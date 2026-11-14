package com.smartparking.model;

public final class ParkingDtos {
    private ParkingDtos() {}

    public record UserInput(String fullName, String phone) {}
    public record VehicleInput(Long userId, String registrationNumber, String vehicleType) {}
    public record RequestInput(Long vehicleId, Integer priority, Integer expectedDurationMinutes) {}
    public record AllocationInput(String algorithm) {}
}
