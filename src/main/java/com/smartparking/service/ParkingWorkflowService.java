package com.smartparking.service;

import com.smartparking.repository.ParkingWorkflowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ParkingWorkflowService {
    private static final Set<String> VEHICLE_TYPES = Set.of("BIKE", "CAR", "EV");
    private static final int MAX_PRIORITY = 100;
    private final ParkingWorkflowRepository repository;
    private final ParkingRequestScheduler scheduler;
    private final BigDecimal hourlyRate;

    public ParkingWorkflowService(ParkingWorkflowRepository repository,
                                  @Value("${parking.billing.hourly-rate:2.00}") BigDecimal hourlyRate) {
        this.repository = repository;
        this.scheduler = new ParkingRequestScheduler();
        this.hourlyRate = hourlyRate;
    }

    @Transactional
    public Map<String, Object> registerUser(String fullName, String phone) {
        if (fullName == null || fullName.isBlank() || fullName.trim().length() > 100)
            throw badRequest("Full name is required and must be at most 100 characters");
        if (phone != null && phone.trim().length() > 20)
            throw badRequest("Phone must be at most 20 characters");
        try {
            long id = repository.createUser(fullName.trim(), blankToNull(phone));
            return repository.user(id);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number is already registered");
        }
    }

    public List<Map<String, Object>> users() {
        return repository.users();
    }

    @Transactional
    public Map<String, Object> registerVehicle(Long userId, String registrationNumber, String vehicleType) {
        if (userId == null || userId <= 0) throw badRequest("A valid user ID is required");
        if (repository.user(userId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        if (registrationNumber == null || registrationNumber.isBlank() || registrationNumber.trim().length() > 20)
            throw badRequest("Registration number is required and must be at most 20 characters");
        String type = normalize(vehicleType);
        if (!VEHICLE_TYPES.contains(type)) throw badRequest("Vehicle type must be BIKE, CAR, or EV");
        try {
            long id = repository.createVehicle(userId, registrationNumber.trim().toUpperCase(), type);
            return repository.vehicles().stream()
                    .filter(vehicle -> ((Number) vehicle.get("id")).longValue() == id)
                    .findFirst().orElseThrow();
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vehicle registration number already exists");
        }
    }

    public List<Map<String, Object>> vehicles() {
        return repository.vehicles();
    }

    @Transactional
    public Map<String, Object> createRequest(Long vehicleId, Integer priority) {
        if (vehicleId == null || vehicleId <= 0) throw badRequest("A valid vehicle ID is required");
        if (repository.vehicle(vehicleId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found");
        int requestPriority = priority == null ? 0 : priority;
        if (requestPriority < 0 || requestPriority > MAX_PRIORITY)
            throw badRequest("Priority must be between 0 and 100; higher values run first");
        return repository.request(repository.createRequest(vehicleId, requestPriority));
    }

    public List<Map<String, Object>> requests() {
        return repository.requests();
    }

    public Map<String, Object> request(long id) {
        Map<String, Object> request = repository.request(id);
        if (request == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking request not found");
        return request;
    }

    public Map<String, Object> schedulingSimulation() {
        List<ParkingRequestScheduler.ScheduledRequest> pending = pendingRequests();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Long> fcfs = new ArrayList<>();
        for (ParkingRequestScheduler.ScheduledRequest request : scheduler.order(pending, SchedulingAlgorithm.FCFS))
            fcfs.add(request.id());
        List<Long> priority = new ArrayList<>();
        for (ParkingRequestScheduler.ScheduledRequest request : scheduler.order(pending, SchedulingAlgorithm.PRIORITY))
            priority.add(request.id());
        result.put("fcfs", fcfs);
        result.put("priority", priority);
        return result;
    }

    @Transactional
    public Map<String, Object> allocate(long requestId, SchedulingAlgorithm algorithm) {
        Map<String, Object> request = request(requestId);
        if (!"PENDING".equals(request.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending requests can be allocated");
        for (ParkingRequestScheduler.ScheduledRequest scheduled : scheduler.order(pendingRequests(), algorithm)) {
            Map<String, Object> earlier = repository.request(scheduled.id());
            if (earlier == null || !"PENDING".equals(earlier.get("status"))) continue;
            String type = String.valueOf(earlier.get("vehicleType"));
            if (!repository.availableSlots(type).isEmpty()) {
                if (scheduled.id() != requestId)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Request " + scheduled.id() + " is next under " + algorithm.name() + " scheduling");
                break;
            }
        }
        Map<String, Object> outcome = tryAllocate(request, algorithm);
        if (Boolean.FALSE.equals(outcome.get("allocated")))
            outcome.put("error", "No compatible available slot is currently available; request remains pending");
        return outcome;
    }

    @Transactional
    public Map<String, Object> allocateNext(SchedulingAlgorithm algorithm) {
        List<ParkingRequestScheduler.ScheduledRequest> ordered = scheduler.order(pendingRequests(), algorithm);
        for (ParkingRequestScheduler.ScheduledRequest scheduled : ordered) {
            Map<String, Object> request = repository.request(scheduled.id());
            if (request == null || !"PENDING".equals(request.get("status"))) continue;
            Map<String, Object> result = tryAllocate(request, algorithm);
            if (Boolean.TRUE.equals(result.get("allocated"))) return result;
        }
        return Map.of("allocated", false, "error", "No pending request has a compatible available slot");
    }

    private Map<String, Object> tryAllocate(Map<String, Object> request, SchedulingAlgorithm algorithm) {
        long requestId = ((Number) request.get("id")).longValue();
        if (repository.claimRequest(requestId, algorithm.name()) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parking request has already been processed");
        long vehicleId = ((Number) request.get("vehicleId")).longValue();
        String vehicleType = String.valueOf(request.get("vehicleType"));
        for (Map<String, Object> slot : repository.availableSlots(vehicleType)) {
            long slotId = ((Number) slot.get("id")).longValue();
            if (repository.occupyIfAvailable(slotId) == 0) continue;
            long sessionId = repository.createSession(requestId, vehicleId, slotId);
            repository.recordAllocation(requestId, slotId, "ALLOCATED", "Slot allocated using " + algorithm.name());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("allocated", true);
            result.put("requestId", requestId);
            result.put("sessionId", sessionId);
            result.put("slot", slot);
            result.put("algorithm", algorithm.name());
            return result;
        }
        repository.recordAllocation(requestId, null, "NO_SLOT", "No available slot compatible with " + vehicleType);
        repository.returnRequestToPending(requestId);
        return Map.of("allocated", false, "requestId", requestId);
    }

    @Transactional
    public Map<String, Object> release(long sessionId) {
        Map<String, Object> session = repository.activeSession(sessionId);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active parking session not found");
        long slotId = ((Number) session.get("slotId")).longValue();
        if (repository.closeSession(sessionId) == 0 || repository.releaseSlot(slotId) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parking session or slot changed; no release was completed");
        Timestamp start = (Timestamp) session.get("startedAt");
        Timestamp endedAt = (Timestamp) repository.session(sessionId).get("endedAt");
        Instant end = endedAt.toInstant();
        ParkingBilling.Charge charge = ParkingBilling.calculate(start.toInstant(), end, hourlyRate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("slotId", slotId);
        result.put("durationSeconds", charge.durationSeconds());
        result.put("chargedHours", charge.chargedHours());
        result.put("hourlyRate", hourlyRate);
        result.put("fee", charge.fee());
        return result;
    }

    public List<Map<String, Object>> activeSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, Object> session : repository.activeSessions())
            sessions.add(withCharge(session, now));
        return sessions;
    }

    public Map<String, Object> session(long sessionId) {
        Map<String, Object> session = repository.session(sessionId);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking session not found");
        Timestamp endedAt = (Timestamp) session.get("endedAt");
        return withCharge(session, endedAt == null ? Instant.now() : endedAt.toInstant());
    }

    public List<Map<String, Object>> history() {
        return repository.history();
    }

    private List<ParkingRequestScheduler.ScheduledRequest> pendingRequests() {
        List<ParkingRequestScheduler.ScheduledRequest> result = new ArrayList<>();
        for (Map<String, Object> row : repository.pendingForScheduling())
            result.add(ParkingWorkflowRepository.scheduledRequest(row));
        return result;
    }

    private Map<String, Object> withCharge(Map<String, Object> session, Instant endedAt) {
        Timestamp startedAt = (Timestamp) session.get("startedAt");
        ParkingBilling.Charge charge = ParkingBilling.calculate(startedAt.toInstant(), endedAt, hourlyRate);
        Map<String, Object> result = new LinkedHashMap<>(session);
        result.put("durationSeconds", charge.durationSeconds());
        result.put("chargedHours", charge.chargedHours());
        result.put("hourlyRate", hourlyRate);
        result.put(session.get("endedAt") == null ? "estimatedFee" : "finalFee", charge.fee());
        return result;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
