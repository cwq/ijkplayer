package tv.danmaku.ijk.media.demo;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.util.*;

public class DownloadStatusProcessor extends DataProcessor implements  Handler.Callback {

    private static final String LOG_TAG = DownloadStatusProcessor.class.getSimpleName();
    private static final int WAIT_CACHE_SIZE = 128*1024;     //128kb 以内的小偏移
    // 下载的缓冲目录
    private final String mCachePath;
    private final DownloadTask downloadTask;

    private StreamProxy.BlockHttpRequest mRequest;
    private DownloadStatus mMainStatus;

    // 标示是否已经下载完成了。
    private boolean isAllFinished = false;
    private int mFileSize = 0;
    // 存储已经保存好的块数据
    // [a,b)  数据范围 a <= 索引 < b.
    private HashMap<Integer, Integer> downloadedBlock = new HashMap<Integer, Integer>(10);

    // 位于前部的下载任务
    private static final int TASK_FRONT = 0;
    // 位于后部的下载任务
    private static final int TASK_BEHIND = 1;

    private int mainTask = -1;

    // 现在线程的最大数
    private static final int MAX_RANG_DOWNLOAD_THREAD = 2;
    // 维护下载线程数组
    private RangDownloadThread[] rangDownloadThreads = new RangDownloadThread[MAX_RANG_DOWNLOAD_THREAD];
    private Handler eventHandler;

    public DownloadStatusProcessor(StreamProxy.BlockHttpRequest request, String cachePath, DownloadStatus status, DownloadTask downloadTask) {
        mRequest = request;
        mMainStatus = status;
        mCachePath = cachePath;
        this.downloadTask = downloadTask;
    }

    @Override
    void doSomeWork() {
        if (isAllFinished) {
            // 已经都下载好了
            // 写入的最终值改成文件大小
            mMainStatus.write_off = mFileSize;
            return;
        }

        if (!isAllFinished && !downloadedBlock.isEmpty() && downloadedBlock.size() == 1) {  // 判断是否已经下载完成了
            if (downloadedBlock.get(0) != null) {
                if (mFileSize == downloadedBlock.get(0)) {
                    // file 完整下载了
                    isAllFinished = true;
                } else {

                }
            }
        }

        if (!isAllFinished) {
            if (rangDownloadThreads[mainTask].isEnded() || mMainStatus.read_off > mMainStatus.write_off) {
                // 停止下载了，找到包含目前write off 的block
                Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                long request_write_off = mMainStatus.read_off;  // 请求的就是已经读到的位置
                for (Map.Entry<Integer,Integer> entry : entrySet) {
                    if (entry.getKey() <= request_write_off && entry.getValue() >= request_write_off) {
                        mMainStatus.write_off = entry.getValue();
                        break;
                    }
                }
            } else {
            }
        } else {
            // 全部下载了直接将主状态的写入偏移等于文件长度
            mMainStatus.write_off = mFileSize;
        }
    }

    // 启动下载开启需要的
    @Override
    void start() {
        eventHandler = new Handler(this);

        for (int i = 0; i < rangDownloadThreads.length; i++) {
            rangDownloadThreads[i] = new RangDownloadThread("RangeThread"+i, mCachePath, eventHandler);
            rangDownloadThreads[i].start();
        }

        mainTask = TASK_FRONT;
        // 开启主线程任务
        rangDownloadThreads[TASK_FRONT].postRequest(mRequest);
    }

    @Override
    boolean isEnded() {
        return false;
    }

