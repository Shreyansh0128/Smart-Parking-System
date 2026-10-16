package com.smartparking.service;

public enum SchedulingAlgorithm {
    FCFS,
    PRIORITY;

    public static SchedulingAlgorithm parse(String value) {
        if (value == null || value.isBlank()) return FCFS;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Algorithm must be FCFS or PRIORITY");
        }
    }
}
