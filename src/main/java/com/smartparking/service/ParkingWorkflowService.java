package com.smartparking.service;

import com.smartparking.repository.ParkingWorkflowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ParkingWorkflowService {
    private static final Set<String> VEHICLE_TYPES = Set.of("BIKE", "CAR", "EV");
    private static final int MAX_PRIORITY = 100;
    private static final SchedulingAlgorithm AUTOMATIC_ALGORITHM = SchedulingAlgorithm.PRIORITY_AGING;
    private final ParkingWorkflowRepository repository;
    private final ParkingRequestScheduler scheduler;
    private final BigDecimal hourlyRate;

    public ParkingWorkflowService(ParkingWorkflowRepository repository,
                                  @Value("${parking.billing.hourly-rate:2.00}") BigDecimal hourlyRate) {
        if (hourlyRate == null || hourlyRate.signum() < 0)
            throw new IllegalArgumentException("Parking hourly rate must be non-negative");
        this.repository = repository;
        this.scheduler = new ParkingRequestScheduler();
        this.hourlyRate = hourlyRate.setScale(2, RoundingMode.HALF_UP);
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
        return createRequest(vehicleId, priority, null);
    }

    @Transactional
    public Map<String, Object> createRequest(Long vehicleId, Integer priority, Integer expectedDurationMinutes) {
        if (vehicleId == null || vehicleId <= 0) throw badRequest("A valid vehicle ID is required");
        if (repository.vehicle(vehicleId) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found");
        int requestPriority = priority == null ? 0 : priority;
        if (requestPriority < 0 || requestPriority > MAX_PRIORITY)
            throw badRequest("Priority must be between 0 and 100; higher values run first");
        if (expectedDurationMinutes != null && (expectedDurationMinutes < 1 || expectedDurationMinutes > 10080))
            throw badRequest("Expected duration must be between 1 minute and 7 days, or left blank for unknown");
        long requestId = repository.createRequest(vehicleId, requestPriority, expectedDurationMinutes);
        repository.recordAudit("REQUEST_CREATED", requestId, null, null, "SUCCESS",
                "vehicleId=" + vehicleId + ", priority=" + requestPriority +
                        ", expectedDurationMinutes=" + expectedDurationMinutes);
        List<Map<String, Object>> allocations = autoAllocatePendingRequests();
        Map<String, Object> result = new LinkedHashMap<>(repository.request(requestId));
        result.put("automaticAllocations", allocations);
        return result;
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
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Long> fcfs = new ArrayList<>();
        for (ParkingRequestScheduler.ScheduledRequest request :
                scheduler.order(pending, SchedulingAlgorithm.FCFS, now))
            fcfs.add(request.id());
        List<Long> priority = new ArrayList<>();
        for (ParkingRequestScheduler.ScheduledRequest request :
                scheduler.order(pending, SchedulingAlgorithm.PRIORITY, now))
            priority.add(request.id());
        List<Long> priorityAging = new ArrayList<>();
        for (ParkingRequestScheduler.ScheduledRequest request :
                scheduler.order(pending, SchedulingAlgorithm.PRIORITY_AGING, now))
            priorityAging.add(request.id());
        result.put("fcfs", fcfs);
        result.put("priority", priority);
        result.put("priorityAging", priorityAging);
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
        if (Boolean.FALSE.equals(outcome.get("allocated"))) {
            Map<String, Object> conflict = new LinkedHashMap<>(outcome);
            conflict.put("error", "No compatible available slot is currently available; request remains pending");
            return conflict;
        }
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

    @Transactional
    public List<Map<String, Object>> autoAllocatePendingRequests() {
        List<Map<String, Object>> allocations = new ArrayList<>();
        List<ParkingRequestScheduler.ScheduledRequest> ordered =
                scheduler.order(pendingRequests(), AUTOMATIC_ALGORITHM);
        for (ParkingRequestScheduler.ScheduledRequest scheduled : ordered) {
            Map<String, Object> request = repository.request(scheduled.id());
            if (request == null || !"PENDING".equals(request.get("status"))) continue;
            String vehicleType = String.valueOf(request.get("vehicleType"));
            if (repository.availableSlots(vehicleType).isEmpty()) continue;

            Map<String, Object> result = tryAllocate(request, AUTOMATIC_ALGORITHM, true);
            if (Boolean.TRUE.equals(result.get("allocated"))) allocations.add(result);
        }
        return allocations;
    }

    private Map<String, Object> tryAllocate(Map<String, Object> request, SchedulingAlgorithm algorithm) {
        return tryAllocate(request, algorithm, false);
    }

    private Map<String, Object> tryAllocate(Map<String, Object> request, SchedulingAlgorithm algorithm,
                                            boolean skipIfAlreadyClaimed) {
        long requestId = ((Number) request.get("id")).longValue();
        if (repository.claimRequest(requestId, algorithm.name()) == 0) {
            if (skipIfAlreadyClaimed) return Map.of("allocated", false, "requestId", requestId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parking request has already been processed");
        }
        long vehicleId = ((Number) request.get("vehicleId")).longValue();
        String vehicleType = String.valueOf(request.get("vehicleType"));
        for (Map<String, Object> slot : repository.availableSlots(vehicleType)) {
            long slotId = ((Number) slot.get("id")).longValue();
            if (repository.occupyIfAvailable(slotId) == 0) continue;
            long sessionId = repository.createSession(requestId, vehicleId, slotId, hourlyRate);
            repository.createNotificationIfAbsent("ALLOCATION:" + sessionId, "NEW_ALLOCATION",
                    "Parking request " + requestId + " for vehicle " + request.get("registrationNumber")
                            + " was allocated to slot " + slot.get("slotNumber") + ".", sessionId);
            repository.recordAllocation(requestId, slotId, "ALLOCATED", "Slot allocated using " + algorithm.name());
            repository.recordAudit("ALLOCATION_ATTEMPT", requestId, sessionId, slotId, "ALLOCATED",
                    "Allocated using " + algorithm.name());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("allocated", true);
            result.put("requestId", requestId);
            result.put("sessionId", sessionId);
            result.put("slot", slot);
            result.put("algorithm", algorithm.name());
            return result;
        }
        repository.recordAllocation(requestId, null, "NO_SLOT", "No available slot compatible with " + vehicleType);
        repository.recordAudit("ALLOCATION_ATTEMPT", requestId, null, null, "NO_SLOT",
                "No available slot compatible with " + vehicleType);
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
        Instant end = endedAt.toLocalDateTime().toInstant(ZoneOffset.UTC);
        BigDecimal sessionRate = (BigDecimal) session.get("hourlyRate");
        ParkingBilling.Charge charge = ParkingBilling.calculate(start.toLocalDateTime().toInstant(ZoneOffset.UTC), end, sessionRate);
        if (repository.saveSessionFee(sessionId, charge.fee()) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parking session fee was already recorded");
        repository.recordAudit("SESSION_RELEASED", ((Number) session.get("requestId")).longValue(),
                sessionId, slotId, "SUCCESS",
                "durationSeconds=" + charge.durationSeconds() + ", chargedHours=" + charge.chargedHours() +
                        ", hourlyRate=" + sessionRate + ", fee=" + charge.fee());
        List<Map<String, Object>> allocations = autoAllocatePendingRequests();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("slotId", slotId);
        result.put("durationSeconds", charge.durationSeconds());
        result.put("chargedHours", charge.chargedHours());
        result.put("hourlyRate", sessionRate);
        result.put("fee", charge.fee());
        result.put("automaticAllocations", allocations);
        return result;
    }

    @Transactional
    public Map<String, Object> extend(long sessionId, Integer additionalMinutes) {
        if (additionalMinutes == null || additionalMinutes < 1 || additionalMinutes > 10080)
            throw badRequest("Extension must be between 1 minute and 7 days");
        Map<String, Object> session = repository.activeSession(sessionId);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active parking session not found");
        if (repository.extendSession(sessionId, additionalMinutes) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parking session changed; extension was not applied");
        long requestId = ((Number) session.get("requestId")).longValue();
        long slotId = ((Number) session.get("slotId")).longValue();
        repository.recordAudit("SESSION_EXTENDED", requestId, sessionId, slotId, "SUCCESS",
                "additionalMinutes=" + additionalMinutes);
        return session(sessionId);
    }

    public Map<String, Object> notifications(boolean unreadOnly, int offset, int limit) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100)
            throw badRequest("Notification offset must be between 0 and 1000000 and limit must be between 1 and 100");
        return Map.of("items", repository.notifications(unreadOnly, offset, limit),
                "unreadCount", repository.unreadNotificationCount(), "offset", offset, "limit", limit);
    }

    @Transactional
    public Map<String, Object> acknowledgeNotification(long id) {
        Map<String, Object> notification = repository.notification(id);
        if (notification == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        repository.acknowledgeNotification(id);
        return Map.of("id", id, "acknowledged", true);
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
        return withCharge(session, endedAt == null ? Instant.now() : endedAt.toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    public List<Map<String, Object>> history() {
        return repository.history();
    }

    public List<Map<String, Object>> auditEvents(int limit) {
        if (limit < 1 || limit > 200) throw badRequest("Audit limit must be between 1 and 200");
        return repository.auditEvents(limit);
    }

    public Map<String, Object> reports(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate reportTo = to == null ? today : to;
        LocalDate reportFrom = from == null ? reportTo.minusDays(29) : from;
        long dayCount = ChronoUnit.DAYS.between(reportFrom, reportTo) + 1;
        if (reportFrom.isAfter(reportTo) || reportTo.isAfter(today) || dayCount > 366)
            throw badRequest("Report dates must be ordered, no later than today, and span at most 366 days");

        return Map.of(
                "from", reportFrom.toString(),
                "to", reportTo.toString(),
                "occupancy", repository.occupancyReport(java.sql.Date.valueOf(reportFrom), (int) dayCount),
                "revenue", repository.revenueReport(java.sql.Date.valueOf(reportFrom),
                        java.sql.Date.valueOf(reportTo.plusDays(1))),
                "allocationOutcomes", repository.allocationOutcomeReport(java.sql.Date.valueOf(reportFrom),
                        java.sql.Date.valueOf(reportTo.plusDays(1))));
    }

    private List<ParkingRequestScheduler.ScheduledRequest> pendingRequests() {
        List<ParkingRequestScheduler.ScheduledRequest> result = new ArrayList<>();
        for (Map<String, Object> row : repository.pendingForScheduling())
            result.add(ParkingWorkflowRepository.scheduledRequest(row));
        return result;
    }

    private Map<String, Object> withCharge(Map<String, Object> session, Instant endedAt) {
        Timestamp startedAt = (Timestamp) session.get("startedAt");
        BigDecimal sessionRate = (BigDecimal) session.get("hourlyRate");
        ParkingBilling.Charge charge = ParkingBilling.calculate(
                startedAt.toLocalDateTime().toInstant(ZoneOffset.UTC), endedAt, sessionRate);
        Map<String, Object> result = new LinkedHashMap<>(session);
        result.put("durationSeconds", charge.durationSeconds());
        result.put("chargedHours", charge.chargedHours());
        result.put("hourlyRate", sessionRate);
        if (session.get("endedAt") == null) {
            result.put("estimatedFee", charge.fee());
        } else {
            result.put("finalFee", session.get("billedFee") == null ? charge.fee() : session.get("billedFee"));
        }
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