    @Override
    public void seekTo(StreamProxy.BlockHttpRequest request) throws Exception {
        Log.d(LOG_TAG, "Begin to seek to request " + request.rangeStart + " mainTask " + mainTask);
        int next_thread_id = mainTask == TASK_FRONT ? TASK_BEHIND : TASK_FRONT;
        int position = 0;

        if (!isAllFinished) {   // 未全部下载完成的时候需要将当前下载的参与计算，如果已经全部下载了。说明Block只有1个0 ~ 文件长度。
            // 找到最佳起始下载文件的位置。避免重复下载
            position = findBlockPosition(request);

            if (mFileSize != 0 && position == mFileSize) {
                // 说明请求的快已经下载好了
                // 直接发送假消息返回
                sendFalseInfoToClient(request);
                return;
            }

        } else {
            // 全部完成了，直接发送假消息返回
            sendFalseInfoToClient(request);
            return;
        }

        int restart_new = isNeedRestartNew(request, position);

        connectBlocks(downloadedBlock);

        if (restart_new == -1) {
            // 需要开启一个新的下载
            Pair<Integer, Integer> range = rangDownloadThreads[mainTask].getDownloadRange();

            if (position <= range.first) {  // 比当前下载的range还小
                if (range.second == mFileSize)
                    request.rangeEnd = range.first;
            } else if(position <= range.second) {
                // 请求的位置大于主请求的的起始，控制前置任务的下载区间，避免重复下载
//                rangDownloadThreads[mainTask].setDownloadRange(range.first, position);
            } else {
                Log.d(LOG_TAG,"Can not happen");
            }

            if (rangDownloadThreads[mainTask].isEnded()) {  // 如果当前的主线程任务已经没有事情可以做了，就直接使用主线程下载
                next_thread_id = mainTask;
            }

            if (request.rangeEnd < 0 && mFileSize!=0 && downloadedBlock.containsValue(mFileSize)) { // 判断是否已经有文件末尾的片段
                long new_end = -1;
                Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                for (Map.Entry<Integer,Integer> entry : entrySet) {
                    if (entry.getValue() == mFileSize) {
                        new_end = entry.getKey();
                        break;
                    }
                }
                request.rangeEnd = new_end;
            }

            request.downloadStart = position;
            rangDownloadThreads[mainTask].setSpeedDown();
//            rangDownloadThreads[next_thread_id].postRequest(request);
            rangDownloadThreads[next_thread_id].synchronizePostRequest(request);
            mainTask = next_thread_id;
        } else {
            // 不需要重启直接利用现有的下载服务
            if (restart_new != mainTask) {
                rangDownloadThreads[mainTask].setSpeedDown();
                rangDownloadThreads[restart_new].resetSpeed();
            }
            mainTask = restart_new;
            sendFalseInfoToClient(request);
        }

        mRequest = request;
/*
        // 将当前已经下载的部分当做一个block参与计算
        Integer position = null;
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
        } else if (position > focusDownloadStatus.write_off && position - focusDownloadStatus.write_off < WAIT_CACHE_SIZE) {
            do {
                rangDownloadTasks[mainTask].doSomeWork(false);
            } while (focusDownloadStatus.write_off < position && !rangDownloadTasks[mainTask].isEnded());

            if (rangDownloadTasks[mainTask].isEnded()) {
                connectBlocks(downloadedBlock);
                long final_position = -1;
                Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                for (Map.Entry<Integer,Integer> entry : entrySet) {
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
                    StreamProxy.BlockHttpRequest blockRequest = mRequest.copy();
                    // 不需要传递给客户端，只是缓冲数据
                    blockRequest.client = null;
                    blockRequest.rangeStart = position;
                    blockRequest.downloadStart = blockRequest.rangeStart;

                    if (downloadedBlock.containsValue(mFileSize)) {
                        // 找到最靠近尾部的开始
                        long new_end = -1;
                        for (Map.Entry<Integer,Integer> entry : entrySet) {
                            if (entry.getValue() == mFileSize) {
                                new_end = entry.getKey();
                                break;
                            }
                        }
                        request.rangeEnd = new_end;
                    } else {
                    }

                    rangDownloadTasks[mainTask].start(blockRequest);
                }
            } else {
                // 正在结束
            }
        } else if (position >= focusDownloadStatus.download_start && position <= focusDownloadStatus.write_off) {
            // 位置刚好在当前下载快范围内
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
                    Log.w(LOG_TAG, "JAVAN1 position " + position + " mainTask " + mainTask);
                } else if (position >= maybeRangeStatus.write_off && position - maybeRangeStatus.write_off <= WAIT_CACHE_SIZE) {  // 比后备下载
                    if (position > request.rangeStart && (position - request.rangeStart) > WAIT_CACHE_SIZE ) {
                        Log.w(LOG_TAG, "JAVAN position "+ position + " far away from request " + request.rangeStart);
                        mainTask = remove_index;
                    } else {
                        do {
                            rangDownloadTasks[remove_index].doSomeWork(false);
                        } while (maybeRangeStatus.write_off < position && !rangDownloadTasks[remove_index].isEnded());

                        if (rangDownloadTasks[remove_index].isEnded()) {
                            connectBlocks(downloadedBlock);

                            Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                            for (Map.Entry<Integer,Integer> entry : entrySet) {
                                if (entry.getValue() == mFileSize) {
                                    break;
                                }
                            }
                            rangDownloadTasks[remove_index].start(null);
                        } else {
                            mainTask = remove_index;
                        }
                    }
                    Log.w(LOG_TAG, "JAVAN2 position " + position + " mainTask " + mainTask);
                } else {
                    if (position == focusDownloadStatus.write_off) {
                        // 刚好等于焦点的当前标志位不需要创建新的链接
                        Log.w(LOG_TAG, "JAVAN2 position " + position + " focusDownloadStatus.write_off " + focusDownloadStatus.write_off);
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
                        Log.w(LOG_TAG, "JAVAN3 position " + position + " mainTask " + mainTask);
                    } else if (position > focusDownloadStatus.write_off) {
                        if (downloadedBlock.containsValue(mFileSize)) {
                            long new_end = -1;
                            Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                            for (Map.Entry<Integer,Integer> entry : entrySet) {
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
                            if (rangDownloadTasks[mainTask].status.write_off != position) { // 如果不刚好等于main写入节点的时候
                                restartDownload(request, mainTask);
                            }
                        }
                        Log.w(LOG_TAG, "JAVAN4 position " + position + " mainTask " + mainTask);
                    } else {
                        if (position < focusDownloadStatus.download_start) {    // 在当前下载的前面，使用另外一个备用下载链接请求该数据
                            request.downloadStart = position;
                            // 判断从位置当前下载快最佳下载终点
                            if (downloadedBlock.containsValue(mFileSize)) {
                                long new_end = -1;
                                Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();
                                for (Map.Entry<Integer,Integer> entry : entrySet) {
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
                            Log.w(LOG_TAG, "JAVAN4 position " + position + " mainTask " + mainTask);
                        } else {
                            Log.w(LOG_TAG, "No TODO condition");
                        }
                    }
                    return;
                }
            } else {
                // 文件已经下载好了
            }
        }

        sendFalseInfoToClient(request);
        Log.d(LOG_TAG, "final  mainTask " + mainTask);*/
    }

