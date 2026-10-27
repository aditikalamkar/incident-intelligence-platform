package com.opspilot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Runs tasks on separate threads released at the same instant, to provoke races. */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<Future<T>> run(int threads, IntFunction<Callable<T>> task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<T> callable = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return callable.call();
                }));
            }
            start.countDown();
        }
        return futures;
    }
}
