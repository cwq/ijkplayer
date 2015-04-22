package tv.danmaku.ijk.media.demo;

import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Created by Javan on 15/4/13.
 */
public class SendToClientDataProcessor extends DataProcessor {
    private static final String LOG_TAG = SendToClientDataProcessor.class.getSimpleName();

    private StreamProxy.BlockHttpRequest mRequest;
    private DownloadStatus mDownloadStatus;
    private RandomAccessFile randomAccessFile;

    public SendToClientDataProcessor(StreamProxy.BlockHttpRequest request, String cachePath, DownloadStatus status) {
        mRequest = request;
        mDownloadStatus = status;
        try {
            randomAccessFile = new RandomAccessFile(cachePath, "rw");
            randomAccessFile.seek(mRequest.rangeStart);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mDownloadStatus.read_off = mRequest.rangeStart;
    }

    // Start streaming content.
    byte[] buff = new byte[64*1024];

    @Override
    void doSomeWork() {
        long read_len = buff.length;
//        Log.d(LOG_TAG, "mDownloadStatus.write_off " + mDownloadStatus.write_off + " mDownloadStatus.read_off " + mDownloadStatus.read_off);
        long unread = mDownloadStatus.write_off - mDownloadStatus.read_off;

        if (unread > 0 && unread < read_len) {
            read_len = unread;
        }

        if (unread <= 0) {
            // 没有数据可以读
            return;
        }

        try {
            int readBytes = -1;
            OutputStream outputStream = null;
            try {
                if (mRequest.client != null && !mRequest.client.isClosed())
                    outputStream = mRequest.client.getOutputStream();
                else {
                    // nothing to do.
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            if (outputStream != null) {
                readBytes = randomAccessFile.read(buff, 0, (int) read_len);
                if (readBytes >= 0) {
                    outputStream.write(buff, 0, readBytes);
                    mDownloadStatus.read_off += readBytes;

                    Log.d(LOG_TAG, "read_off " + mDownloadStatus.read_off + " old read_off " + (mDownloadStatus.read_off - readBytes) + " readBytes " + readBytes);
                }

                outputStream.flush();
            }

        } catch (IOException e) {
            Log.e(LOG_TAG, " Thread id " + Thread.currentThread().getId() + " error: " + e);
        }

    }

    @Override
    boolean isEnded() {
        return false;
    }

    @Override
    public void seekTo(StreamProxy.BlockHttpRequest request) {
        try {
            mRequest.client.close();
            mRequest.client = null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        mRequest = request;
        mDownloadStatus.read_off = request.rangeStart;


        try {
            randomAccessFile.seek(request.rangeStart);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
