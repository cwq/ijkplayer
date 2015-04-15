package tv.danmaku.ijk.media.demo;

import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
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
import java.util.*;

public class DownloadDataProcessor extends DataProcessor {

    private static final String LOG_TAG = DownloadDataProcessor.class.getSimpleName();
    private static final int WAIT_CACHE_SIZE = 128*1024;     //128kb 以内的小偏移

    private StreamProxy.BlockHttpRequest mRequest;
    private DownloadStatus mMainStatus;

    // 标示是否已经下载完成了。
    private boolean isAllFinished = false;
    private long mFileSize = 0;
    // 存储已经保存好的块数据
    // [a,b)  数据范围 a <= 索引 < b.
    private HashMap<Long, Long> downloadedBlock = new HashMap<Long, Long>(30);
    // 临时拼接前后下载链接的数据块
    private HashMap<Long, Long> tmpBlocks = new HashMap<Long, Long>(2);

    // 位于前部的下载任务
    private static final int TASK_FRONT = 0;
    // 位于后部的下载任务
    private static final int TASK_BEHIND = 1;

    private RangDownloadTask[] rangDownloadTasks = new RangDownloadTask[2];

    // 下载数据缓冲区
    byte[] buff = new byte[65535];
    private int mainTask = -1;

    public DownloadDataProcessor(StreamProxy.BlockHttpRequest request, String cachePath, DownloadStatus status) {
        mRequest = request;
        mMainStatus = status;

        for (int i = 0; i < rangDownloadTasks.length; i++) {
            rangDownloadTasks[i] = new RangDownloadTask(cachePath);
        }

    }

    @Override
    void doSomeWork() {
        if (isAllFinished) {
            // 已经都下载好了
            // 写入的最终值改成文件大小
            mMainStatus.write_off = mFileSize;
            return;
        }

        tmpBlocks.clear();

        for (int i = 0; i < rangDownloadTasks.length; i++) {
            rangDownloadTasks[i].doSomeWork(true);
            DownloadStatus status = rangDownloadTasks[i].status;
            tmpBlocks.put(status.download_start, status.write_off);
        }

        connectBlocks(tmpBlocks);

        if (!isAllFinished && !tmpBlocks.isEmpty() && tmpBlocks.size() == 1) {  // 判断是否已经下载完成了
            if (tmpBlocks.get(Long.valueOf(0)) != null) {
                if (mFileSize == tmpBlocks.get(0L)) {
                    // file 完整下载了
                    isAllFinished = true;
                } else if (tmpBlocks.get(0L) > rangDownloadTasks[TASK_BEHIND].status.download_start){
//                    // 说明下载的数据已经拼接起来了
//                    mainTask = TASK_BEHIND;
                }
            }
        }
        if (!isAllFinished) {
            if (rangDownloadTasks[mainTask].isEnded() || mMainStatus.read_off > mMainStatus.write_off) {
                // 停止下载了，找到包含目前write off 的block
                Set<Map.Entry<Long, Long>> entrySet = downloadedBlock.entrySet();
                long request_write_off = mMainStatus.read_off;  // 请求的就是已经读到的位置
                for (Map.Entry<Long,Long> entry : entrySet) {
                    if (entry.getKey() <= request_write_off && entry.getValue() >= request_write_off) {
                        mMainStatus.write_off = entry.getValue();
                        break;
                    }
                }
            } else {
                mMainStatus.copyDownloadProgress(rangDownloadTasks[mainTask].status);
            }
        } else {
            // 全部下载了直接将主状态的写入偏移等于文件长度
            mMainStatus.write_off = mFileSize;
        }
    }

    // 启动下载开启需要的
    @Override
    void start() {
        rangDownloadTasks[TASK_FRONT].start(mRequest);
        StreamProxy.BlockHttpRequest halfRequest = mRequest.copy();
        // 不需要传递给客户端，只是缓冲数据
        halfRequest.client = null;
        halfRequest.rangeStart = mFileSize/2;
        halfRequest.downloadStart = halfRequest.rangeStart;

//        rangDownloadTasks[TASK_BEHIND].start(halfRequest);

        mainTask = TASK_FRONT;
    }

