package com.smartparking.controller;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/parking")
public class ParkingController {
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "OCCUPIED", "RESERVED");
    private static final Set<String> SLOT_TYPES = Set.of("BIKE", "CAR", "EV");
    private final JdbcTemplate jdbc;

    public ParkingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/stats")
    public Map<String, Integer> getParkingStats() {
        Map<String, Object> stats = jdbc.queryForMap("SELECT COUNT(*) totalSlots, " +
                "NVL(SUM(CASE WHEN STATUS='AVAILABLE' THEN 1 ELSE 0 END), 0) availableSlots, " +
                "NVL(SUM(CASE WHEN STATUS='OCCUPIED' THEN 1 ELSE 0 END), 0) occupiedSlots, " +
                "NVL(SUM(CASE WHEN STATUS='RESERVED' THEN 1 ELSE 0 END), 0) reservedSlots FROM PARKING_SLOTS");
        return Map.of("totalSlots", ((Number) stats.get("TOTALSLOTS")).intValue(),
                "availableSlots", ((Number) stats.get("AVAILABLESLOTS")).intValue(),
                "occupiedSlots", ((Number) stats.get("OCCUPIEDSLOTS")).intValue(),
                "reservedSlots", ((Number) stats.get("RESERVEDSLOTS")).intValue());
    }

    @GetMapping("/slots")
    public List<Map<String, Object>> slots(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer floor) {
        if (status != null && !STATUSES.contains(status.trim().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be AVAILABLE, OCCUPIED, or RESERVED");
        StringBuilder sql = new StringBuilder("SELECT SLOT_ID AS \"id\", SLOT_NUMBER AS \"slotNumber\", FLOOR AS \"floor\", SLOT_TYPE AS \"slotType\", STATUS AS \"status\" FROM PARKING_SLOTS WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (status != null) { sql.append(" AND STATUS=?"); args.add(status.trim().toUpperCase()); }
        if (floor != null) {
            if (floor < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Floor must be zero or greater");
            sql.append(" AND FLOOR=?");
            args.add(floor);
        }
        sql.append(" ORDER BY FLOOR, SLOT_NUMBER");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/slots/{id}")
    public Map<String, Object> slot(@PathVariable long id) { return findSlot(id); }

    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSlot(@RequestBody SlotInput input) {
        validate(input);
        try {
            jdbc.update("INSERT INTO PARKING_SLOTS (SLOT_ID,SLOT_NUMBER,FLOOR,SLOT_TYPE,STATUS) VALUES (PARKING_SLOT_SEQ.NEXTVAL,?,?,?,?)",
                    input.slotNumber().trim().toUpperCase(), input.floor(), input.slotType().trim().toUpperCase(),
                    normalizedStatus(input.status() == null ? "AVAILABLE" : input.status()));
        } catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot number already exists"); }
        return jdbc.queryForMap("SELECT SLOT_ID AS \"id\",SLOT_NUMBER AS \"slotNumber\",FLOOR AS \"floor\",SLOT_TYPE AS \"slotType\",STATUS AS \"status\" FROM PARKING_SLOTS WHERE SLOT_NUMBER=?", input.slotNumber().trim().toUpperCase());
    }

    @PutMapping("/slots/{id}")
    @Transactional
    public Map<String, Object> updateSlot(@PathVariable long id, @RequestBody SlotInput input) {
        validate(input);
        findSlotForUpdate(id);
        String status = normalizedStatus(input.status() == null ? "AVAILABLE" : input.status());
        ensureNoActiveSessionForNonOccupiedStatus(id, status);
        try {
            int changed = jdbc.update("UPDATE PARKING_SLOTS SET SLOT_NUMBER=?,FLOOR=?,SLOT_TYPE=?,STATUS=? WHERE SLOT_ID=?",
                    input.slotNumber().trim().toUpperCase(), input.floor(), input.slotType().trim().toUpperCase(),
                    status, id);
            if (changed == 0) throw missingSlot();
        } catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot number already exists"); }
        return findSlot(id);
    }

    @PatchMapping("/slots/{id}/status")
    @Transactional
    public Map<String, Object> updateStatus(@PathVariable long id, @RequestBody StatusInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required");
        String status = normalizedStatus(input.status());
        findSlotForUpdate(id);
        ensureNoActiveSessionForNonOccupiedStatus(id, status);
        int changed = jdbc.update("UPDATE PARKING_SLOTS SET STATUS=? WHERE SLOT_ID=?", status, id);
        if (changed == 0) throw missingSlot();
        return findSlot(id);
    }

    @DeleteMapping("/slots/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable long id) {
        findSlot(id);
        Integer references = jdbc.queryForObject("SELECT COUNT(*) FROM PARKING_SESSIONS WHERE SLOT_ID=?", Integer.class, id);
        if (references != null && references > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete a slot referenced by a parking session");
        jdbc.update("DELETE FROM PARKING_SLOTS WHERE SLOT_ID=?", id);
    }

    private Map<String, Object> findSlot(long id) {
        try { return jdbc.queryForMap("SELECT SLOT_ID AS \"id\",SLOT_NUMBER AS \"slotNumber\",FLOOR AS \"floor\",SLOT_TYPE AS \"slotType\",STATUS AS \"status\" FROM PARKING_SLOTS WHERE SLOT_ID=?", id); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw missingSlot(); }
    }

    private Map<String, Object> findSlotForUpdate(long id) {
        try {
            return jdbc.queryForMap("SELECT SLOT_ID AS \"id\",SLOT_NUMBER AS \"slotNumber\",FLOOR AS \"floor\",SLOT_TYPE AS \"slotType\",STATUS AS \"status\" FROM PARKING_SLOTS WHERE SLOT_ID=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw missingSlot();
        }
    }

    private void ensureNoActiveSessionForNonOccupiedStatus(long id, String status) {
        if ("OCCUPIED".equals(status)) return;
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM PARKING_SESSIONS WHERE SLOT_ID=? AND SESSION_STATUS='ACTIVE'", Integer.class, id);
        if (active != null && active > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active session requires this slot to remain occupied");
    }

    private ResponseStatusException missingSlot() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking slot not found"); }
    private String normalizedStatus(String status) {
        if (status == null || !STATUSES.contains(status.trim().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be AVAILABLE, OCCUPIED, or RESERVED");
        return status.trim().toUpperCase();
    }
    private void validate(SlotInput input) {
        if (input == null || input.slotNumber() == null || input.slotNumber().isBlank() || input.slotNumber().trim().length() > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot number is required and must be at most 10 characters");
        if (input.floor() == null || input.floor() < 0 || input.floor() > 999)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Floor must be between zero and 999");
        if (input.slotType() == null || input.slotType().isBlank() || input.slotType().trim().length() > 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot type is required and must be at most 20 characters");
        if (!SLOT_TYPES.contains(input.slotType().trim().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot type must be BIKE, CAR, or EV");
        if (input.status() != null) normalizedStatus(input.status());
    }
    public record SlotInput(String slotNumber, Integer floor, String slotType, String status) {}
    public record StatusInput(String status) {}
}
