package com.smartparking.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

public class ParkingRequestScheduler {
    public record ScheduledRequest(long id, int priority, LocalDateTime requestedAt) {}

    public List<ScheduledRequest> order(List<ScheduledRequest> requests, SchedulingAlgorithm algorithm) {
        Comparator<ScheduledRequest> order = Comparator.comparing(ScheduledRequest::requestedAt)
                .thenComparingLong(ScheduledRequest::id);
        if (algorithm == SchedulingAlgorithm.PRIORITY) {
            order = Comparator.comparingInt(ScheduledRequest::priority).reversed()
                    .thenComparing(ScheduledRequest::requestedAt)
                    .thenComparingLong(ScheduledRequest::id);
        }
        return requests.stream().sorted(order).toList();
    }
}
