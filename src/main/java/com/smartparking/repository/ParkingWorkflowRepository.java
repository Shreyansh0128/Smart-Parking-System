package com.smartparking.repository;

import com.smartparking.service.ParkingRequestScheduler.ScheduledRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class ParkingWorkflowRepository {
    private enum SequenceName {
        PARKING_USER_SEQ, VEHICLE_SEQ, PARKING_REQUEST_SEQ, PARKING_SESSION_SEQ, ALLOCATION_HISTORY_SEQ
    }

    private final JdbcTemplate jdbc;

    public ParkingWorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long nextId(SequenceName sequence) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + sequence.name() + ".NEXTVAL FROM dual", Long.class),
                "Oracle sequence returned NULL");
    }

    public long createUser(String name, String phone) {
        long id = nextId(SequenceName.PARKING_USER_SEQ);
        jdbc.update("INSERT INTO PARKING_USERS (USER_ID,FULL_NAME,PHONE) VALUES (?,?,?)", id, name, phone);
        return id;
    }

    public List<Map<String, Object>> users() {
        return jdbc.queryForList("SELECT USER_ID AS \"id\",FULL_NAME AS \"fullName\",PHONE AS \"phone\",ROLE AS \"role\" FROM PARKING_USERS ORDER BY USER_ID");
    }

    public Map<String, Object> user(long id) {
        try {
            return jdbc.queryForMap("SELECT USER_ID AS \"id\",FULL_NAME AS \"fullName\",PHONE AS \"phone\",ROLE AS \"role\" FROM PARKING_USERS WHERE USER_ID=?", id);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public long createVehicle(long userId, String registration, String type) {
        long id = nextId(SequenceName.VEHICLE_SEQ);
        jdbc.update("INSERT INTO VEHICLES (VEHICLE_ID,USER_ID,REGISTRATION_NUMBER,VEHICLE_TYPE) VALUES (?,?,?,?)",
                id, userId, registration, type);
        return id;
    }

    public List<Map<String, Object>> vehicles() {
        return jdbc.queryForList("SELECT V.VEHICLE_ID AS \"id\",V.USER_ID AS \"userId\",U.FULL_NAME AS \"ownerName\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\" FROM VEHICLES V JOIN PARKING_USERS U ON U.USER_ID=V.USER_ID ORDER BY V.VEHICLE_ID");
    }

    public Map<String, Object> vehicle(long id) {
        try {
            return jdbc.queryForMap("SELECT VEHICLE_ID AS \"id\",USER_ID AS \"userId\",REGISTRATION_NUMBER AS \"registrationNumber\",VEHICLE_TYPE AS \"vehicleType\" FROM VEHICLES WHERE VEHICLE_ID=?", id);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public long createRequest(long vehicleId, int priority) {
        long id = nextId(SequenceName.PARKING_REQUEST_SEQ);
        jdbc.update("INSERT INTO PARKING_REQUESTS (REQUEST_ID,VEHICLE_ID,PRIORITY,REQUEST_STATUS) VALUES (?,?,?,'PENDING')",
                id, vehicleId, priority);
        return id;
    }

    public Map<String, Object> request(long id) {
        try {
            return jdbc.queryForMap("SELECT R.REQUEST_ID AS \"id\",R.VEHICLE_ID AS \"vehicleId\",R.REQUESTED_AT AS \"requestedAt\",R.PRIORITY AS \"priority\",R.REQUEST_STATUS AS \"status\",R.SCHEDULING_ALGORITHM AS \"algorithm\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\" FROM PARKING_REQUESTS R JOIN VEHICLES V ON V.VEHICLE_ID=R.VEHICLE_ID WHERE R.REQUEST_ID=?",
                    id);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public List<Map<String, Object>> requests() {
        return jdbc.queryForList("SELECT R.REQUEST_ID AS \"id\",R.VEHICLE_ID AS \"vehicleId\",R.REQUESTED_AT AS \"requestedAt\",R.PRIORITY AS \"priority\",R.REQUEST_STATUS AS \"status\",R.SCHEDULING_ALGORITHM AS \"algorithm\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\" FROM PARKING_REQUESTS R JOIN VEHICLES V ON V.VEHICLE_ID=R.VEHICLE_ID ORDER BY R.REQUESTED_AT,R.REQUEST_ID");
    }

    public List<Map<String, Object>> pendingForScheduling() {
        return jdbc.queryForList("SELECT REQUEST_ID AS \"id\",PRIORITY AS \"priority\",REQUESTED_AT AS \"requestedAt\",REQUEST_STATUS AS \"status\" FROM PARKING_REQUESTS WHERE REQUEST_STATUS='PENDING'");
    }

    public int claimRequest(long requestId, String algorithm) {
        return jdbc.update("UPDATE PARKING_REQUESTS SET REQUEST_STATUS='ALLOCATED',SCHEDULING_ALGORITHM=? WHERE REQUEST_ID=? AND REQUEST_STATUS='PENDING'",
                algorithm, requestId);
    }

    public void returnRequestToPending(long requestId) {
        jdbc.update("UPDATE PARKING_REQUESTS SET REQUEST_STATUS='PENDING' WHERE REQUEST_ID=? AND REQUEST_STATUS='ALLOCATED'",
                requestId);
    }

    public List<Map<String, Object>> availableSlots(String type) {
        return jdbc.queryForList("SELECT SLOT_ID AS \"id\",SLOT_NUMBER AS \"slotNumber\",FLOOR AS \"floor\",SLOT_TYPE AS \"slotType\" FROM PARKING_SLOTS WHERE STATUS='AVAILABLE' AND SLOT_TYPE=? ORDER BY FLOOR,SLOT_NUMBER",
                type);
    }

    public int occupyIfAvailable(long slotId) {
        return jdbc.update("UPDATE PARKING_SLOTS SET STATUS='OCCUPIED' WHERE SLOT_ID=? AND STATUS='AVAILABLE'", slotId);
    }

    public long createSession(long requestId, long vehicleId, long slotId) {
        long id = nextId(SequenceName.PARKING_SESSION_SEQ);
        jdbc.update("INSERT INTO PARKING_SESSIONS (SESSION_ID,REQUEST_ID,VEHICLE_ID,SLOT_ID,STARTED_AT,SESSION_STATUS) VALUES (?,?,?,?,SYSTIMESTAMP,'ACTIVE')",
                id, requestId, vehicleId, slotId);
        return id;
    }

    public void recordAllocation(long requestId, Long slotId, String outcome, String details) {
        long id = nextId(SequenceName.ALLOCATION_HISTORY_SEQ);
        jdbc.update("INSERT INTO ALLOCATION_HISTORY (HISTORY_ID,REQUEST_ID,SLOT_ID,ALLOCATED_AT,OUTCOME,DETAILS) VALUES (?,?,?,SYSTIMESTAMP,?,?)",
                id, requestId, slotId, outcome, details);
    }

    public Map<String, Object> activeSession(long sessionId) {
        try {
            return jdbc.queryForMap("SELECT SESSION_ID AS \"id\",REQUEST_ID AS \"requestId\",VEHICLE_ID AS \"vehicleId\",SLOT_ID AS \"slotId\",STARTED_AT AS \"startedAt\" FROM PARKING_SESSIONS WHERE SESSION_ID=? AND SESSION_STATUS='ACTIVE' FOR UPDATE",
                    sessionId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public int closeSession(long sessionId) {
        return jdbc.update("UPDATE PARKING_SESSIONS SET SESSION_STATUS='CLOSED',ENDED_AT=SYSTIMESTAMP WHERE SESSION_ID=? AND SESSION_STATUS='ACTIVE'",
                sessionId);
    }

    public int releaseSlot(long slotId) {
        return jdbc.update("UPDATE PARKING_SLOTS SET STATUS='AVAILABLE' WHERE SLOT_ID=? AND STATUS='OCCUPIED'", slotId);
    }

    public List<Map<String, Object>> activeSessions() {
        return jdbc.queryForList("SELECT S.SESSION_ID AS \"id\",S.REQUEST_ID AS \"requestId\",S.VEHICLE_ID AS \"vehicleId\",S.SLOT_ID AS \"slotId\",S.STARTED_AT AS \"startedAt\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\",P.SLOT_NUMBER AS \"slotNumber\",P.FLOOR AS \"floor\" FROM PARKING_SESSIONS S JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID JOIN PARKING_SLOTS P ON P.SLOT_ID=S.SLOT_ID WHERE S.SESSION_STATUS='ACTIVE' ORDER BY S.STARTED_AT,S.SESSION_ID");
    }

    public Map<String, Object> session(long sessionId) {
        try {
            return jdbc.queryForMap("SELECT S.SESSION_ID AS \"id\",S.REQUEST_ID AS \"requestId\",S.VEHICLE_ID AS \"vehicleId\",S.SLOT_ID AS \"slotId\",S.STARTED_AT AS \"startedAt\",S.ENDED_AT AS \"endedAt\",S.SESSION_STATUS AS \"status\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\",P.SLOT_NUMBER AS \"slotNumber\",P.FLOOR AS \"floor\" FROM PARKING_SESSIONS S JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID JOIN PARKING_SLOTS P ON P.SLOT_ID=S.SLOT_ID WHERE S.SESSION_ID=?",
                    sessionId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT H.HISTORY_ID AS \"id\",H.REQUEST_ID AS \"requestId\",H.SLOT_ID AS \"slotId\",H.ALLOCATED_AT AS \"allocatedAt\",H.OUTCOME AS \"outcome\",H.DETAILS AS \"details\",S.SESSION_ID AS \"sessionId\",S.STARTED_AT AS \"startedAt\",S.ENDED_AT AS \"endedAt\",S.SESSION_STATUS AS \"sessionStatus\",V.REGISTRATION_NUMBER AS \"registrationNumber\",P.SLOT_NUMBER AS \"slotNumber\" FROM ALLOCATION_HISTORY H LEFT JOIN PARKING_SESSIONS S ON S.REQUEST_ID=H.REQUEST_ID LEFT JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID LEFT JOIN PARKING_SLOTS P ON P.SLOT_ID=H.SLOT_ID ORDER BY H.ALLOCATED_AT DESC,H.HISTORY_ID DESC");
    }

    public static ScheduledRequest scheduledRequest(Map<String, Object> row) {
        Object time = row.get("requestedAt");
        Timestamp timestamp = (Timestamp) time;
        return new ScheduledRequest(((Number) row.get("id")).longValue(),
                ((Number) row.get("priority")).intValue(), timestamp.toLocalDateTime());
    }
}
