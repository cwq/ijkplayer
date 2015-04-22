package tv.danmaku.ijk.media.demo;

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.OperatedClientConnection;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.DefaultClientConnection;
import org.apache.http.impl.conn.DefaultClientConnectionOperator;
import org.apache.http.impl.conn.DefaultResponseParser;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.ParserCursor;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.CharArrayBuffer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * 区间下载线程
 * 
 */
public class RangDownloadThread extends PriorityHandlerThread implements Handler.Callback {

	private static final String LOG_TAG = RangDownloadThread.class.getSimpleName();

	private static final int WAIT_CACHE_SIZE = 128*1024;     //128kb 以内的小偏移

	private static final int DOWNLOADING_INTERVAL_MS = 10;

	private static final int IDLE_INTERVAL_MS = 1000;
	// 减速的倍数
	private static final int MULTIPLE_SLOW = 3;
	// 正常速度
	private static final int NORMAL_SPEED = 1;

	// 线程消息处理handler
	private final Handler eventHandler;

	private int download_write_off;

	private int download_start;


	// 下载文件的总长度
	private int file_size;

	/**
	 * 下载数据长度
	 * 
	 */
	private int content_length;

	private InputStream input_stream;

	private int written_total;

	private boolean eof = false;

	private RandomAccessFile randomAccessFile;
	// 处理内部逻辑Handler
	private Handler handler;

	private MyClientConnManager mgr;

	// 下载数据缓冲区
	private byte[] buff = new byte[65535];
	// 上一次发送状态消息的时间
	private long last_send_time = -1;

	// 当前下载状态
	private int state = DownloadTask.STATE_IDLE;
	private int multiple = NORMAL_SPEED;
	// 下一次请求的区间
	private StreamProxy.BlockHttpRequest nextRequest;
	// 日志打印计数器
	private int count = 0;

	// 现在正在的服务的块请求对象
	private StreamProxy.BlockHttpRequest mRequest;

