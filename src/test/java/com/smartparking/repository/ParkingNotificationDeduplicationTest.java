package com.smartparking.repository;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParkingNotificationDeduplicationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ParkingWorkflowRepository repository = new ParkingWorkflowRepository(jdbc);

    @Test
    void notificationInsertUsesAnEventKeyUpsert() {
        when(jdbc.update(contains("MERGE INTO PARKING_NOTIFICATIONS"), any(Object[].class))).thenReturn(1);

        assertEquals(1, repository.createNotificationIfAbsent("OVERDUE:31", "PARKING_TIME_EXPIRED", "expired", 31L));

        verify(jdbc).update(contains("ON (N.EVENT_KEY=S.EVENT_KEY)"), eq("OVERDUE:31"),
                eq("PARKING_TIME_EXPIRED"), eq("expired"), eq(31L));
    }

    @Test
    void concurrentDuplicateEventKeyIsTreatedAsAlreadyInserted() {
        doThrow(new DuplicateKeyException("duplicate event key"))
                .when(jdbc).update(contains("MERGE INTO PARKING_NOTIFICATIONS"), any(Object[].class));

        assertEquals(0, repository.createNotificationIfAbsent(
                "OVERDUE:31", "PARKING_TIME_EXPIRED", "expired", 31L));
    }
}
