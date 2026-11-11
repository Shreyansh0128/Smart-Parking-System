package com.smartparking.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParkingRequestSchedulerTest {
    private final ParkingRequestScheduler scheduler = new ParkingRequestScheduler();
    private final LocalDateTime arrival = LocalDateTime.parse("2026-10-15T09:00:00");

    @Test
    void fcfsUsesArrivalTimeThenRequestId() {
        List<ParkingRequestScheduler.ScheduledRequest> ordered = scheduler.order(List.of(
                new ParkingRequestScheduler.ScheduledRequest(7, 0, arrival.plusMinutes(1)),
                new ParkingRequestScheduler.ScheduledRequest(9, 0, arrival),
                new ParkingRequestScheduler.ScheduledRequest(4, 0, arrival)),
                SchedulingAlgorithm.FCFS);

        assertEquals(List.of(4L, 9L, 7L), ordered.stream().map(ParkingRequestScheduler.ScheduledRequest::id).toList());
    }

    @Test
    void priorityUsesHigherValueFirstAndArrivalForTies() {
        List<ParkingRequestScheduler.ScheduledRequest> ordered = scheduler.order(List.of(
                new ParkingRequestScheduler.ScheduledRequest(7, 1, arrival),
                new ParkingRequestScheduler.ScheduledRequest(9, 3, arrival.plusMinutes(1)),
                new ParkingRequestScheduler.ScheduledRequest(4, 3, arrival)),
                SchedulingAlgorithm.PRIORITY);

        assertEquals(List.of(4L, 9L, 7L), ordered.stream().map(ParkingRequestScheduler.ScheduledRequest::id).toList());
    }

    @Test
    void priorityAgingRaisesEffectivePriorityForLongWaitingRequests() {
        List<ParkingRequestScheduler.ScheduledRequest> requests = List.of(
                new ParkingRequestScheduler.ScheduledRequest(1, 0, arrival),
                new ParkingRequestScheduler.ScheduledRequest(2, 3, arrival.plusMinutes(20)));

        List<ParkingRequestScheduler.ScheduledRequest> ordered = scheduler.order(requests,
                SchedulingAlgorithm.PRIORITY_AGING, arrival.plusMinutes(120));

        assertEquals(List.of(1L, 2L), ordered.stream().map(ParkingRequestScheduler.ScheduledRequest::id).toList());
        assertEquals(24, scheduler.effectivePriority(requests.get(0), arrival.plusMinutes(120)));
    }

    @Test
    void priorityAgingIsBoundedAndUsesArrivalThenIdForTies() {
        List<ParkingRequestScheduler.ScheduledRequest> requests = List.of(
                new ParkingRequestScheduler.ScheduledRequest(9, 0, arrival),
                new ParkingRequestScheduler.ScheduledRequest(4, 100, arrival),
                new ParkingRequestScheduler.ScheduledRequest(7, 0, arrival.plusMinutes(1)));
        LocalDateTime now = arrival.plusDays(2);

        List<ParkingRequestScheduler.ScheduledRequest> ordered = scheduler.order(requests,
                SchedulingAlgorithm.PRIORITY_AGING, now);

        assertEquals(100, scheduler.effectivePriority(requests.get(0), now));
        assertEquals(List.of(4L, 9L, 7L),
                ordered.stream().map(ParkingRequestScheduler.ScheduledRequest::id).toList());
    }

    @Test
    void priorityAgingDoesNotReducePriorityForFutureDatedRequests() {
        ParkingRequestScheduler.ScheduledRequest request =
                new ParkingRequestScheduler.ScheduledRequest(1, 25, arrival.plusDays(1));

        assertEquals(25, scheduler.effectivePriority(request, arrival));
    }
}