	/**
	 * 创建区间下载进程
	 * @param name 线程名
	 * @param cachePath 线程的现在路径
	 * @param eventHandler 线程消息处理线程的事件handler
	 */
	public RangDownloadThread(String name, String cachePath, Handler eventHandler) {
		super(name);

		this.eventHandler = eventHandler;

		try {
			randomAccessFile = new RandomAccessFile(cachePath, "rw");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 设置状态
	 * @param state 状态值
	 */
	private void setState(int state) {
		if (this.state != state) {
			this.state = state;
		}
	}

	/**
	 * 获取下载文件的大小
	 * @return 返回文件大小，单位byte
	 */
	public int getFileSize() {
		return file_size;
	}

	@Override
	public synchronized void start() {
		super.start();
		handler = new Handler(this.getLooper(), this);
	}

	/**
	 * 推送一个新的请求。让线程响应请求
	 * @param request 对应块下载请求
	 */
	public void postRequest(StreamProxy.BlockHttpRequest request) {
		nextRequest = request;
		handler.removeMessages(DownloadTask.MSG_DOING_DOWNLOAD);// 清楚下载请求加速对事件的响应
		Message message = handler.obtainMessage(DownloadTask.MSG_SEEK_BLOCK, request);
		handler.sendMessage(message);
	}

	public void synchronizePostRequest(StreamProxy.BlockHttpRequest request) {
		Log.e(LOG_TAG, "JAVAN  synchronizePostRequest start");
		long start = SystemClock.elapsedRealtime();
		postRequest(request);

		while (mRequest != request) {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			Log.e(LOG_TAG, "JAVAN  waiting.....");
		}

		Log.e(LOG_TAG, "JAVAN  synchronizePostRequest end cost " + (SystemClock.elapsedRealtime() - start));
		StringBuilder info = new StringBuilder(256);

		info.append("开启网络请求使用时长:").append((SystemClock.elapsedRealtime() - start));

		Message message = eventHandler.obtainMessage(DownloadTask.MSG_NOTIFY_INFO, info.toString());
		eventHandler.sendMessage(message);
	}

	private void sendDownloadStatus() {
		Message message = eventHandler.obtainMessage(DownloadTask.MSG_UPDATE_DOWNLOAD_STATUS, download_start, download_write_off);
		message.obj = this;
		eventHandler.sendMessage(message);
	}

	@Override
	public boolean handleMessage(Message message) {
		switch (message.what) {
			case DownloadTask.MSG_SEEK_BLOCK:
			{
				// 切换区间下载任务
				try {
					seekToInternal(message.obj);
				} catch (Exception e) {
					e.printStackTrace();
					eventHandler.sendEmptyMessage(DownloadTask.MSG_ERROR);
				}
			}
				break;
			case DownloadTask.MSG_DOING_DOWNLOAD:
			{
				// 执行下载代码
				downloadData();
			}
			break;
			case DownloadTask.MSG_STOP:
			{
				stopDownloadInternal();
			}
			break;
			case DownloadTask.MSG_CHANGE_RANGE:
			{
				if (message.arg1 == download_start) {
					content_length = message.arg2 - message.arg1;
				}
			}
			break;
			default:
				break;
		}
		return false;
	}

	/**
	 * 执行下载动作并返回实际下载数据
	 */
	private void downloadData() {

		long operationStartTimeMs = SystemClock.elapsedRealtime();

		doSomeWork(false);

		handler.removeMessages(DownloadTask.MSG_DOING_DOWNLOAD);

		if (!isEnded() && (state == DownloadTask.STATE_READY) || state == DownloadTask.STATE_BUFFERING ) {
			scheduleNextOperation(DownloadTask.MSG_DOING_DOWNLOAD, operationStartTimeMs, DOWNLOADING_INTERVAL_MS * multiple);
		} else if (state != DownloadTask.STATE_IDLE ) {
			scheduleNextOperation(DownloadTask.MSG_DOING_DOWNLOAD, operationStartTimeMs, IDLE_INTERVAL_MS);
		}
	}

	private void scheduleNextOperation(int operationType, long thisOperationStartTimeMs,
									   long intervalMs) {
		long nextOperationStartTimeMs = thisOperationStartTimeMs + intervalMs;
		long nextOperationDelayMs = nextOperationStartTimeMs - SystemClock.elapsedRealtime();
		if (nextOperationDelayMs <= 0) {
			handler.sendEmptyMessage(operationType);
		} else {
			handler.sendEmptyMessageDelayed(operationType, nextOperationDelayMs);
		}
	}

	private void seekToInternal(Object data) throws Exception {
		StreamProxy.BlockHttpRequest request = null;
		if (data instanceof StreamProxy.BlockHttpRequest) {
			request = (StreamProxy.BlockHttpRequest) data;
		}

		if (request == null) return;

		// 直接调用内部停止功能
		stopDownloadInternal();
		start(request);

		setState(DownloadTask.STATE_READY);
		// 修改成正常速度NORMAL_SPEED
		multiple = NORMAL_SPEED;

		handler.sendEmptyMessage(DownloadTask.MSG_DOING_DOWNLOAD);
	}

	void doSomeWork(boolean checkSpeed) {
		int readBytes = -1;

		if (input_stream == null) { // 没有输入流
			setState(DownloadTask.STATE_IDLE);
			return;
		}

		// 加入下载速度的控制
		if (checkSpeed) {
			// 是否要进行速度的限制
		}

		try {
			long begin = SystemClock.elapsedRealtime();
			readBytes = input_stream.read(buff, 0, buff.length);
			if (readBytes > 0 && SystemClock.elapsedRealtime() - begin > 100) {
				Log.d(LOG_TAG, "Javan end fileData.read readBytes " + readBytes + " cost time " + (SystemClock.elapsedRealtime() - begin));
			} else if (readBytes < 0){
				eof = true;
				// 最后发送一次状态
				sendDownloadStatus();
				// 关闭下载，文件已经读到末尾了，可能是因为网络异常
				stopDownloadInternal();
				Log.w(LOG_TAG, "Socket download read nothing.");
			}
		}  catch (IOException e) {
			e.printStackTrace();
			Log.w(LOG_TAG, e);
		}

		if (readBytes < 0) {
			return;
		}

		try {

			// 每次下载后更新偏移值
			randomAccessFile.write(buff, 0, readBytes);
			written_total += readBytes;
			download_write_off  += readBytes;
			if (written_total >= content_length) {
				Log.e(LOG_TAG, "write_off Out of file size" + download_write_off);
				mgr.shutdown();
				mgr = null;
				input_stream.close();
				input_stream = null;
				sendDownloadStatus();
				return;
			} else {
				// 通知主控制着现在下载数据的变化
				if (last_send_time < 0 || SystemClock.elapsedRealtime() - last_send_time > 30) {
					sendDownloadStatus();
					last_send_time = SystemClock.elapsedRealtime();
				}
			}

			if (multiple == NORMAL_SPEED) {
				Log.d(LOG_TAG, "download_start " + download_start + " download_write_off " + download_write_off + " readBytes " + readBytes);
			} else {
				if (count == 20) {
					Log.d(LOG_TAG, "download_start " + download_start + " download_write_off " + download_write_off + " readBytes " + readBytes);
					count = 0;
				} else {
					count++;
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void start(StreamProxy.BlockHttpRequest request) {

		HttpRequest httpRequest = request.obtain();
		boolean error = false;
		String url = httpRequest.getRequestLine().getUri();

		HttpParams httpParams = new BasicHttpParams();
		DefaultHttpClient seed = new DefaultHttpClient(httpParams);
		SchemeRegistry registry = new SchemeRegistry();
		registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		mgr = new MyClientConnManager(seed.getParams(), registry);

		DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());

		HttpGet method = new HttpGet(url);

		for (Header h : httpRequest.getAllHeaders()) {
			method.addHeader(h);
		}

		HttpResponse realResponse = null;
		int retry_times = 3;
		do {
			try {
				Log.d(LOG_TAG, "starting download");
				long begin = SystemClock.elapsedRealtime();
				realResponse = http.execute(method);
				Log.d(LOG_TAG, "request downloadStart " + request.downloadStart + " http.execute(method); cost time " + (SystemClock.elapsedRealtime() - begin) + " Thread id " + Thread.currentThread().getId());
			} catch (ClientProtocolException e) {
				Log.e(LOG_TAG, "Error downloading", e);
				error = true;
			} catch (IOException e) {
				Log.e(LOG_TAG, "Error downloading", e);
				error = true;
			} finally {
				retry_times--;
			}
		} while(error && retry_times > 0);

		try {
			input_stream = realResponse.getEntity().getContent();
		} catch (IOException e) {
			e.printStackTrace();
			error = true;
		}

		StatusLine line = realResponse.getStatusLine();
		HttpResponse response = new BasicHttpResponse(line);
		response.setHeaders(realResponse.getAllHeaders());

		content_length = (int)realResponse.getEntity().getContentLength();

		if (request.rangeEnd < 0) {    // 如果默认小于0，修正成实际数值
			request.rangeEnd = request.downloadStart + content_length - 1;
		}

		Log.d(LOG_TAG, "reading headers");
		StringBuilder httpString = new StringBuilder();
		httpString.append(response.getStatusLine().toString());

		httpString.append("\n");
		String rangeParam = null;
		for (Header h : response.getAllHeaders()) {
			if (h.getName().startsWith("Content-Range")) {
				rangeParam = h.getValue();
				int index = rangeParam.indexOf('/');
				if (index > 0) {
					try {
						String strFilelength = rangeParam.substring(index+1);
						file_size = Integer.parseInt(strFilelength);
						Log.e(LOG_TAG, "====== file_size: " + file_size);
					} catch (NumberFormatException nfe) {
						Log.w(LOG_TAG, nfe);
					}
				}
				httpString.append("Content-Range: bytes ").append(request.rangeStart)
						.append("-").append(file_size-1).append('/').append(file_size).append("\n");
			} else {
				httpString.append(h.getName()).append(": ").append(h.getValue()).append("\n");
			}
		}

		httpString.append("\n");
		Log.d(LOG_TAG, "headers done");

		// 还没下载过
		// 创建文件句柄
		try {
			randomAccessFile.setLength(file_size);
			randomAccessFile.seek(request.downloadStart);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			error = true;
		} catch (IOException e) {
			e.printStackTrace();
			error = true;
		}

		if (!error && request.client != null) {
			byte[] buffer = httpString.toString().getBytes();

//			Log.d(LOG_TAG, httpString.toString());

			try {
				request.client.getOutputStream().write(buffer, 0, buffer.length);
				request.client.getOutputStream().flush();
			} catch (IOException e) {
				e.printStackTrace();
				error = true;
			}
		}

		if (!error) {
			Log.e(LOG_TAG, "Start without error");
			// 更新下载状态成请求状态
			download_write_off = (int)request.downloadStart;
			download_start = (int)request.downloadStart;
			written_total = 0;
			eof = false;
		} else {
			stopDownloadInternal();
		}

		mRequest = request;
	}

	/**
	 * 停止下载
	 */
	void stopDownload() {
		if (mgr != null) {
			handler.removeMessages(DownloadTask.MSG_DOING_DOWNLOAD);
			handler.sendEmptyMessage(DownloadTask.MSG_STOP);
		}
	}

	void stopDownloadInternal() {
		if (mgr != null) {
			mgr.shutdown();
			try {
				input_stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				input_stream = null;
			}
			mgr = null;
		}

		// 重置主要的标记参数
		written_total = 0;
		eof = false;
		download_start = 0;
		download_write_off = 0;
		last_send_time = -1;
		content_length = 0;

		setState(DownloadTask.STATE_IDLE);
	}

	/**
	 * 判断是否已经下载完成
	 * @return true 数据下载完成了，或是到达文件尾部。 false，说明下载还在进行中
	 *
	 */
	public boolean isEnded() {
		return file_size != 0 && (written_total >= content_length || eof);
	}

	/**
	 * 判断一个下载请求的块是否落在当前的下载线程区间内
	 *
	 * @param request 当前的块请求
	 * @param position 目标请求位置
	 * @return true 表示确实。false 表示不是
	 */
	public boolean isRequestInRange(StreamProxy.BlockHttpRequest request, int position) {
		boolean res = false;

		// 判断位置是否在已经下载的区间内
		if (position >= download_start && download_write_off >= position) {    // 在后备下载区间范围之内
			res = true;
		} else if (position >= download_write_off && position - download_write_off <= WAIT_CACHE_SIZE) {  // 比后备下载
			if (position > request.rangeStart && (position - request.rangeStart) > WAIT_CACHE_SIZE ) {
				Log.w(LOG_TAG, "JAVAN position "+ position + " far away from request " + request.rangeStart);
				res = true;
			} else {
				do {
					handler.sendEmptyMessage(DownloadTask.MSG_DOING_DOWNLOAD);
				} while (download_write_off < position && !isEnded());

				if (download_write_off >= position) {
					res = true;
				} else {
					res = false;
				}
			}
		} else {
			res = false;
		}

		return res;
	}

	public Pair<Integer, Integer> getDownloadRange() {
		return new Pair<Integer, Integer>(download_start, download_start+content_length);
	}

	/**
	 * 当前已经下载到的位置
	 * @return 返回当前下载到得位置
	 */
	public int getCurrentPosition() {
		return download_write_off;
	}

	public void setDownloadRange(int start, int end) {
		Message message = handler.obtainMessage(DownloadTask.MSG_CHANGE_RANGE, start, end);
		handler.sendMessage(message);
	}

	public void setSpeedDown() {
		multiple = MULTIPLE_SLOW;
	}

	public void resetSpeed() {
		multiple = NORMAL_SPEED;
		handler.sendEmptyMessage(DownloadTask.MSG_DOING_DOWNLOAD);
	}

	class IcyLineParser extends BasicLineParser {
		private static final String ICY_PROTOCOL_NAME = "ICY";

		private IcyLineParser() {
			super();
		}

		@Override
		public boolean hasProtocolVersion(CharArrayBuffer buffer, ParserCursor cursor) {
			boolean superFound = super.hasProtocolVersion(buffer, cursor);
			if (superFound) {
				return true;
			}
			int index = cursor.getPos();

			final int protolength = ICY_PROTOCOL_NAME.length();

			if (buffer.length() < protolength)
				return false; // not long enough for "HTTP/1.1"

			if (index < 0) {
				// end of line, no tolerance for trailing whitespace
				// this works only for single-digit major and minor version
				index = buffer.length() - protolength;
			} else if (index == 0) {
				// beginning of line, tolerate leading whitespace
				while ((index < buffer.length()) && HTTP.isWhitespace(buffer.charAt(index))) {
					index++;
				}
			} // else within line, don't tolerate whitespace

			return index + protolength <= buffer.length()
					&& buffer.substring(index, index + protolength).equals(ICY_PROTOCOL_NAME);

		}

		@Override
		public ProtocolVersion parseProtocolVersion(CharArrayBuffer buffer, ParserCursor cursor)
				throws ParseException {

			if (buffer == null) {
				throw new IllegalArgumentException("Char array buffer may not be null");
			}
			if (cursor == null) {
				throw new IllegalArgumentException("Parser cursor may not be null");
			}

			final int protolength = ICY_PROTOCOL_NAME.length();

			int indexFrom = cursor.getPos();
			int indexTo = cursor.getUpperBound();

			skipWhitespace(buffer, cursor);

			int i = cursor.getPos();

			// long enough for "HTTP/1.1"?
			if (i + protolength + 4 > indexTo) {
				throw new ParseException("Not a valid protocol version: " + buffer.substring(indexFrom, indexTo));
			}

			// check the protocol name and slash
			if (!buffer.substring(i, i + protolength).equals(ICY_PROTOCOL_NAME)) {
				return super.parseProtocolVersion(buffer, cursor);
			}

			cursor.updatePos(i + protolength);

			return createProtocolVersion(1, 0);
		}

		@Override
		public StatusLine parseStatusLine(CharArrayBuffer buffer, ParserCursor cursor)
				throws ParseException {
			return super.parseStatusLine(buffer, cursor);
		}
	}

	class MyClientConnection extends DefaultClientConnection {
		@Override
		protected HttpMessageParser createResponseParser(final SessionInputBuffer buffer,
														 final HttpResponseFactory responseFactory, final HttpParams params) {
			return new DefaultResponseParser(buffer, new IcyLineParser(), responseFactory, params);
		}
	}

	class MyClientConnectionOperator extends DefaultClientConnectionOperator {
		public MyClientConnectionOperator(final SchemeRegistry sr) {
			super(sr);
		}

		@Override
		public OperatedClientConnection createConnection() {
			return new MyClientConnection();
		}
	}

	class MyClientConnManager extends ThreadSafeClientConnManager {
		MyClientConnManager(HttpParams params, SchemeRegistry schreg) {
			super(params, schreg);
		}

		@Override
		protected ClientConnectionOperator createConnectionOperator(final SchemeRegistry sr) {
			return new MyClientConnectionOperator(sr);
		}
	}
}
