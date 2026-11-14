package com.smartparking.service;

import com.smartparking.repository.ParkingWorkflowRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

class ParkingNotificationProcessorTest {
    private final ParkingWorkflowRepository repository = mock(ParkingWorkflowRepository.class);

    @Test
    void expiredEstimateCreatesAnAlertButDoesNotReleaseTheOccupiedSlot() {
        Instant now = Instant.now();
        Timestamp start = Timestamp.valueOf(LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC));
        Timestamp expectedEnd = Timestamp.valueOf(LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
        when(repository.sessionsNeedingNotifications(15, 60)).thenReturn(List.of(Map.of(
                "sessionId", 31L, "startedAt", start, "expectedEndAt", expectedEnd,
                "registrationNumber", "UK1111111", "slotNumber", "C-02")));

        new ParkingNotificationProcessor(repository, 15, 60).processDueNotifications();

        verify(repository).createNotificationIfAbsent(org.mockito.ArgumentMatchers.eq("OVERDUE:31"),
                org.mockito.ArgumentMatchers.eq("PARKING_TIME_EXPIRED"),
                org.mockito.ArgumentMatchers.contains("Slot C-02 remains occupied until checkout is confirmed."),
                org.mockito.ArgumentMatchers.eq(31L));
        verify(repository, never()).releaseSlot(anyLong());
    }

    @Test
    void unknownDurationUsesReminderLanguageRatherThanAnExpiredClaim() {
        Instant now = Instant.now();
        Timestamp start = Timestamp.valueOf(LocalDateTime.ofInstant(now.minusSeconds(7200), ZoneOffset.UTC));
        Map<String, Object> unknownSession = new java.util.HashMap<>();
        unknownSession.put("sessionId", 32L);
        unknownSession.put("startedAt", start);
        unknownSession.put("expectedEndAt", null);
        unknownSession.put("registrationNumber", "UK2222222");
        unknownSession.put("slotNumber", "B-01");
        when(repository.sessionsNeedingNotifications(15, 60)).thenReturn(List.of(unknownSession));

        new ParkingNotificationProcessor(repository, 15, 60).processDueNotifications();

        verify(repository).createNotificationIfAbsent(
                org.mockito.ArgumentMatchers.startsWith("UNKNOWN:32:"),
                org.mockito.ArgumentMatchers.eq("UNKNOWN_DURATION_REMINDER"),
                org.mockito.ArgumentMatchers.contains("has an unknown parking duration"), org.mockito.ArgumentMatchers.eq(32L));
    }
}
