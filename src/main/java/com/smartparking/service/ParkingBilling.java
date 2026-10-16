package com.smartparking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

public final class ParkingBilling {
    private ParkingBilling() {}

    public record Charge(long durationSeconds, long chargedHours, BigDecimal fee) {}

    public static Charge calculate(Instant startedAt, Instant endedAt, BigDecimal hourlyRate) {
        if (startedAt == null || endedAt == null || hourlyRate == null || hourlyRate.signum() < 0
                || endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Parking times and a non-negative hourly rate are required");
        }
        Duration duration = Duration.between(startedAt, endedAt);
        long seconds = duration.getSeconds();
        long ceilingSeconds = seconds + (duration.getNano() == 0 ? 0 : 1);
        long hours = duration.isZero() ? 0 : (ceilingSeconds + 3599) / 3600;
        BigDecimal fee = hourlyRate.multiply(BigDecimal.valueOf(hours)).setScale(2, RoundingMode.HALF_UP);
        return new Charge(seconds, hours, fee);
    }
}
