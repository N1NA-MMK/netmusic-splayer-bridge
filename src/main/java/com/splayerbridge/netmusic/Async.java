package com.splayerbridge.netmusic;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台线程池。全部使用守护线程，避免影响服务端退出。
 */
public final class Async {
    private static final ExecutorService API = Executors.newFixedThreadPool(4, factory("SPlayerBridge-Api"));
    private static final ExecutorService DOWNLOAD = Executors.newFixedThreadPool(2, factory("SPlayerBridge-Download"));
    private static final ScheduledExecutorService TICK =
            Executors.newSingleThreadScheduledExecutor(factory("SPlayerBridge-Tick"));

    private Async() {
    }

    /** 用于解锁接口等短请求。 */
    public static ExecutorService api() {
        return API;
    }

    /** 用于音频下载。 */
    public static ExecutorService download() {
        return DOWNLOAD;
    }

    /** 用于健康检查等定时任务。 */
    public static ScheduledExecutorService tick() {
        return TICK;
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY + 2);
            return thread;
        };
    }
}
