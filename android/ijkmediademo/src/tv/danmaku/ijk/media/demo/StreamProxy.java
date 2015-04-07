package tv.danmaku.ijk.media.demo;

import android.text.TextUtils;
import android.util.Log;
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
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.ParserCursor;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.CharArrayBuffer;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamProxy implements Runnable {

    private static final String LOG_TAG = StreamProxy.class.getSimpleName();

    private int port = 0;

    private String url;
    private ExecutorService pool = null;
    private ArrayList<RandomAccessFile> mRandomAccessFilesList = new ArrayList<RandomAccessFile>();
    private long mFileSize = 0;

    private HashMap<Long, DownloadBlock> downloadBlocks = new HashMap<Long, DownloadBlock>();
    private ArrayList<Long> blockStartPositions = new ArrayList<Long>();

    private File tempCacheDir;
    private File saveCacheDir;

    private ProxyCallback proxyCallback;
    private boolean mIsComplete = false;

    public interface ProxyCallback {
        public void onError();

        public void onNetError();
    }

    public StreamProxy(String url, ProxyCallback proxyCallback) {
        super();
        this.url = url;
        this.proxyCallback = proxyCallback;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private boolean isRunning = false;
    private ServerSocket mServerSocket;
    private ArrayList<Socket> mSockets = new ArrayList<Socket>();
    private Thread thread;

    public void init() {
        try {
            mIsComplete = false;
            mServerSocket = new ServerSocket(port, 0, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            mServerSocket.setSoTimeout(5000);
            port = mServerSocket.getLocalPort();
            pool = Executors.newSingleThreadExecutor();
            Log.d(LOG_TAG, "port " + port + " obtained");
        } catch (UnknownHostException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error initializing server", e);
        }
    }

    public void start() {
        if (mServerSocket == null) {
            return;
            // throw new IllegalStateException("Cannot start proxy; it has not been initialized.");
        }

        isRunning = true;
        thread = new Thread(this, "thread-StreamProxy");
        thread.start();
    }

    public void stop(boolean isPlayComplete) {
        Log.d(LOG_TAG, "stopping--" + Thread.currentThread().getName());

        // 关闭所有连接
        synchronized (mSockets) {
            for (Socket s : mSockets) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            mSockets.clear();
        }

        isRunning = false;
        if (thread == null) {
            return;
        }

        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        thread = null;

        stopDownload(isPlayComplete);

        if (null != mServerSocket) {
            try {
                mServerSocket.close();
            } catch (Exception e) {
                Log.w(LOG_TAG,e);
            }
        }
    }

    public void run() {
        Log.d(LOG_TAG, "running");
        while (isRunning) {
            try {
                Log.d(LOG_TAG, "waiting accept");
                final Socket client = mServerSocket.accept();
                if (client == null) {
                    continue;
                }

                synchronized (mSockets) {
                    mSockets.add(client);
                }
                Log.d(LOG_TAG, "client connected add to Sockets the port is:[" + client.getPort() + "]");

                // 获取请求
                final HttpRequest request = readRequest(client);
                if (isRunning) {
                    new Thread(new Runnable() {

                        @Override
                        public void run() {
                            try {
                                processRequest(request, client);
                            } catch (IllegalStateException e) {
                                Log.w(LOG_TAG,e);
                            } catch (IOException e) {
                                Log.w(LOG_TAG,e);
                            }
                        }
                    }, "thread-processRequest").start();
                }
            } catch (SocketTimeoutException e) {
                Log.w(LOG_TAG, "SocketTimeoutException");
            } catch (IOException e) {
                Log.w(LOG_TAG, "Error connecting to client", e);
            }
        }
        Log.d(LOG_TAG, "Proxy interrupted. Shutting down.");
    }

    private HttpRequest readRequest(Socket client) {
        HttpRequest request = null;
        InputStream is;
        String firstLine;
        String range = null;
        String ua = null;
        try {
            is = client.getInputStream();
            // BufferedReader reader = new BufferedReader(new InputStreamReader(is),
            // 8192);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is), 1024);
            firstLine = reader.readLine();
            System.out.println("ijk请求 : " + firstLine);
            String line = null;
            boolean rangeRead = false;
            boolean uaRead = false;

            do {
                line = reader.readLine();
                System.out.println("ijk请求 : " + line);
                if (line != null && line.toLowerCase().startsWith("range: ")) {
                    range = line.substring(7);
                    rangeRead = true;
                }
                if (line != null && line.toLowerCase().startsWith("user-agent: ")) {
                    ua = line.substring(12);
                    uaRead = true;
                }

                if (rangeRead && uaRead) {
                    break;
                }

            } while (!TextUtils.isEmpty(line) && reader.ready());
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error parsing request", e);
            return request;
        }

        if (firstLine == null) {
            Log.i(LOG_TAG, "Proxy client closed connection without a request.");
            return request;
        }

        StringTokenizer st = new StringTokenizer(firstLine);
        String method = st.nextToken();
        String uri = st.nextToken();
        Log.d(LOG_TAG, uri);
        String realUri = uri.substring(1);
        Log.d(LOG_TAG, realUri);
        request = new BasicHttpRequest(method, realUri, new ProtocolVersion("HTTP", 1, 1));
        if (range != null) {
            request.addHeader("Range", range);
        }
        if (ua != null)
            request.addHeader("User-Agent", ua);
        return request;
    }

    // 已经写入的数据总量
    private static long write_total = 0;
    private static long total_file = 0;

    private class DownloadThread extends Thread {
        private final HttpRequest mRequest;

        public DownloadThread(HttpRequest request) {
            mRequest = request;
        }

        public String startThread() {
            Log.e(LOG_TAG, "processing ---");
            String url = mRequest.getRequestLine().getUri();
            HttpParams httpParams = new BasicHttpParams();
            DefaultHttpClient seed = new DefaultHttpClient(httpParams);
            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            SingleClientConnManager mgr = new MyClientConnManager(seed.getParams(), registry);
            DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());
            HttpGet method = new HttpGet(url);

            for (Header h : mRequest.getAllHeaders()) {
                method.addHeader(h);
            }

            HttpResponse realResponse = null;
            try {
                Log.d(LOG_TAG, "starting download");
                realResponse = http.execute(method);
                Log.d(LOG_TAG, "downloaded");
            } catch (ClientProtocolException e) {
                Log.e(LOG_TAG, "Error downloading", e);
                if (null != proxyCallback) {
                    proxyCallback.onError();
                }
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error downloading", e);
                if (null != proxyCallback) {
                    proxyCallback.onError();
                }
            }

            if (realResponse == null) {
                Log.w(LOG_TAG, "realResponse is null.");
                return null;
            }

            if (!isRunning) {
                Log.w(LOG_TAG, "isRunning is false.");
                return null;
            }

            Log.d(LOG_TAG, "downloading...");

            try {
                InputStream data = realResponse.getEntity().getContent();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }

            StatusLine line = realResponse.getStatusLine();
            HttpResponse response = new BasicHttpResponse(line);
            response.setHeaders(realResponse.getAllHeaders());

            long startFrom = 0;
            String rangeParam = mRequest.getFirstHeader("Range") == null ? null : mRequest.getFirstHeader("Range").getValue();
            Log.d(LOG_TAG, "rangeparam" + rangeParam);
            if (!TextUtils.isEmpty(rangeParam)) {
                String range = rangeParam.substring(6);
                int index = range.indexOf('-');
                if (index > 0) {
                    try {
                        String startStr = range.substring(0, index);
                        startFrom = Long.parseLong(startStr);
                        Log.e(LOG_TAG, "====== range: " + startFrom);
                    } catch (NumberFormatException nfe) {
                        Log.w(LOG_TAG,nfe);
                    }
                }
            }

            return null;
        }
    }

    private void processRequest(HttpRequest request, Socket client)
        throws IllegalStateException, IOException {
        if (request == null) {
            return;
        }
        Log.e(LOG_TAG, "processing ---");
        String url = request.getRequestLine().getUri();
        HttpParams httpParams = new BasicHttpParams();
        DefaultHttpClient seed = new DefaultHttpClient(httpParams);
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        SingleClientConnManager mgr = new MyClientConnManager(seed.getParams(), registry);
        DefaultHttpClient http = new DefaultHttpClient(mgr, seed.getParams());
        HttpGet method = new HttpGet(url);

        for (Header h : request.getAllHeaders()) {
            method.addHeader(h);
        }

        HttpResponse realResponse = null;
        try {
            Log.d(LOG_TAG, "starting download");
            realResponse = http.execute(method);
            Log.d(LOG_TAG, "downloaded");
        } catch (ClientProtocolException e) {
            Log.e(LOG_TAG, "Error downloading", e);
            if (null != proxyCallback) {
                proxyCallback.onError();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error downloading", e);
            if (null != proxyCallback) {
                proxyCallback.onError();
            }
        }

        if (realResponse == null) {
            Log.w(LOG_TAG, "realResponse is null.");
            return;
        }

        if (!isRunning) {
            Log.w(LOG_TAG, "isRunning is false.");
            return;
        }

        Log.d(LOG_TAG, "downloading...");

        InputStream data = realResponse.getEntity().getContent();
        StatusLine line = realResponse.getStatusLine();
        HttpResponse response = new BasicHttpResponse(line);
        response.setHeaders(realResponse.getAllHeaders());

        long startFrom = 0;
        String rangeParam = request.getFirstHeader("Range") == null ? null : request.getFirstHeader("Range").getValue();
        Log.d(LOG_TAG, "rangeparam" + rangeParam);
        if (!TextUtils.isEmpty(rangeParam)) {
            String range = rangeParam.substring(6);
            int index = range.indexOf('-');
            if (index > 0) {
                try {
                    String startStr = range.substring(0, index);
                    startFrom = Long.parseLong(startStr);
                    Log.e(LOG_TAG, "====== range: " + startFrom);
                } catch (NumberFormatException nfe) {
                    Log.w(LOG_TAG,nfe);
                }
            }
        }

        RandomAccessFile randomAccessFile = null;

        // 首次mRandomAccessFile为空，创建时确定文件长度，后面进入的请求创建新的文件句柄
        if (!mIsComplete) {
            try {
                synchronized (mRandomAccessFilesList) {
                    // 首次确定文件长度
                    if (mRandomAccessFilesList.size() == 0) {
                        tempCacheDir = new File("/mnt/sdcard/");
                        File tempFile = getTempCacheFile();
                        tempFile.delete();
                        long fileSize = realResponse.getEntity().getContentLength();
                        mFileSize = fileSize;
                        total_file = mFileSize;
                        Log.d(LOG_TAG, "FileSize is :" + mFileSize);
                    }

                    // 创建文件句柄
                    randomAccessFile = new RandomAccessFile(getTempCacheFile().getAbsolutePath(), "rw");
                    randomAccessFile.setLength(mFileSize);
                    randomAccessFile.seek(startFrom);
                    mRandomAccessFilesList.add(randomAccessFile);
                }
            } catch (Exception e) {
                Log.w(LOG_TAG,e);
            }
        }

        Log.d(LOG_TAG, "reading headers");
        StringBuilder httpString = new StringBuilder();
        httpString.append(response.getStatusLine().toString());

        httpString.append("\n");
        for (Header h : response.getAllHeaders()) {
            httpString.append(h.getName()).append(": ").append(h.getValue()).append("\n");
        }
        httpString.append("\n");
        Log.d(LOG_TAG, "headers done");

        final DownloadBlock downloadBlock = new DownloadBlock();
        downloadBlock.setStart(startFrom);

        long count = 0;
        try {
            byte[] buffer = httpString.toString().getBytes();
            int readBytes = -1;
            Log.d(LOG_TAG, "writing to client " + httpString.toString());
            client.getOutputStream().write(buffer, 0, buffer.length);
            client.getOutputStream().flush();

            // Start streaming content.
            byte[] buff = new byte[100 * 1024];
            try {
                readBytes = data.read(buff, 0, buff.length);
            } catch (Exception e) {
                if (null != proxyCallback) {
                    proxyCallback.onNetError();
                }
                Log.w(LOG_TAG,e);
            }
            while (isRunning && readBytes != -1) {
                // while ((readBytes = data.read(buff, 0, buff.length)) != -1) {

                // 先写本地文件保证数据安全，再写入mediaplayer
                if (!mIsComplete) {
                    final int writeBytes = readBytes;
                    // 异步写入
                    // final byte[] savedBuff = new byte[writeBytes];
                    // System.arraycopy(buff, 0, savedBuff, 0, writeBytes);
                    // writeFile(false, randomAccessFile, downloadBlock, savedBuff, writeBytes);
                    // 同步写入
                    writeFile(true, randomAccessFile, downloadBlock, buff, writeBytes);
                    write_total += readBytes;
                    count = +readBytes;
                    count += readBytes;
                }

                OutputStream outputStream = client.getOutputStream();
                if (null != outputStream) {
                    outputStream.write(buff, 0, readBytes);
                    outputStream.flush();
                }

                try {
                    readBytes = data.read(buff, 0, buff.length);
                    Log.d(LOG_TAG, "total ! write_total " + write_total);
                } catch (Exception e) {
                    if (null != proxyCallback) {
                        proxyCallback.onNetError();
                    }
                    Log.w(LOG_TAG,e);
                }
            }
        } catch (Exception e) {
            Log.w("", e.getMessage(), e);
        } finally {
            Log.d(LOG_TAG, "write over startfrom and count : " + startFrom + " / " + count);
            mgr.shutdown();
            client.close();
            downloadBlock.setDownloadComplete(true);
            addDownloadBlock(downloadBlock);

            // 文件合并
            saveCachedFile();

            Log.d(LOG_TAG, "streaming complete");
        }
    }

    private class IcyLineParser extends BasicLineParser {
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

    class MyClientConnManager extends SingleClientConnManager {
        private MyClientConnManager(HttpParams params, SchemeRegistry schreg) {
            super(params, schreg);
        }

        @Override
        protected ClientConnectionOperator createConnectionOperator(final SchemeRegistry sr) {
            return new MyClientConnectionOperator(sr);
        }
    }

    private void writeFile(boolean sync, final RandomAccessFile randomAccessFile, final DownloadBlock downloadBlock,
        final byte[] savedBuff, final int writeBytes) {

        if (null == randomAccessFile) {
            Log.w(LOG_TAG, "randomAccessFile is null . ");
            return;
        }

        if (sync) {
            internalWriteFile(randomAccessFile, downloadBlock, savedBuff, writeBytes);
        } else {
            pool.execute(new Runnable() {

                @Override
                public void run() {
                    internalWriteFile(randomAccessFile, downloadBlock, savedBuff, writeBytes);
                }
            });
        }
    }

    private synchronized void internalWriteFile(RandomAccessFile randomAccessFile, final DownloadBlock downloadBlock,
        final byte[] savedBuff, final int writeBytes) {
        if (mIsComplete) {
            return;
        }

        try {
            randomAccessFile.write(savedBuff, 0, writeBytes);
            downloadBlock.setBlockSize(downloadBlock.getBlockSize() + writeBytes);
            if (downloadBlock.isDownloadComplete()) {
                addDownloadBlock(downloadBlock);
                saveCachedFile();
            }
        } catch (Exception e) {
            Log.w(LOG_TAG,e);
        }
    }

    private File getTempCacheFile() {
        return new File(tempCacheDir, md5hash(url));
    }

    static String md5hash(String key) {
        MessageDigest hash = null;
        try {
            hash = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }

        hash.update(key.getBytes());
        byte[] digest = hash.digest();
        StringBuilder builder = new StringBuilder();
        for (int b : digest) {
            builder.append(Integer.toHexString((b >> 4) & 0xf));
            builder.append(Integer.toHexString((b >> 0) & 0xf));
        }
        // builder.append(".mv");
        return builder.toString();
    }

    private void stopDownload(boolean isPlayComplete) {

        // 关闭所有文件句柄
        synchronized (mRandomAccessFilesList) {
            for (RandomAccessFile randomAccessFile : mRandomAccessFilesList) {
                if (null != randomAccessFile) {
                    try {
                        randomAccessFile.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            mRandomAccessFilesList.clear();
        }

        pool.shutdown();
        saveCachedFile();
        downloadBlocks.clear();
    }

    private void addDownloadBlock(DownloadBlock downloadBlock) {
        if (mIsComplete) {
            return;
        }

        if (null != downloadBlock) {
            long blockSize = 0;
            DownloadBlock existBlock = downloadBlocks.remove(downloadBlock.getStart());
            if (null != existBlock) {
                blockSize = existBlock.getBlockSize();
                if (blockSize > downloadBlock.getBlockSize()) {
                    downloadBlock.setBlockSize(blockSize);
                }
            }
            downloadBlocks.put(downloadBlock.getStart(), downloadBlock);
            synchronized (blockStartPositions) {
                blockStartPositions.add(downloadBlock.getStart());
            }
        }
    }

    private class DownloadBlock {
        private long start = 0;
        private long blockSize = 0;
        private boolean downloadComplete;

        public long getStart() {
            return start;
        }

        public void setStart(long start) {
            this.start = start;
        }

        public long getBlockSize() {
            return blockSize;
        }

        public void setBlockSize(long blockSize) {
            this.blockSize = blockSize;
        }

        public boolean isDownloadComplete() {
            return downloadComplete;
        }

        public void setDownloadComplete(boolean downloadComplete) {
            this.downloadComplete = downloadComplete;
        }

    }

    private boolean checkFileDownloadComplete() {
        boolean isComplete = false;

        if (null == blockStartPositions || blockStartPositions.isEmpty()) {
            return isComplete;
        }

        try {
            Collections.sort(blockStartPositions);
        } catch (Exception e) {
            Log.w(LOG_TAG,e);
            return isComplete;
        }
        if (blockStartPositions.get(0) != 0) {
            return isComplete;
        }

        long blockStart = 0;
        long blockSize = 0;
        // TODO ConcurrentModificationException http://zhoujianghai.iteye.com/blog/1041555
        synchronized (blockStartPositions) {
            for (Long startPosition : blockStartPositions) {
                DownloadBlock downloadBlock = downloadBlocks.get(startPosition);
                if (null != downloadBlock && downloadBlock.getStart() >= blockStart
                    && downloadBlock.getStart() <= blockSize) {
                    blockSize = downloadBlock.getBlockSize() + blockSize - (blockSize - downloadBlock.getStart());
                }
            }
        }

        if (blockSize == mFileSize) {
            Log.v(LOG_TAG, "set complete ok success !" + "  blockSize is [" + blockSize + "]");
            isComplete = true;
        }

        return isComplete;
    }

    private void saveCachedFile() {
        if (TextUtils.isEmpty(url) || !checkFileDownloadComplete()) {
            return;
        }
        saveCacheDir = new File("/mnt/sdcard/");
        File file = new File(saveCacheDir, md5hash(url));
        if (null != file && null != file.getParentFile() && !file.getParentFile().exists()) {
            boolean reMkDirs = file.getParentFile().mkdirs();
            if (!reMkDirs) {
                Log.d(LOG_TAG,"save Foler not exits, reMkDirs = " + reMkDirs);
            }
        }
        File cacheFile = getTempCacheFile();
        boolean result = cacheFile.renameTo(file);
        if (result) {
            Log.d(LOG_TAG,"Set file write complete success !");
            mIsComplete = true;
        } else {
            Log.w(LOG_TAG,"remove video file to save Foler failed!");
        }
    }

}
