/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.utils.cache;

import static java.time.Instant.now;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.awssdk.utils.FunctionalUtils.invokeSafely;

import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validate the functionality of {@link CachedSupplier}.
 */
public class CachedSupplierTest {
    /**
     * An executor for performing "get" on the cached supplier asynchronously. This, along with the {@link WaitingSupplier} allows
     * near-manual scheduling of threads so that we can test that the cache is only calling the underlying supplier when we want
     * it to.
     */
    private ExecutorService executorService;

    /**
     * All executions added to the {@link #executorService} since the beginning of an individual test method.
     */
    private List<Future<?>> allExecutions;

    /**
     * Create an executor service for async testing.
     */
    @BeforeEach
    public void setup() {
        executorService = Executors.newFixedThreadPool(50);
        allExecutions = new ArrayList<>();
    }

    /**
     * Shut down the executor service when we're done.
     */
    @AfterEach
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * An implementation of {@link Supplier} that allows us to (more or less) manually schedule threads so that we can make sure
     * the CachedSupplier is only calling the underlying supplier when we expect it to.
     */
    private static class WaitingSupplier implements Supplier<RefreshResult<String>>, Closeable {
        /**
         * A semaphore that is counted up each time a "get" is started. This is useful during testing for waiting for a certain
         * number of "gets" to start.
         */
        private final Semaphore startedGetPermits = new Semaphore(0);

        /**
         * A semaphore that is counted down each time a "get" is started. This is useful during testing for blocking the threads
         * performing the "get" until it is time for them to complete.
         */
        private final Semaphore permits = new Semaphore(0);

        /**
         * A semaphore that is counted up each time a "get" is finished. This is useful during testing for waiting for a certain
         * number of "gets" to finish.
         */
        private final Semaphore finishedGetPermits = new Semaphore(0);

        private final Instant staleTime;
        private final Instant prefetchTime;

        private WaitingSupplier(Instant staleTime, Instant prefetchTime) {
            this.staleTime = staleTime;
            this.prefetchTime = prefetchTime;
        }

        @Override
        public RefreshResult<String> get() {
            startedGetPermits.release(1);

            try {
                permits.acquire(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
                fail();
            }

            finishedGetPermits.release(1);
            return RefreshResult.builder("value")
                                .staleTime(staleTime)
                                .prefetchTime(prefetchTime)
                                .build();
        }

        /**
         * Wait for a certain number of "gets" to have started. This will time out and fail the test after a certain amount of
         * time if the "gets" never actually start.
         */
        public void waitForGetsToHaveStarted(int numExpectedGets) {
            assertTrue(invokeSafely(() -> startedGetPermits.tryAcquire(numExpectedGets, 10, TimeUnit.SECONDS)));
        }

        /**
         * Wait for a certain number of "gets" to have finished. This will time out and fail the test after a certain amount of
         * time if the "gets" never finish.
         */
        public void waitForGetsToHaveFinished(int numExpectedGets) {
            assertTrue(invokeSafely(() -> finishedGetPermits.tryAcquire(numExpectedGets, 10, TimeUnit.SECONDS)));
        }

        /**
         * Release all threads blocked in this supplier.
         */
        @Override
        public void close() {
            permits.release(50);
        }
    }

