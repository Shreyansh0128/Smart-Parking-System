package com.smartparking.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ParkingSlotClaimConcurrencyTest {
    @Test
    void concurrentConditionalClaimsAllowAtMostOneWinner() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicBoolean slotAvailable = new AtomicBoolean(true);
        doAnswer(invocation -> slotAvailable.compareAndSet(true, false) ? 1 : 0)
                .when(jdbc).update(eq("UPDATE PARKING_SLOTS SET STATUS='OCCUPIED' WHERE SLOT_ID=? AND STATUS='AVAILABLE'"),
                        any(Object[].class));
        ParkingWorkflowRepository repository = new ParkingWorkflowRepository(jdbc);

        int competitors = 12;
        ExecutorService executor = Executors.newFixedThreadPool(competitors);
        CountDownLatch ready = new CountDownLatch(competitors);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < competitors; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.occupyIfAvailable(1);
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Integer> attempt : attempts) winners += attempt.get();
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
    }
}
