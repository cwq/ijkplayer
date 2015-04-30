package tv.danmaku.ijk.media.demo;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import tv.danmaku.ijk.media.demo.StreamProxy.BlockHttpRequest;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 下载任务类
 * Created by Javan on 15/4/13.
 */
public class DownloadTask implements Handler.Callback {
    private static final String LOG_TAG = Class.class.getSimpleName();
    // 执行开始
    static final int MSG_START = 0x01;
    // 执行停止
    static final int MSG_STOP = 0x02;
    // 执行下载
    static final int MSG_DOING_DOWNLOAD = 0x03;
    // 定位到指定块数据
    static final int MSG_SEEK_BLOCK = 0x04;
    // 定位到指定块数据
    static final int MSG_ERROR = 0x05;
    // 发送下载状态数据
    static final int MSG_UPDATE_DOWNLOAD_STATUS = 0x06;
    // 优化改变下载区间
    public static final int MSG_CHANGE_RANGE = 0x07;
    // 线程消息
    public static final int MSG_NOTIFY_INFO = 0x08;

    public static final int STATE_IDLE = -1;

    public static final int STATE_PREPARING = 2;
    /**
     */
    public static final int STATE_BUFFERING = 3;
    /**
     */
    public static final int STATE_READY = 4;

    private static final int DOWNLOADING_INTERVAL_MS = 20;
    private static final int IDLE_INTERVAL_MS = 1000;
    private static final int PROCESSOR_DOWNLOAD_STATUS = 0;
    public static final int STATE_REQUEST_STOP = 5;

    public void sendMessage(Object obj) {
        Message message = handler.obtainMessage(DownloadTask.MSG_NOTIFY_INFO, obj);
        handler.sendMessage(message);
    }

    public interface DownloadTaskCallback {
        void notifyMessage(String mes);
    }

    private final PriorityHandlerThread internalDownThread;
    private final Handler handler;
    private DownloadStatus status;

    DownloadTaskCallback callback;

    int state = STATE_IDLE;
    // 当前正在使用下载的block
    private BlockHttpRequest request;

    private DataProcessor[] processors;

    public DownloadTask(BlockHttpRequest request) {
        internalDownThread = new PriorityHandlerThread(getClass().getSimpleName() + ":Handler");
        internalDownThread.start();
        handler = new Handler(internalDownThread.getLooper(), this);
        status = new DownloadStatus();

        this.request = request;
        String cachePath = getTempCacheFile().getAbsolutePath();
        File cacheFile = new File(cachePath);
        if (cacheFile.exists() && cacheFile.delete()) {
            Log.i(LOG_TAG, "Delete cache file " + cachePath);
        } else {

        }

        processors = new DataProcessor[2];
        processors[PROCESSOR_DOWNLOAD_STATUS] = new DownloadStatusProcessor(request, cachePath, status, this);
        processors[1] = new SendToClientDataProcessor(request, cachePath, status);

    }

    public DownloadTaskCallback getCallback() {
        return callback;
    }

    public void setCallback(DownloadTaskCallback callback) {
        this.callback = callback;
    }

    /**
     * 开启下载
     */
    void start() {
        handler.sendEmptyMessage(MSG_START);
    }

    /**
     * 停止下载
     */
    void stop() {
        handler.sendEmptyMessage(MSG_STOP);
    }

    /**
     * 释放内存
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    void release() {
        while(state != STATE_IDLE) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            internalDownThread.quitSafely();
        }else {
            internalDownThread.quit();
        }
    }

    void seekTo(BlockHttpRequest request) {
        Message message = handler.obtainMessage(MSG_SEEK_BLOCK, request);
        handler.sendMessage(message);
    }

    @Override
    public boolean handleMessage(Message message) {
        boolean handled = false;
        switch (message.what) {
            case MSG_START:
                startInternal();
                handled = true;
                break;
            case MSG_STOP:
                stopInternal();
                handled = true;
                break;
            case MSG_DOING_DOWNLOAD:
                downloadData();
                handled = true;
                break;
            case MSG_SEEK_BLOCK:
                try {
                    seekToInternal(message.obj);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                handled = true;
                break;
            case MSG_NOTIFY_INFO:
            {
                if (callback != null) {
                    callback.notifyMessage((String) message.obj);
                }
            }
            break;
            default:
                break;
        }
        return handled;
    }

    private void stopInternal() {
        Log.e(LOG_TAG, "Javan stopInternal");

        if (state == STATE_IDLE) {
            return;
        }

        for (int i = 0; i < processors.length; i++) {
            processors[i].stop();
        }

        setState(STATE_IDLE);
    }

    private void seekToInternal(Object data) throws Exception {
        BlockHttpRequest request = null;
        if (data instanceof BlockHttpRequest) {
            request = (BlockHttpRequest) data;
        }

        if (request == null) return;

        for (int i = 0; i < processors.length; i++) {
            processors[i].seekTo(request);
        }

        handler.sendEmptyMessage(MSG_DOING_DOWNLOAD);
    }

    private void startInternal() {
        for (int i = 0; i < processors.length; i++) {
            processors[i].start();
        }

        setState(STATE_READY);

        handler.sendEmptyMessage(MSG_DOING_DOWNLOAD);
    }

    private void downloadData() {
        long operationStartTimeMs = SystemClock.elapsedRealtime();

        for (int i = 0; i < processors.length; i++) {
            processors[i].doSomeWork();
        }

        handler.removeMessages(MSG_DOING_DOWNLOAD);

        if ((/*playWhenReady &&*/ state == STATE_READY) || state == STATE_BUFFERING) {
            scheduleNextOperation(MSG_DOING_DOWNLOAD, operationStartTimeMs, DOWNLOADING_INTERVAL_MS);
        } else if (processors.length != 0) {
            scheduleNextOperation(MSG_DOING_DOWNLOAD, operationStartTimeMs, IDLE_INTERVAL_MS);
        } else {
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

    private void setState(int state) {
        if (this.state != state) {
            this.state = state;
        }
    }

    private File getTempCacheFile() {
        return new File("/mnt/sdcard", md5hash(request.url));
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
}
