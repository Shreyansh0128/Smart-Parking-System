package com.smartparking.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParkingBillingTest {
    private final BigDecimal hourlyRate = new BigDecimal("2.50");
    private final Instant start = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void roundsAnyPartialHourUp() {
        ParkingBilling.Charge charge = ParkingBilling.calculate(start, start.plusSeconds(3601), hourlyRate);

        assertEquals(2, charge.chargedHours());
        assertEquals(new BigDecimal("5.00"), charge.fee());
    }

    @Test
    void chargesZeroForZeroDuration() {
        ParkingBilling.Charge charge = ParkingBilling.calculate(start, start, hourlyRate);

        assertEquals(0, charge.chargedHours());
        assertEquals(new BigDecimal("0.00"), charge.fee());
    }

    @Test
    void chargesAtLeastOneHourForAnyPositiveFractionalDuration() {
        ParkingBilling.Charge charge = ParkingBilling.calculate(start, start.plusNanos(1), hourlyRate);

        assertEquals(1, charge.chargedHours());
        assertEquals(new BigDecimal("2.50"), charge.fee());
    }

    @Test
    void rejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> ParkingBilling.calculate(start, start.minusSeconds(1), hourlyRate));
    }
}
