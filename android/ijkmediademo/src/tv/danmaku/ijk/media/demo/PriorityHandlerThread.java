package tv.danmaku.ijk.media.demo;

import android.os.HandlerThread;
import android.os.Process;

/**
 * A {@link HandlerThread} with a specified process priority.
 */
public class PriorityHandlerThread extends HandlerThread {

    private final int priority;

    /**
     * @param name The name of the thread.
     * @param priority The priority level. See {@link Process#setThreadPriority(int)} for details.
     */
    public PriorityHandlerThread(String name, int priority) {
        super(name);
        this.priority = priority;
    }

    public PriorityHandlerThread(String name) {
        // Note: The documentation for Process.THREAD_PRIORITY_AUDIO that states "Applications can
        // not normally change to this priority" is incorrect.
        this(name, Process.THREAD_PRIORITY_AUDIO);
    }

    @Override
    public void run() {
        Process.setThreadPriority(priority);
        super.run();
    }

}
