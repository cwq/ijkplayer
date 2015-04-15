package tv.danmaku.ijk.media.demo;

/**
 * Created by Javan on 15/4/15.
 */
public class DownloadStatus {
    long download_start;
    long write_off; // 写偏移
    long read_off;// 读偏移

    void copyDownloadProgress(DownloadStatus status) {
        write_off = status.write_off;
    }
}
