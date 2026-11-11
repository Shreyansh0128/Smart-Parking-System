package com.smartparking.service;

import com.smartparking.repository.ParkingWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ParkingWorkflowServiceTest {
    private final ParkingWorkflowRepository repository = mock(ParkingWorkflowRepository.class);
    private final ParkingWorkflowService service =
            new ParkingWorkflowService(repository, new BigDecimal("2.00"));

    @Test
    void auditLimitMustBeWithinSupportedBounds() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.auditEvents(201));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void reportsRejectReversedDateRanges() {
        LocalDate today = LocalDate.now();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reports(today, today.minusDays(1)));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void reportsRejectRangesLongerThan366Days() {
        LocalDate today = LocalDate.now();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reports(today.minusDays(366), today));

        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void requestCreationRecordsAnAuditEventWithTheCreatedRequest() {
        when(repository.vehicle(8L)).thenReturn(Map.of("id", 8L));
        when(repository.createRequest(8L, 35)).thenReturn(14L);
        when(repository.request(14L)).thenReturn(Map.of("id", 14L, "status", "PENDING"));

        service.createRequest(8L, 35);

        verify(repository).recordAudit("REQUEST_CREATED", 14L, null, null, "SUCCESS",
                "vehicleId=8, priority=35");
    }

    @Test
    void noCompatibleSlotKeepsTheRequestPendingAndAuditsTheAttempt() {
        Timestamp requestedAt = Timestamp.valueOf(LocalDateTime.now().minusMinutes(1));
        Map<String, Object> request = Map.of("id", 14L, "vehicleId", 8L, "vehicleType", "CAR",
                "status", "PENDING", "requestedAt", requestedAt, "priority", 10);
        when(repository.request(14L)).thenReturn(request);
        when(repository.pendingForScheduling()).thenReturn(List.of(Map.of(
                "id", 14L, "priority", 10, "requestedAt", requestedAt, "status", "PENDING")));
        when(repository.availableSlots("CAR")).thenReturn(List.of());
        when(repository.claimRequest(14L, "FCFS")).thenReturn(1);

        Map<String, Object> outcome = service.allocate(14L, SchedulingAlgorithm.FCFS);

        assertEquals(Boolean.FALSE, outcome.get("allocated"));
        assertEquals("No compatible available slot is currently available; request remains pending",
                outcome.get("error"));
        verify(repository).recordAllocation(14L, null, "NO_SLOT", "No available slot compatible with CAR");
        verify(repository).recordAudit("ALLOCATION_ATTEMPT", 14L, null, null, "NO_SLOT",
                "No available slot compatible with CAR");
        verify(repository).returnRequestToPending(14L);
    }

    @Test
    void releaseUsesTheSessionRateAndPersistsFeeAndAudit() {
        Timestamp startedAt = Timestamp.valueOf("2026-10-15 09:00:00");
        Timestamp endedAt = Timestamp.valueOf("2026-10-15 10:00:01");
        when(repository.activeSession(22L)).thenReturn(Map.of(
                "id", 22L, "requestId", 14L, "slotId", 7L, "startedAt", startedAt,
                "hourlyRate", new BigDecimal("2.50")));
        when(repository.closeSession(22L)).thenReturn(1);
        when(repository.releaseSlot(7L)).thenReturn(1);
        when(repository.session(22L)).thenReturn(Map.of("endedAt", endedAt));
        when(repository.saveSessionFee(22L, new BigDecimal("5.00"))).thenReturn(1);

        Map<String, Object> outcome = service.release(22L);

        assertEquals(new BigDecimal("5.00"), outcome.get("fee"));
        assertEquals(new BigDecimal("2.50"), outcome.get("hourlyRate"));
        verify(repository).saveSessionFee(22L, new BigDecimal("5.00"));
        verify(repository).recordAudit("SESSION_RELEASED", 14L, 22L, 7L, "SUCCESS",
                "durationSeconds=3601, chargedHours=2, hourlyRate=2.50, fee=5.00");
    }
}