    @Override
    boolean isEnded() {
        return false;
    }

    @Override
    public void seekTo(StreamProxy.BlockHttpRequest request) throws Exception {
        Log.e(LOG_TAG, "Begin to seek to request " + request.rangeStart + " mainTask " + mainTask);
        int remove_index = mainTask == TASK_FRONT ? TASK_BEHIND : TASK_FRONT;

        // 将当前已经下载的部分当做一个block参与计算
        Long position = null;
        // 目前主下载任务的状态
        DownloadStatus focusDownloadStatus = rangDownloadTasks[mainTask].status;

        if (!isAllFinished) {   // 未全部下载完成的时候需要将当前下载的参与计算，如果已经全部下载了。说明Block只有1个0 ~ 文件长度。
            // 找到最佳起始下载文件的位置。避免重复下载
            position = findBlockPosition(request);
        } else {    // 全部完成了
            position = mFileSize;
        }

        if (position == focusDownloadStatus.write_off || position == mFileSize) {   // 刚好等于当前下载位置，或是请求新的下载位置直接就是文件的大小，说明从请求到文件结束段的数据已经下载好了。
            // 说明新的位置数据已经下载好了不需要移动下载位置,继续下载就好
//            for (int i = 0; i < rangDownloadTasks.length; i++) {
//                downloadedBlock.remove(rangDownloadTasks[i].status.download_start);
//            }

        } else if (position > focusDownloadStatus.write_off && position - focusDownloadStatus.write_off < WAIT_CACHE_SIZE) {
            do {
                rangDownloadTasks[mainTask].doSomeWork(false);
            } while (focusDownloadStatus.write_off < position && !rangDownloadTasks[mainTask].isEnded());

            if (rangDownloadTasks[mainTask].isEnded()) {
                connectBlocks(downloadedBlock);
                long final_position = -1;
                Set<Map.Entry<Long, Long>> entrySet = downloadedBlock.entrySet();
                for (Map.Entry<Long,Long> entry : entrySet) {
                    if (entry.getKey() <= position && entry.getValue() >= position) {
                        final_position = entry.getValue();
                        break;
                    }
                }

                if (rangDownloadTasks[remove_index].status.write_off == final_position) {
                    mainTask = remove_index;
                } else if (final_position == -1){
                    // 没找到
                    rangDownloadTasks[mainTask].start(null);
                } else {
                    rangDownloadTasks[mainTask].start(null);
                }
            } else {
                // 正在结束
            }
        } else if (position >= focusDownloadStatus.download_start && position <= focusDownloadStatus.write_off) {
            // 位置刚好在当前下载快范围内
//            for (int i = 0; i < rangDownloadTasks.length; i++) {
//                downloadedBlock.remove(rangDownloadTasks[i].status.download_start);
//            }
        } else { // 需要重启链接的情况
            // 需要发起新的请求距离偏大，但是我们可以check下位于后部的下载请求是否
            DownloadStatus maybeRangeStatus = rangDownloadTasks[remove_index].status;
            connectBlocks(downloadedBlock);
            // 在拼接的快之后重新获取最佳位置
            position = findBlockPosition(request);

            if (!isAllFinished && !downloadedBlock.isEmpty() && downloadedBlock.size() == 1) {  // 判断是否已经下载完成了
                if (downloadedBlock.get(0L) != null && mFileSize == downloadedBlock.get(0L)) {
                    // file 完整下载了
                    isAllFinished = true;
                }
            }

            if (!isAllFinished) {
                // 判断位置是否在另外一个下载块，已经下载的区间内
                if (position >= maybeRangeStatus.download_start && maybeRangeStatus.write_off >= position) {    // 在后备下载区间范围之内
                    mainTask = remove_index;
                    Log.e(LOG_TAG, "JAVAN1 position " + position + " mainTask " + mainTask);
                } else if (position >= maybeRangeStatus.write_off && position - maybeRangeStatus.write_off <= WAIT_CACHE_SIZE) {  // 比后备下载
                    if (position > request.rangeStart && (position - request.rangeStart) > WAIT_CACHE_SIZE ) {
                        Log.e(LOG_TAG, "JAVAN position "+ position + " far away from request " + request.rangeStart);
                        mainTask = remove_index;
                    } else {
                        do {
                            rangDownloadTasks[remove_index].doSomeWork(false);
                        } while (maybeRangeStatus.write_off < position && !rangDownloadTasks[remove_index].isEnded());

                        if (rangDownloadTasks[remove_index].isEnded()) {
                            connectBlocks(downloadedBlock);

                            Set<Map.Entry<Long, Long>> entrySet = downloadedBlock.entrySet();
                            for (Map.Entry<Long,Long> entry : entrySet) {
                                if (entry.getValue() == mFileSize) {
                                    break;
                                }
                            }
                            rangDownloadTasks[remove_index].start(null);
                        } else {
                            mainTask = remove_index;
                        }
                    }
                    Log.e(LOG_TAG, "JAVAN2 position " + position + " mainTask " + mainTask);
                } else {
                    if (position == focusDownloadStatus.write_off) {
                        // 刚好等于焦点的当前标志位不需要创建新的链接
                        Log.e(LOG_TAG, "JAVAN2 position " + position + " focusDownloadStatus.write_off " + focusDownloadStatus.write_off);
                    } else if (position == maybeRangeStatus.write_off) {
                        mainTask = remove_index;
                    } else if (position > maybeRangeStatus.write_off) {
                        request.downloadStart = position;
                        if (focusDownloadStatus.download_start > position && (focusDownloadStatus.download_start + rangDownloadTasks[mainTask].mContentLength) == mFileSize) {
                            // 前一个请求的区域已经到了文件尾部，所以我们必须要请求这么长得文件长度。
                            // 只要到主请求的前部就可以了
                            request.rangeEnd = focusDownloadStatus.download_start;
                        }
                        restartDownload(request, remove_index);
                        mainTask = remove_index;
                        Log.e(LOG_TAG, "JAVAN3 position " + position + " mainTask " + mainTask);
                    } else if (position > focusDownloadStatus.write_off) {
                        if (downloadedBlock.containsValue(mFileSize)) {
                            long new_end = -1;
                            Set<Map.Entry<Long, Long>> entrySet = downloadedBlock.entrySet();
                            for (Map.Entry<Long,Long> entry : entrySet) {
                                if (entry.getValue() == mFileSize) {
                                    new_end = entry.getKey();
                                    break;
                                }
                            }

                            request.rangeEnd = new_end;
                        }

                        if (rangDownloadTasks[remove_index].isEnded()) {    // 备用任务已经停止下载了，直接使用备用任务下载
                            restartDownload(request, remove_index);
                            mainTask = remove_index;
                        } else {
                            restartDownload(request, mainTask);
                        }
                        Log.e(LOG_TAG, "JAVAN4 position " + position + " mainTask " + mainTask);
                    } else {
                        if (position < focusDownloadStatus.download_start) {    // 在当前下载的前面，使用另外一个备用下载链接请求该数据
                            request.downloadStart = position;
                            // 判断从位置当前下载快最佳下载终点
                            if (downloadedBlock.containsValue(mFileSize)) {
                                long new_end = -1;
                                Set<Map.Entry<Long, Long>> entrySet = downloadedBlock.entrySet();
                                for (Map.Entry<Long,Long> entry : entrySet) {
                                    if (entry.getValue() == mFileSize) {
                                        new_end = entry.getKey();
                                        break;
                                    }
                                }
                                request.rangeEnd = new_end;
                            } else {
                                request.rangeEnd = focusDownloadStatus.download_start;
                            }

                            restartDownload(request, remove_index);
                            mainTask = remove_index;
                            Log.e(LOG_TAG, "JAVAN4 position " + position + " mainTask " + mainTask);
                        } else {
                            Log.e(LOG_TAG, "No TODO condition");
                        }
                    }
                    return;
                }
            } else {
                // 文件已经下载好了
            }
        }

        sendFalseInfoToClient(request);
        Log.e(LOG_TAG, "final  mainTask " + mainTask);
    }

