package com.smartparking.service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class ParkingRequestScheduler {
    public record ScheduledRequest(long id, int priority, LocalDateTime requestedAt) {}

    public List<ScheduledRequest> order(List<ScheduledRequest> requests, SchedulingAlgorithm algorithm) {
        return order(requests, algorithm, LocalDateTime.now());
    }

    public List<ScheduledRequest> order(List<ScheduledRequest> requests, SchedulingAlgorithm algorithm,
                                        LocalDateTime now) {
        Comparator<ScheduledRequest> order = Comparator.comparing(ScheduledRequest::requestedAt)
                .thenComparingLong(ScheduledRequest::id);
        if (algorithm == SchedulingAlgorithm.PRIORITY) {
            order = Comparator.comparingInt(ScheduledRequest::priority).reversed()
                    .thenComparing(ScheduledRequest::requestedAt)
                    .thenComparingLong(ScheduledRequest::id);
        } else if (algorithm == SchedulingAlgorithm.PRIORITY_AGING) {
            order = Comparator.comparingInt((ScheduledRequest request) ->
                            effectivePriority(request, now)).reversed()
                    .thenComparing(ScheduledRequest::requestedAt)
                    .thenComparingLong(ScheduledRequest::id);
        }
        return requests.stream().sorted(order).toList();
    }

    public int effectivePriority(ScheduledRequest request, LocalDateTime now) {
        long waitedMinutes = Math.max(0, Duration.between(request.requestedAt(), now).toMinutes());
        long agingBoost = waitedMinutes / 5;
        return (int) Math.min(100, request.priority() + agingBoost);
    }
}