    private int isNeedRestartNew(StreamProxy.BlockHttpRequest request, int position) {
        // 将当前已经下载的部分当做一个block参与计算
        int ret = -1;

        RangDownloadThread thread;
        for (int i = 0; i < rangDownloadThreads.length; i++) {
            thread = rangDownloadThreads[i];

            if (thread.isRequestInRange(request, position)) {
                ret = i;
                break;
            }
        }

        return ret;
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
                .append("-").append(mFileSize - 1).append('/').append(mFileSize).append("\n");
        httpString.append("Content-Length: ").append(mFileSize - request.rangeStart).append("\n");
        httpString.append("Age: 1").append("\n");
        httpString.append("X-Via: 1.1 zhj190:8080 (Cdn Cache Server V2.0), 1.1 zhj74:8107 (Cdn Cache Server V2.0), 1.1 fzh194:2 (Cdn Cache Server V2.0)").append("\n");
        httpString.append("Connection: close").append("\n");
        httpString.append("\n");

//        Log.d(LOG_TAG, "JAVAN false result: \n" + httpString.toString());

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
    private void connectBlocks(HashMap<Integer, Integer> blocks) {
        ArrayList<HashMap.Entry<Integer, Integer>> indexArray =
                new ArrayList<HashMap.Entry<Integer, Integer>>(blocks.entrySet());

        Collections.sort(indexArray, new Comparator<HashMap.Entry<Integer, Integer>>() {
            @Override
            public int compare(HashMap.Entry<Integer, Integer> longIntegerEntry, HashMap.Entry<Integer, Integer> t1) {
                int ret = 0;
                if (longIntegerEntry.getKey() > t1.getKey()) {
                    ret = 1;
                } else if (longIntegerEntry.getKey() < t1.getKey()) {
                    ret = -1;
                } else {
                    ret = 0;
                }
                return ret;
            }
        });
        ArrayList<HashMap.Entry<Integer, Integer>> resultArray =
                new ArrayList<HashMap.Entry<Integer, Integer>>(blocks.size());

        int last_index = indexArray.size();
        HashMap.Entry<Integer, Integer> lastItem = indexArray.get(0);
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
            HashMap.Entry<Integer, Integer> item = resultArray.get(i);
            blocks.put(item.getKey(), item.getValue());
        }
    }

