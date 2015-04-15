package tv.danmaku.ijk.media.demo;

/**
 * Created by Javan on 15/4/13.
 */
public abstract class DataProcessor {
    abstract void doSomeWork();

    void start() {};
    void stop() {};

    abstract boolean isEnded();

    // 跳到指定位置
    public abstract void seekTo(StreamProxy.BlockHttpRequest request) throws Exception;
}
