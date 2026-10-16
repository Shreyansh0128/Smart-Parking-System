package com.smartparking.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParkingControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ParkingController controller = new ParkingController(jdbc);

    @Test
    void statsReturnZeroCountsWhenTheInventoryIsEmpty() {
        when(jdbc.queryForMap(contains("COUNT(*)"))).thenReturn(Map.of(
                "TOTALSLOTS", 0L, "AVAILABLESLOTS", 0L, "OCCUPIEDSLOTS", 0L, "RESERVEDSLOTS", 0L));

        assertEquals(Map.of("totalSlots", 0, "availableSlots", 0, "occupiedSlots", 0, "reservedSlots", 0),
                controller.getParkingStats());
    }

    @Test
    void fullSlotEditCannotMakeAnActivelyAllocatedSlotAvailable() {
        when(jdbc.queryForMap(contains("FOR UPDATE"), eq(7L))).thenReturn(Map.of(
                "id", 7L, "slotNumber", "C-07", "floor", 0, "slotType", "CAR", "status", "OCCUPIED"));
        when(jdbc.queryForObject(contains("SESSION_STATUS='ACTIVE'"), eq(Integer.class), eq(7L))).thenReturn(1);

        assertThrows(ResponseStatusException.class,
                () -> controller.updateSlot(7, new ParkingController.SlotInput("C-07", 0, "CAR", "AVAILABLE")));
        verify(jdbc, never()).update(contains("UPDATE PARKING_SLOTS"), eq("C-07"), eq(0),
                eq("CAR"), eq("AVAILABLE"), eq(7L));
    }
}
