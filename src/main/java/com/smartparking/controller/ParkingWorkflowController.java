package com.smartparking.controller;

import com.smartparking.model.ParkingDtos.AllocationInput;
import com.smartparking.model.ParkingDtos.RequestInput;
import com.smartparking.model.ParkingDtos.UserInput;
import com.smartparking.model.ParkingDtos.VehicleInput;
import com.smartparking.service.ParkingWorkflowService;
import com.smartparking.service.SchedulingAlgorithm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/parking")
public class ParkingWorkflowController {
    private final ParkingWorkflowService service;

    public ParkingWorkflowController(ParkingWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return service.users();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerUser(@RequestBody UserInput input) {
        return service.registerUser(input == null ? null : input.fullName(), input == null ? null : input.phone());
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles() {
        return service.vehicles();
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerVehicle(@RequestBody VehicleInput input) {
        return service.registerVehicle(input == null ? null : input.userId(),
                input == null ? null : input.registrationNumber(), input == null ? null : input.vehicleType());
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests() {
        return service.requests();
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRequest(@RequestBody RequestInput input) {
        return service.createRequest(input == null ? null : input.vehicleId(), input == null ? null : input.priority());
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable long id) {
        return service.request(id);
    }

    @PostMapping("/requests/{id}/allocate")
    public Map<String, Object> allocate(@PathVariable long id,
                                        @RequestBody(required = false) AllocationInput input) {
        return allocationOrConflict(service.allocate(id, algorithm(input)));
    }

    @PostMapping("/requests/allocate-next")
    public Map<String, Object> allocateNext(@RequestBody(required = false) AllocationInput input) {
        return allocationOrConflict(service.allocateNext(algorithm(input)));
    }

    @GetMapping("/requests/scheduling/simulate")
    public Map<String, Object> schedulingSimulation() {
        return service.schedulingSimulation();
    }

    @GetMapping("/sessions/active")
    public List<Map<String, Object>> activeSessions() {
        return service.activeSessions();
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable long id) {
        return service.session(id);
    }

    @PostMapping("/sessions/{id}/release")
    public Map<String, Object> release(@PathVariable long id) {
        return service.release(id);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return service.history();
    }

    private SchedulingAlgorithm algorithm(AllocationInput input) {
        try {
            return SchedulingAlgorithm.parse(input == null ? null : input.algorithm());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private Map<String, Object> allocationOrConflict(Map<String, Object> result) {
        if (Boolean.FALSE.equals(result.get("allocated")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.valueOf(result.getOrDefault("error",
                    "No compatible available slot is currently available")));
        return result;
    }
}