    @Test
    public void allCallsBeforeInitializationBlock() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), future())) {
            CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier).build();

            // Perform two "gets".
            performAsyncGets(cachedSupplier, 2);

            // Make sure both "gets" are started.
            waitingSupplier.waitForGetsToHaveStarted(2);
        }
    }

    @Test
    public void staleValueBlocksAllCalls() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(past(), future())) {
            CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier).build();

            // Perform one successful "get".
            waitingSupplier.permits.release(1);
            waitFor(performAsyncGet(cachedSupplier));

            // Perform two "get"s that will attempt to refresh the value, and wait for them to get stuck.
            performAsyncGets(cachedSupplier, 2);
            waitingSupplier.waitForGetsToHaveStarted(3);

            // Release any "gets" that blocked and wait for them to finish.
            waitingSupplier.permits.release(50);
            waitForAsyncGetsToFinish();

            // Make extra sure all 3 "gets" actually happened.
            waitingSupplier.waitForGetsToHaveFinished(3);
        }
    }

    @Test
    public void basicCachingWorks() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), future())) {
            CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier).build();

            // Perform 5 "gets".
            waitingSupplier.permits.release(5);
            waitFor(performAsyncGets(cachedSupplier, 5));

            // Make extra sure only 1 "get" actually happened.
            waitingSupplier.waitForGetsToHaveFinished(1);
        }
    }

    @Test
    public void oneCallerBlocksPrefetchStrategyWorks() throws InterruptedException {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), past())) {
            CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                  .prefetchStrategy(new OneCallerBlocks())
                                                                  .prefetchJitterEnabled(false)
                                                                  .build();

            // Perform one successful "get" to prime the cache.
            waitingSupplier.permits.release(1);
            waitFor(performAsyncGet(cachedSupplier));

            // Perform one "get" that will attempt to refresh the value, and wait for that one to get stuck.
            performAsyncGet(cachedSupplier);
            waitingSupplier.waitForGetsToHaveStarted(2);

            // Perform a successful "get" because one is already blocked to refresh.
            waitFor(performAsyncGet(cachedSupplier));

            // Release any "gets" that blocked and wait for them to finish.
            waitingSupplier.permits.release(50);
            waitForAsyncGetsToFinish();

            // Make extra sure only 2 "gets" actually happened.
            waitingSupplier.waitForGetsToHaveFinished(2);
        }
    }

    @Test
    public void nonBlockingPrefetchStrategyWorks() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), past());
             CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                   .prefetchStrategy(new NonBlocking("test-%s"))
                                                                   .prefetchJitterEnabled(false)
                                                                   .build()) {
            // Perform one successful "get" to prime the cache.
            waitingSupplier.permits.release(1);
            waitFor(performAsyncGet(cachedSupplier));

            // Perform one successful "get" to kick off the async refresh.
            waitFor(performAsyncGet(cachedSupplier));

            // Wait for the async "get" in the background to start (if it hasn't already).
            waitingSupplier.waitForGetsToHaveStarted(2);

            // Make sure only one "get" has actually happened (the async get is currently waiting to be released).
            waitingSupplier.waitForGetsToHaveFinished(1);
        }
    }

    @Test
    public void nonBlockingPrefetchStrategyRefreshesInBackground() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(now().plusSeconds(62), now().plusSeconds(1));
             CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                   .prefetchStrategy(new NonBlocking("test-%s"))
                                                                   .prefetchJitterEnabled(false)
                                                                   .build()) {
            waitingSupplier.permits.release(2);
            cachedSupplier.get();

            // Ensure two "get"s happens even though we only made one call to the cached supplier.
            waitingSupplier.waitForGetsToHaveStarted(2);

            assertThat(cachedSupplier.get()).isNotNull();
        }
    }

    @Test
    public void nonBlockingPrefetchStrategyHasOneMinuteMinimumByDefault() {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(now(), now());
             CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                   .prefetchStrategy(new NonBlocking("test-%s"))
                                                                   .build()) {
            waitingSupplier.permits.release(2);
            cachedSupplier.get();

            // Ensure two "get"s happens even though we only made one call to the cached supplier.
            assertThat(invokeSafely(() -> waitingSupplier.startedGetPermits.tryAcquire(2, 2, TimeUnit.SECONDS))).isFalse();
        }
    }

    @Test
    public void nonBlockingPrefetchStrategyBackgroundRefreshesHitCache() throws InterruptedException {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), future());
             CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                   .prefetchStrategy(new NonBlocking("test-%s"))
                                                                   .build()) {
            waitingSupplier.permits.release(5);
            cachedSupplier.get();

            Thread.sleep(1_000);

            assertThat(waitingSupplier.permits.availablePermits()).isEqualTo(4); // Only 1 call to supplier
        }
    }

    @Test
    public void nonBlockingPrefetchStrategyDoesNotRefreshUntilItIsCalled() throws InterruptedException {
        try (WaitingSupplier waitingSupplier = new WaitingSupplier(future(), past());
             CachedSupplier<String> cachedSupplier = CachedSupplier.builder(waitingSupplier)
                                                                   .prefetchStrategy(new NonBlocking("test-%s"))
                                                                   .build()) {
            waitingSupplier.startedGetPermits.release();

            Thread.sleep(1_000);

            assertThat(waitingSupplier.startedGetPermits.availablePermits()).isEqualTo(1);
        }
    }

    @Test
    public void threadsAreSharedBetweenNonBlockingInstances() throws InterruptedException {
        int maxActive = runAndCountThreads(() -> {
            List<CachedSupplier<?>> css = new ArrayList<>();
            for (int i = 0; i < 99; i++) {
                CachedSupplier<?> supplier =
                    CachedSupplier.builder(() -> RefreshResult.builder("foo")
                                                              .prefetchTime(now().plusMillis(10))
                                                              .staleTime(future())
                                                              .build())
                                  .prefetchStrategy(new NonBlocking("test"))
                                  .prefetchJitterEnabled(false)
                                  .build();
                supplier.get();
                css.add(supplier);
            }
            return css;
        });

        assertThat(maxActive).isBetween(1, 99);
    }

    @Test
    public void activeThreadsHaveMaxCount() throws InterruptedException {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            int maxActive = runAndCountThreads(() -> {
                List<CachedSupplier<?>> css = new ArrayList<>();

                // Create 1000 concurrent non-blocking instances
                for (int i = 0; i < 1000; i++) {
                    CachedSupplier<String> supplier =
                        CachedSupplier.builder(() -> {
                                          invokeSafely(() -> Thread.sleep(100));
                                          return RefreshResult.builder("foo")
                                                              .prefetchTime(now().plusMillis(10))
                                                              .staleTime(now().plusSeconds(60))
                                                              .build();
                                      }).prefetchStrategy(new NonBlocking("test"))
                                      .prefetchJitterEnabled(false)
                                      .build();
                    executor.submit(supplier::get);
                    css.add(supplier);
                }

                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                return css;
            });

            assertThat(maxActive).isBetween(2, 150);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Run the provided supplier, measure the non-blocking executor thread count, and return the result. If the result is 0,
     * try again. This makes our stochastic tests ~100% reliable instead of ~99%.
     */
    private int runAndCountThreads(ThrowingSupplier suppliersConstructor) throws InterruptedException {
        for (int attempt = 0; attempt < 10; attempt++) {
            Collection<CachedSupplier<?>> suppliers = emptyList();
            try {
                suppliers = suppliersConstructor.get();

                int maxActive = 0;
                for (int j = 0; j < 1000; j++) {
                    maxActive = Math.max(maxActive, NonBlocking.executor().getActiveCount());
                    Thread.sleep(1);
                }

                if (maxActive != 0) {
                    return maxActive;
                }
            } finally {
                suppliers.forEach(CachedSupplier::close);
            }
        }

        throw new AssertionError("Thread count never exceeded 0.");
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        Collection<CachedSupplier<?>> get() throws InterruptedException;
    }

    /**
     * Asynchronously perform a "get" on the provided supplier, returning the future that will be completed when the "get"
     * finishes.
     */
    private Future<?> performAsyncGet(CachedSupplier<?> supplier) {
        return executorService.submit(supplier::get);
    }

    /**
     * Asynchronously perform multiple "gets" on the provided supplier, returning the collection of futures to be completed when
     * the "get" finishes.
     */
    private List<Future<?>> performAsyncGets(CachedSupplier<?> supplier, int count) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            futures.add(performAsyncGet(supplier));
        }
        allExecutions.addAll(futures);
        return futures;
    }

    /**
     * Wait for the provided future to complete, failing the test if it does not.
     */
    private void waitFor(Future<?> future) {
        invokeSafely(() -> future.get(10, TimeUnit.SECONDS));
    }

    /**
     * Wait for all futures in the provided collection fo complete, failing the test if they do not all complete.
     */
    private void waitFor(Collection<Future<?>> futures) {
        futures.forEach(this::waitFor);
    }

    /**
     * Wait for all async gets ever created by this class to complete, failing the test if they do not all complete.
     */
    private void waitForAsyncGetsToFinish() {
        waitFor(allExecutions);
    }

    private Instant past() {
        return now().minusSeconds(1);
    }

    private Instant future() {
        return Instant.MAX;
    }
}
