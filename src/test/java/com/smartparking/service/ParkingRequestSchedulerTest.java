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
}