    private void sendFalseInfoToClient(StreamProxy.BlockHttpRequest request) {
        // 更新Client
        // 有数据已经下载好了创建模拟数据
        StringBuilder httpString = new StringBuilder();
        httpString.append("HTTP/1.1 206 Partial Content").append("\n");
//        httpString.append("Date: Sun, 29 Mar 2015 07:41:25 GMT").append("\n");
        httpString.append("Server: nginx/1.4.4").append("\n");
        httpString.append("Content-Type: video/mp4").append("\n");
        httpString.append("Accept-Ranges: bytes").append("\n");
        httpString.append("Access-Control-Allow-Origin: *").append("\n");
        httpString.append("Access-Control-Max-Age: 2592000").append("\n");
        httpString.append("Content-Disposition: inline; filename=\"5517ac4679dbe8010.mp4\"").append("\n");
        httpString.append("Content-Transfer-Encoding: binary").append("\n");
        httpString.append("ETag: \"ltU-JTZbZ09azl8vsGyEC8YO3DuH\"").append("\n");
        httpString.append("X-Log: mc.g;IO").append("\n");
        httpString.append("X-Reqid: tQ4AADZNZu8a6M8T").append("\n");
        httpString.append("X-Whom: nb263").append("\n");
        httpString.append("X-Qiniu-Zone: 0").append("\n");
        httpString.append("Content-Range: bytes ").append(request.rangeStart)
                .append("-6709786/6709787").append("\n");
        httpString.append("Content-Length: ").append(mFileSize - request.rangeStart).append("\n");
        httpString.append("Age: 1").append("\n");
        httpString.append("X-Via: 1.1 zhj190:8080 (Cdn Cache Server V2.0), 1.1 zhj74:8107 (Cdn Cache Server V2.0), 1.1 fzh194:2 (Cdn Cache Server V2.0)").append("\n");
        httpString.append("Connection: close").append("\n");
        httpString.append("\n");

//        Log.d(LOG_TAG, "result: \n" + httpString.toString());

        byte[] buffer = httpString.toString().getBytes();
        try {
            request.client.getOutputStream().write(buffer, 0, buffer.length);
            request.client.getOutputStream().flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将提供的块进行的拼接，进行连续性处理
     * @param blocks 已经下载好的数据块
     */
    private void connectBlocks(HashMap<Long, Long> blocks) {
        ArrayList<HashMap.Entry<Long, Long>> indexArray =
                new ArrayList<HashMap.Entry<Long, Long>>(blocks.entrySet());

        Collections.sort(indexArray, new Comparator<HashMap.Entry<Long, Long>>() {
            @Override
            public int compare(HashMap.Entry<Long, Long> longLongEntry, HashMap.Entry<Long, Long> t1) {
                int ret = 0;
                if (longLongEntry.getKey() > t1.getKey()) {
                    ret = 1;
                } else if (longLongEntry.getKey() < t1.getKey()) {
                    ret = -1;
                } else {
                    ret = 0;
                }
                return ret;
            }
        });
        ArrayList<HashMap.Entry<Long, Long>> resultArray =
                new ArrayList<HashMap.Entry<Long, Long>>(blocks.size());

        int last_index = indexArray.size();
        HashMap.Entry<Long, Long> lastItem = indexArray.get(0);
        for (int i = 1; i < last_index; i++) {
            if (lastItem.getValue() >= indexArray.get(i).getKey()) {
                // 后一组跟前一组是关联的
                if (lastItem.getValue() >= indexArray.get(i).getValue()) {
                    // 第一个后界限，比第二个的后界限大。说明已经包好了只要使用后界限就好。
                } else {
                    lastItem.setValue(indexArray.get(i).getValue());
                }
            } else {
                resultArray.add(lastItem);
                lastItem = indexArray.get(i);
            }
        }

        resultArray.add(lastItem);

        blocks.clear();

        for (int i = 0; i < resultArray.size(); i++) {
            HashMap.Entry<Long, Long> item = resultArray.get(i);
            blocks.put(item.getKey(), item.getValue());
        }
    }

    /**
     * 找到请求快在目前已经下载块里面得位置
     * @param request
     * @return find final download start position;
     */
    private Long findBlockPosition(StreamProxy.BlockHttpRequest request) {
        Set<Long> downloaded = downloadedBlock.keySet();
        Long nearPosition = null, nearPositionKey = null;   // 最靠近的位置
        Long nearDistance = Long.MAX_VALUE;
        Long finalPosition = request.rangeStart;

        for (Long start : downloaded) {
            if (Math.abs(request.rangeStart - start) < nearDistance) {  // 与快前端的比较
                nearPosition = start;
                nearPositionKey = start;
                nearDistance = Math.abs(request.rangeStart - start);
            }

            if (Math.abs(request.rangeStart - downloadedBlock.get(start)) < nearDistance) { // 与快后端的比较
                nearPosition = downloadedBlock.get(start);
                nearPositionKey = start;
                nearDistance = Math.abs(request.rangeStart - downloadedBlock.get(start));
            }
        }
        if (nearPosition == null) {
            // 没有找到最近点
            return finalPosition;
        }

        if (downloadedBlock.containsKey(nearPosition)) {
            // 说明离头部比较近
            if (request.rangeStart >= nearPosition && request.rangeStart <= downloadedBlock.get(nearPosition)) {
                // 距离最近快内部。只要从快得尾部开始下载即可
                finalPosition = downloadedBlock.get(nearPosition);
            } else {
                // 最近块的外围，直接从请求头部开始即可
            }
        } else if (downloadedBlock.containsValue(nearPosition)) {
            // 说明离尾部较近
            if (request.rangeStart >= nearPositionKey && request.rangeStart <= downloadedBlock.get(nearPositionKey)) {
                // 距离最近快内部。只要从快得尾部开始下载即可
                finalPosition = nearPosition;
            } else {
                // 最近块的外围，直接从请求头部开始即可
            }
        }

        return finalPosition;
    }

    private void restartDownload(StreamProxy.BlockHttpRequest request, int block_to_restart) {
        rangDownloadTasks[block_to_restart].stop();
        mRequest = request;
        mMainStatus.write_off = request.downloadStart;
        mMainStatus.download_start = request.downloadStart;

        if (request.downloadStart == mFileSize) {
            // 请求下载的位置已经是最后的末尾了数据都已经下载好了，
            // 发送假消息即可
            sendFalseInfoToClient(request);
        } else {
            rangDownloadTasks[block_to_restart].start(request);
        }
    }

    /**
     * 区间下载任务控制类
     */
    class  RangDownloadTask {
        private RandomAccessFile randomAccessFile;
        private MyClientConnManager mgr;
        // Range 任务的状态
        private DownloadStatus status = new DownloadStatus();
        // 下载内容的长度
        private long mContentLength;
        private long write_total = 0;
        // 网络数据读入扣
        private InputStream fileData;
        private long lastReadOff = -1;

        /**
         * 区间下载任务
         * @param cachePath 下载任务的缓冲区间
         */
        RangDownloadTask(String cachePath) {
            try {
                randomAccessFile = new RandomAccessFile(cachePath, "rw");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        void doSomeWork(boolean checkSpeed) {
            int readBytes = -1;

            if (fileData == null) { // 没有输入流
                return;
            }

            // 加入下载速度的控制
            if (checkSpeed) {
                if (mMainStatus.read_off >= status.download_start && mMainStatus.read_off <= status.write_off) {
                    // 说明当前读取的数据在该范围内
                    lastReadOff = mMainStatus.read_off;
                    if ((status.write_off - mMainStatus.read_off) > 256 * 1024) {
                        // 不需要加载更多数据了目前
//                        Log.e(LOG_TAG, "slow down read speed!" + " status.download_start " + status.download_start);
                        return;
                    }
                } else if (lastReadOff != -1 && lastReadOff >= status.download_start && (status.write_off - lastReadOff) > 256 * 1024){
//                    Log.e(LOG_TAG, "1 slow down read speed!" + " status.download_start " + status.download_start);
                    return;
                } else {

                }
            }

            try {
                long begin = SystemClock.elapsedRealtime();
                readBytes = fileData.read(buff, 0, buff.length);
                if (readBytes > 0)
                    Log.d(LOG_TAG, "Javan end fileData.read readBytes " + readBytes + " cost time " + (SystemClock.elapsedRealtime() - begin));
                else
                    Log.w(LOG_TAG, "Socket download read nothing.");
            }  catch (IOException e) {
                e.printStackTrace();
                Log.w(LOG_TAG, e);
            }
            if (readBytes < 0) {
                return;
            }

            try {

                randomAccessFile.write(buff, 0, readBytes);
                write_total += readBytes;
                status.write_off  += readBytes;
                if (write_total == mContentLength) {
                    Log.e(LOG_TAG, "write_off Out of file size" + status.write_off);
                    mgr.shutdown();
                    mgr = null;
                    fileData.close();
                    fileData = null;
                }

                // 每次下载后更新偏移值
                if (downloadedBlock.get(status.download_start) == null ||
                        (downloadedBlock.get(status.download_start) != null && downloadedBlock.get(status.download_start)  < status.write_off)) {
                    // 如果是拼接后得长度肯定要比写入的长。如果修改了造成信息不一致
                    downloadedBlock.put(status.download_start, status.write_off);
                } else {

                }

                Log.d(LOG_TAG, "status.download_start "+ status.download_start + " write_off " + status.write_off + " readBytes " + readBytes );

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void start(StreamProxy.BlockHttpRequest request) {
            Log.e(LOG_TAG, "processing --- request.range_start " + request.rangeStart);

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
            try {
                Log.d(LOG_TAG, "starting download");
                realResponse = http.execute(method);
                Log.d(LOG_TAG, "downloaded");
            } catch (ClientProtocolException e) {
                Log.e(LOG_TAG, "Error downloading", e);
                error = true;
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error downloading", e);
                error = true;
            }

            Log.d(LOG_TAG, "downloading...");

            try {
                fileData = realResponse.getEntity().getContent();
            } catch (IOException e) {
                e.printStackTrace();
                error = true;
            }

            StatusLine line = realResponse.getStatusLine();
            HttpResponse response = new BasicHttpResponse(line);
            response.setHeaders(realResponse.getAllHeaders());

            mContentLength = realResponse.getEntity().getContentLength();

            if (request.rangeEnd < 0) {    // 如果默认小于0，修正成实际数值
                request.rangeEnd = request.downloadStart + mContentLength - 1;
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
                            mFileSize = Long.parseLong(strFilelength);
                            Log.e(LOG_TAG, "====== mFileSize: " + mFileSize);
                        } catch (NumberFormatException nfe) {
                            Log.w(LOG_TAG, nfe);
                        }
                    }
                    httpString.append("Content-Range: bytes ").append(request.rangeStart)
                            .append("-6709786/6709787").append("\n");
                } else {
                    httpString.append(h.getName()).append(": ").append(h.getValue()).append("\n");
                }
            }

            httpString.append("\n");
            Log.d(LOG_TAG, "headers done");

            // 还没下载过
            // 创建文件句柄
            try {
                randomAccessFile.setLength(mFileSize);
                randomAccessFile.seek(request.downloadStart);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                error = true;
            } catch (IOException e) {
                e.printStackTrace();
                error = true;
            }

            if (request.client != null) {
                byte[] buffer = httpString.toString().getBytes();
                int readBytes = -1;
//                Log.d(LOG_TAG, "writing to client " + httpString.toString());
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
                status.write_off = request.downloadStart;
                status.read_off = request.downloadStart;
                status.download_start = request.downloadStart;
                write_total = 0;
            }
        }

        public void stop() {
            if (mgr != null) {
                mgr.shutdown();
                mgr = null;
            }
        }

        public boolean isEnded() {
            return write_total == mContentLength;
        }
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

    class MyClientConnManager extends SingleClientConnManager {
        MyClientConnManager(HttpParams params, SchemeRegistry schreg) {
            super(params, schreg);
        }

        @Override
        protected ClientConnectionOperator createConnectionOperator(final SchemeRegistry sr) {
            return new MyClientConnectionOperator(sr);
        }
    }
}
