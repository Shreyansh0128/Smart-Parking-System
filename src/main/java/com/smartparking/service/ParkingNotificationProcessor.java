package com.smartparking.service;

import com.smartparking.repository.ParkingWorkflowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class ParkingNotificationProcessor {
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault());
    private final ParkingWorkflowRepository repository;
    private final int upcomingMinutes;
    private final int unknownReminderMinutes;

    public ParkingNotificationProcessor(ParkingWorkflowRepository repository,
                                        @Value("${parking.notifications.upcoming-minutes:15}") int upcomingMinutes,
                                        @Value("${parking.notifications.unknown-reminder-minutes:60}") int unknownReminderMinutes) {
        if (upcomingMinutes < 1 || unknownReminderMinutes < 1)
            throw new IllegalArgumentException("Notification intervals must be positive");
        this.repository = repository;
        this.upcomingMinutes = upcomingMinutes;
        this.unknownReminderMinutes = unknownReminderMinutes;
    }

    @Scheduled(fixedDelayString = "${parking.notifications.poll-interval-ms:60000}")
    @Transactional
    public void processDueNotifications() {
        Instant now = Instant.now();
        List<Map<String, Object>> sessions =
                repository.sessionsNeedingNotifications(upcomingMinutes, unknownReminderMinutes);
        for (Map<String, Object> session : sessions) {
            long sessionId = ((Number) session.get("sessionId")).longValue();
            Timestamp expectedEnd = (Timestamp) session.get("expectedEndAt");
            String registration = String.valueOf(session.get("registrationNumber"));
            String slot = String.valueOf(session.get("slotNumber"));
            if (expectedEnd == null) {
                Timestamp started = (Timestamp) session.get("startedAt");
                long interval = Math.max(1, Duration.between(started.toLocalDateTime().toInstant(ZoneOffset.UTC), now).toMinutes()
                        / unknownReminderMinutes);
                repository.createNotificationIfAbsent("UNKNOWN:" + sessionId + ":" + interval,
                        "UNKNOWN_DURATION_REMINDER",
                        "Vehicle " + registration + " has an unknown parking duration. Check whether it still needs slot "
                                + slot + ".", sessionId);
            } else if (!expectedEnd.toLocalDateTime().toInstant(ZoneOffset.UTC).isAfter(now)) {
                repository.createNotificationIfAbsent("OVERDUE:" + sessionId, "PARKING_TIME_EXPIRED",
                        "Vehicle " + registration + ": expected parking time ended at "
                                + DISPLAY_TIME.format(expectedEnd.toLocalDateTime().toInstant(ZoneOffset.UTC)) + ". Slot " + slot
                                + " remains occupied until checkout is confirmed.", sessionId);
            } else {
                repository.createNotificationIfAbsent("UPCOMING:" + sessionId, "UPCOMING_END_TIME",
                        "Vehicle " + registration + " is expected to leave slot " + slot + " at "
                                + DISPLAY_TIME.format(expectedEnd.toLocalDateTime().toInstant(ZoneOffset.UTC)) + ".", sessionId);
            }
        }
    }
}
