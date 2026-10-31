package com.kasa.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Concurrency {

    public interface TaskFactory<T> {
        Callable<T> create(int index);
    }

    private Concurrency() {
    }

    /** Releases all tasks at once to maximise contention. A task's unexpected exception fails the test. */
    public static <T> List<T> run(int count, TaskFactory<T> factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> task = factory.create(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw new AssertionError("concurrent task failed", e.getCause());
                }
            }
            return results;
        }
    }
}