    /**
     * 找到请求快在目前已经下载块里面得位置
     * @param request
     * @return find final download start position;
     */
    private Integer findBlockPosition(StreamProxy.BlockHttpRequest request) {
        Set<Integer> downloaded = downloadedBlock.keySet();
        Integer nearPosition = null, nearPositionKey = null;   // 最靠近的位置
        Integer nearDistance = Integer.MAX_VALUE;
        Integer finalPosition = (int)request.rangeStart;

        for (Integer start : downloaded) {
            if (Math.abs(request.rangeStart - start) < nearDistance) {  // 与快前端的比较
                nearPosition = start;
                nearPositionKey = start;
                nearDistance = Math.abs((int)request.rangeStart - start);
            }

            if (Math.abs(request.rangeStart - downloadedBlock.get(start)) < nearDistance) { // 与快后端的比较
                nearPosition = downloadedBlock.get(start);
                nearPositionKey = start;
                nearDistance = Math.abs((int)request.rangeStart - downloadedBlock.get(start));
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
//        rangDownloadTasks[block_to_restart].stop();
//        mRequest = request;
//        mMainStatus.write_off = request.downloadStart;
//        mMainStatus.download_start = request.downloadStart;
//
//        if (request.downloadStart == mFileSize) {
//            // 请求下载的位置已经是最后的末尾了数据都已经下载好了，
//            // 发送假消息即可
//            sendFalseInfoToClient(request);
//        } else {
//            rangDownloadTasks[block_to_restart].start(request);
//        }
    }

    /**
     * 更新状态
     * @param thread 需要更新的线程
     * @param downloadStart 下载的起始位置
     * @param download_write_off 已经下载到位置的后一位 [downloadStart, download_write_off)
     */
    private void updateDownloadStatus(RangDownloadThread thread, int downloadStart, int download_write_off) {
//        Log.d(LOG_TAG, "updateDownloadStatus downloadStart " + downloadStart + " download_write_off " + download_write_off);
        // TODO 校验对应
        // 的start位置是否已经有数值了，有则已最大得为准
        // 每次下载后更新偏移值
        if (downloadedBlock.get(downloadStart) == null ||
                (downloadedBlock.get(downloadStart) != null && downloadedBlock.get(downloadStart)  < download_write_off)) {
            // 如果是拼接后得长度肯定要比写入的长。如果修改了造成信息不一致
            downloadedBlock.put(downloadStart, download_write_off);
        } else {

        }

        // TODO 根据拼接后得下载块，修正各个下载任务的最终区间
        connectBlocks(downloadedBlock);
        // 判断是否视频文件数据已经拼接成一个文件了
        if (!isAllFinished && !downloadedBlock.isEmpty() && downloadedBlock.size() == 1) {  // 判断是否已经下载完成了
            if (downloadedBlock.get(0) != null) {
                if (mFileSize == downloadedBlock.get(0)) {
                    // file 完整下载了
                    isAllFinished = true;
                } else {

                }
            }
        }

        if (downloadedBlock.get(downloadStart) == null) {
            // 说明拼接的操作将数据块组合起来了。说明有些线程可能要做重复工作。
            Pair<Integer, Integer> rang = rangDownloadThreads[mainTask].getDownloadRange();
            // 判断其他线程下载区间是否已经跟主下载重合
            for (int i = 0; i < rangDownloadThreads.length; i++) {
                if (rangDownloadThreads[i] != rangDownloadThreads[mainTask]) {
                    Pair<Integer, Integer> rang_other = rangDownloadThreads[i].getDownloadRange();
                    if (rang_other.second <= rang.second) {
                        int position = rangDownloadThreads[i].getCurrentPosition();
                        if (position >= rang.first) {
                            // 位置已经落在下载快内了。
                            rangDownloadThreads[i].stopDownload();
                        } else {
//                            Log.d(LOG_TAG, "Has something not been downloaded");
                        }
                    }
                }
            }
        }

        // 更新主状态对象
        if (thread == rangDownloadThreads[mainTask]) {
            // 如果更新的线程对象是主状态对象
            if (downloadedBlock.get(downloadStart) != null) {
                mMainStatus.write_off = downloadedBlock.get(downloadStart);
            } else  {
                mMainStatus.write_off = rangDownloadThreads[mainTask].getCurrentPosition();
//                Log.e(LOG_TAG, "after connect block is missing. mMainStatus.write_off " + mMainStatus.write_off);
            }
        }

        if (mFileSize == 0) {
            mFileSize = thread.getFileSize();
        } else {

        }

        // Check 是否所有任务已经停止，并有缺失的快
        boolean allEnded = true;
        for (int i = 0; i < rangDownloadThreads.length; i++) {
            allEnded &= rangDownloadThreads[i].isEnded();
        }

        if (allEnded && !isAllFinished) {
            connectBlocks(downloadedBlock);

            int range_start = 0, range_end = 0;

            Set<Map.Entry<Integer, Integer>> entrySet = downloadedBlock.entrySet();

            for (Map.Entry<Integer,Integer> entry : entrySet) {
                if (entry.getKey() > range_start) {
                    range_end = entry.getKey();
                    break;
                } else if (entry.getValue() >= range_start) {
                    range_start = entry.getValue();
                }
            }
            if (range_end == 0 && !downloadedBlock.containsValue(mFileSize)) {
                range_end = mFileSize;
            }

            if (range_end > range_start) {
                StreamProxy.BlockHttpRequest request = mRequest.copy();
                // 不需要传递给客户端，只是缓冲数据
                request.client = null;
                request.rangeStart = range_start;
                request.downloadStart = request.rangeStart;
                request.rangeEnd = range_end;

                rangDownloadThreads[0].postRequest(request);
            }

            Log.e(LOG_TAG, "All thread has finished. But some block was not downloaded");
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        boolean res = false;
        switch (message.what) {
            case DownloadTask.MSG_UPDATE_DOWNLOAD_STATUS: {
                updateDownloadStatus((RangDownloadThread) message.obj, message.arg1, message.arg2);
                res = true;
            }
            break;
            case DownloadTask.MSG_NOTIFY_INFO:
            {
                downloadTask.sendMessage(message.obj);
                res = true;
            }
            break;
            default:
                break;
        }
        return res;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    void stop() {
        super.stop();

        for (int i = 0; i < rangDownloadThreads.length; i++) {
            rangDownloadThreads[i].clearMessages();
            rangDownloadThreads[i].stopDownload();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                rangDownloadThreads[i].quitSafely();
            }else {
                rangDownloadThreads[i].quit();
            }
        }
    }
}
