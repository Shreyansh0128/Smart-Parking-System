package com.smartparking.repository;

import com.smartparking.service.ParkingRequestScheduler.ScheduledRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
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
        return createRequest(vehicleId, priority, null);
    }

    public long createRequest(long vehicleId, int priority, Integer expectedDurationMinutes) {
        long id = nextId(SequenceName.PARKING_REQUEST_SEQ);
        jdbc.update("INSERT INTO PARKING_REQUESTS " +
                        "(REQUEST_ID,VEHICLE_ID,REQUESTED_AT,PRIORITY,REQUEST_STATUS,EXPECTED_DURATION_MINUTES) " +
                        "VALUES (?,?,SYS_EXTRACT_UTC(SYSTIMESTAMP),?,'PENDING',?)",
                id, vehicleId, priority, expectedDurationMinutes);
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
        return jdbc.update("UPDATE PARKING_SLOTS SET STATUS='OCCUPIED',VERSION_NO=VERSION_NO+1 " +
                "WHERE SLOT_ID=? AND STATUS='AVAILABLE'", slotId);
    }

    public long createSession(long requestId, long vehicleId, long slotId, BigDecimal hourlyRate) {
        long id = nextId(SequenceName.PARKING_SESSION_SEQ);
        jdbc.update("INSERT INTO PARKING_SESSIONS " +
                    "(SESSION_ID,REQUEST_ID,VEHICLE_ID,SLOT_ID,STARTED_AT,EXPECTED_END_AT,SESSION_STATUS,HOURLY_RATE) " +
                    "SELECT ?,?,?,?,SYS_EXTRACT_UTC(SYSTIMESTAMP)," +
                    "CASE WHEN R.EXPECTED_DURATION_MINUTES IS NULL THEN NULL ELSE " +
                    "SYS_EXTRACT_UTC(SYSTIMESTAMP)+NUMTODSINTERVAL(R.EXPECTED_DURATION_MINUTES,'MINUTE') END," +
                    "'ACTIVE',? FROM PARKING_REQUESTS R WHERE R.REQUEST_ID=?",
            id, requestId, vehicleId, slotId, hourlyRate, requestId);
        return id;
    }

    public void recordAllocation(long requestId, Long slotId, String outcome, String details) {
        long id = nextId(SequenceName.ALLOCATION_HISTORY_SEQ);
        jdbc.update("INSERT INTO ALLOCATION_HISTORY (HISTORY_ID,REQUEST_ID,SLOT_ID,ALLOCATED_AT,OUTCOME,DETAILS) VALUES (?,?,?,SYS_EXTRACT_UTC(SYSTIMESTAMP),?,?)",
                id, requestId, slotId, outcome, details);
    }

    public void recordAudit(String eventType, Long requestId, Long sessionId, Long slotId,
                            String outcome, String details) {
        jdbc.update("INSERT INTO PARKING_AUDIT_EVENTS " +
                        "(EVENT_ID,EVENT_TYPE,EVENT_AT,REQUEST_ID,SESSION_ID,SLOT_ID,OUTCOME,DETAILS) " +
                        "VALUES (PARKING_AUDIT_SEQ.NEXTVAL,?,SYS_EXTRACT_UTC(SYSTIMESTAMP),?,?,?,?,?)",
                eventType, requestId, sessionId, slotId, outcome, details);
    }

    public List<Map<String, Object>> auditEvents(int limit) {
        return jdbc.queryForList("SELECT * FROM (" +
                "SELECT EVENT_ID AS \"id\",EVENT_TYPE AS \"eventType\",EVENT_AT AS \"eventAt\"," +
                "REQUEST_ID AS \"requestId\",SESSION_ID AS \"sessionId\",SLOT_ID AS \"slotId\"," +
                "OUTCOME AS \"outcome\",DETAILS AS \"details\" FROM PARKING_AUDIT_EVENTS " +
                "ORDER BY EVENT_AT DESC,EVENT_ID DESC) WHERE ROWNUM <= ?", limit);
    }

    public List<Map<String, Object>> occupancyReport(Date from, int dayCount) {
        return jdbc.queryForList("WITH report_days AS (" +
                        "SELECT TRUNC(?) + LEVEL - 1 AS report_day FROM dual CONNECT BY LEVEL <= ?)," +
                        "day_cutoffs AS (SELECT report_day,CASE WHEN report_day+1>SYSDATE " +
                        "THEN CAST(SYSTIMESTAMP AS TIMESTAMP) ELSE CAST(report_day+1 AS TIMESTAMP) END AS cutoff FROM report_days) " +
                        "SELECT TO_CHAR(d.report_day,'YYYY-MM-DD') AS \"day\"," +
                        "COUNT(DISTINCT s.SESSION_ID) AS \"occupiedSlots\"," +
                        "(SELECT COUNT(*) FROM PARKING_SLOTS) AS \"totalSlots\"," +
                        "CASE WHEN (SELECT COUNT(*) FROM PARKING_SLOTS)=0 THEN 0 ELSE " +
                        "ROUND(100*COUNT(DISTINCT s.SESSION_ID)/(SELECT COUNT(*) FROM PARKING_SLOTS),2) END " +
                        "AS \"utilizationPercent\" FROM day_cutoffs d LEFT JOIN PARKING_SESSIONS s " +
                        "ON s.STARTED_AT<d.cutoff AND (s.ENDED_AT IS NULL OR s.ENDED_AT>d.cutoff) " +
                        "GROUP BY d.report_day ORDER BY d.report_day",
                from, dayCount);
    }

    public List<Map<String, Object>> revenueReport(Date from, Date toExclusive) {
        return jdbc.queryForList("SELECT TO_CHAR(TRUNC(ENDED_AT),'YYYY-MM-DD') AS \"day\"," +
                        "COUNT(*) AS \"closedSessions\",ROUND(SUM(NVL(BILLED_FEE,CASE WHEN ENDED_AT=STARTED_AT THEN 0 ELSE " +
                        "CEIL((EXTRACT(DAY FROM (ENDED_AT-STARTED_AT))*86400+" +
                        "EXTRACT(HOUR FROM (ENDED_AT-STARTED_AT))*3600+" +
                        "EXTRACT(MINUTE FROM (ENDED_AT-STARTED_AT))*60+" +
                        "EXTRACT(SECOND FROM (ENDED_AT-STARTED_AT)))/3600)*HOURLY_RATE END)),2) AS \"revenue\" " +
                        "FROM PARKING_SESSIONS WHERE SESSION_STATUS='CLOSED' AND ENDED_AT>=? AND ENDED_AT<? " +
                        "GROUP BY TRUNC(ENDED_AT) ORDER BY TRUNC(ENDED_AT)",
                from, toExclusive);
    }

    public List<Map<String, Object>> allocationOutcomeReport(Date from, Date toExclusive) {
        return jdbc.queryForList("SELECT TO_CHAR(TRUNC(ALLOCATED_AT),'YYYY-MM-DD') AS \"day\"," +
                        "OUTCOME AS \"outcome\",COUNT(*) AS \"count\" FROM ALLOCATION_HISTORY " +
                        "WHERE ALLOCATED_AT>=? AND ALLOCATED_AT<? GROUP BY TRUNC(ALLOCATED_AT),OUTCOME " +
                        "ORDER BY TRUNC(ALLOCATED_AT),OUTCOME",
                from, toExclusive);
    }

    public Map<String, Object> activeSession(long sessionId) {
        try {
            return jdbc.queryForMap("SELECT SESSION_ID AS \"id\",REQUEST_ID AS \"requestId\",VEHICLE_ID AS \"vehicleId\",SLOT_ID AS \"slotId\",STARTED_AT AS \"startedAt\",EXPECTED_END_AT AS \"expectedEndAt\",HOURLY_RATE AS \"hourlyRate\" FROM PARKING_SESSIONS WHERE SESSION_ID=? AND SESSION_STATUS='ACTIVE' FOR UPDATE",
                    sessionId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public int closeSession(long sessionId) {
        return jdbc.update("UPDATE PARKING_SESSIONS SET SESSION_STATUS='CLOSED',ENDED_AT=SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE SESSION_ID=? AND SESSION_STATUS='ACTIVE'",
                sessionId);
    }

    public int saveSessionFee(long sessionId, BigDecimal fee) {
        return jdbc.update("UPDATE PARKING_SESSIONS SET BILLED_FEE=? WHERE SESSION_ID=? AND SESSION_STATUS='CLOSED' AND BILLED_FEE IS NULL",
                fee, sessionId);
    }

    public int releaseSlot(long slotId) {
        return jdbc.update("UPDATE PARKING_SLOTS SET STATUS='AVAILABLE',VERSION_NO=VERSION_NO+1 " +
                "WHERE SLOT_ID=? AND STATUS='OCCUPIED'", slotId);
    }

    public List<Map<String, Object>> activeSessions() {
        return jdbc.queryForList("SELECT S.SESSION_ID AS \"id\",S.REQUEST_ID AS \"requestId\",S.VEHICLE_ID AS \"vehicleId\",S.SLOT_ID AS \"slotId\",S.STARTED_AT AS \"startedAt\",S.EXPECTED_END_AT AS \"expectedEndAt\",S.HOURLY_RATE AS \"hourlyRate\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\",P.SLOT_NUMBER AS \"slotNumber\",P.FLOOR AS \"floor\" FROM PARKING_SESSIONS S JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID JOIN PARKING_SLOTS P ON P.SLOT_ID=S.SLOT_ID WHERE S.SESSION_STATUS='ACTIVE' ORDER BY S.STARTED_AT,S.SESSION_ID");
    }

    public Map<String, Object> session(long sessionId) {
        try {
            return jdbc.queryForMap("SELECT S.SESSION_ID AS \"id\",S.REQUEST_ID AS \"requestId\",S.VEHICLE_ID AS \"vehicleId\",S.SLOT_ID AS \"slotId\",S.STARTED_AT AS \"startedAt\",S.EXPECTED_END_AT AS \"expectedEndAt\",S.ENDED_AT AS \"endedAt\",S.SESSION_STATUS AS \"status\",S.HOURLY_RATE AS \"hourlyRate\",S.BILLED_FEE AS \"billedFee\",V.REGISTRATION_NUMBER AS \"registrationNumber\",V.VEHICLE_TYPE AS \"vehicleType\",P.SLOT_NUMBER AS \"slotNumber\",P.FLOOR AS \"floor\" FROM PARKING_SESSIONS S JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID JOIN PARKING_SLOTS P ON P.SLOT_ID=S.SLOT_ID WHERE S.SESSION_ID=?",
                    sessionId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT H.HISTORY_ID AS \"id\",H.REQUEST_ID AS \"requestId\",H.SLOT_ID AS \"slotId\",H.ALLOCATED_AT AS \"allocatedAt\",H.OUTCOME AS \"outcome\",H.DETAILS AS \"details\",S.SESSION_ID AS \"sessionId\",S.STARTED_AT AS \"startedAt\",S.ENDED_AT AS \"endedAt\",S.SESSION_STATUS AS \"sessionStatus\",V.REGISTRATION_NUMBER AS \"registrationNumber\",P.SLOT_NUMBER AS \"slotNumber\" FROM ALLOCATION_HISTORY H LEFT JOIN PARKING_SESSIONS S ON S.REQUEST_ID=H.REQUEST_ID LEFT JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID LEFT JOIN PARKING_SLOTS P ON P.SLOT_ID=H.SLOT_ID ORDER BY H.ALLOCATED_AT DESC,H.HISTORY_ID DESC");
    }

    public int extendSession(long sessionId, int additionalMinutes) {
        return jdbc.update("UPDATE PARKING_SESSIONS SET EXPECTED_END_AT=" +
                        "CASE WHEN EXPECTED_END_AT IS NULL OR EXPECTED_END_AT<SYS_EXTRACT_UTC(SYSTIMESTAMP) " +
                        "THEN SYS_EXTRACT_UTC(SYSTIMESTAMP)+NUMTODSINTERVAL(?,'MINUTE') " +
                        "ELSE EXPECTED_END_AT+NUMTODSINTERVAL(?,'MINUTE') END " +
                        "WHERE SESSION_ID=? AND SESSION_STATUS='ACTIVE'",
                additionalMinutes, additionalMinutes, sessionId);
    }

    public List<Map<String, Object>> notifications(boolean unreadOnly, int offset, int limit) {
        String unread = unreadOnly ? " AND ACKNOWLEDGED_AT IS NULL" : "";
        return jdbc.queryForList("SELECT * FROM (SELECT N.NOTIFICATION_ID AS \"id\",N.EVENT_KEY AS \"eventKey\"," +
                        "N.EVENT_TYPE AS \"eventType\",N.MESSAGE AS \"message\",N.CREATED_AT AS \"createdAt\"," +
                        "N.SESSION_ID AS \"sessionId\",N.ACKNOWLEDGED_AT AS \"acknowledgedAt\"," +
                        "ROW_NUMBER() OVER (ORDER BY N.CREATED_AT DESC,N.NOTIFICATION_ID DESC) AS \"rowNumber\" " +
                        "FROM PARKING_NOTIFICATIONS N WHERE 1=1" + unread +
                        ") WHERE \"rowNumber\">? AND \"rowNumber\"<=? ORDER BY \"rowNumber\"",
                offset, offset + limit);
    }

    public int unreadNotificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PARKING_NOTIFICATIONS WHERE ACKNOWLEDGED_AT IS NULL",
                Integer.class);
    }

    public int acknowledgeNotification(long id) {
        return jdbc.update("UPDATE PARKING_NOTIFICATIONS SET ACKNOWLEDGED_AT=SYS_EXTRACT_UTC(SYSTIMESTAMP) " +
                "WHERE NOTIFICATION_ID=? AND ACKNOWLEDGED_AT IS NULL", id);
    }

    public Map<String, Object> notification(long id) {
        try {
            return jdbc.queryForMap("SELECT NOTIFICATION_ID AS \"id\",SESSION_ID AS \"sessionId\"," +
                    "ACKNOWLEDGED_AT AS \"acknowledgedAt\" FROM PARKING_NOTIFICATIONS WHERE NOTIFICATION_ID=?", id);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    public List<Map<String, Object>> sessionsNeedingNotifications(int upcomingMinutes, int unknownReminderMinutes) {
        return jdbc.queryForList("SELECT S.SESSION_ID AS \"sessionId\",S.EXPECTED_END_AT AS \"expectedEndAt\"," +
                        "S.STARTED_AT AS \"startedAt\",V.REGISTRATION_NUMBER AS \"registrationNumber\"," +
                        "P.SLOT_NUMBER AS \"slotNumber\" FROM PARKING_SESSIONS S " +
                        "JOIN VEHICLES V ON V.VEHICLE_ID=S.VEHICLE_ID JOIN PARKING_SLOTS P ON P.SLOT_ID=S.SLOT_ID " +
                        "WHERE S.SESSION_STATUS='ACTIVE' AND (" +
                        "(S.EXPECTED_END_AT IS NOT NULL AND S.EXPECTED_END_AT<=SYS_EXTRACT_UTC(SYSTIMESTAMP)+" +
                        "NUMTODSINTERVAL(?,'MINUTE')) OR " +
                        "(S.EXPECTED_END_AT IS NULL AND S.STARTED_AT<=SYS_EXTRACT_UTC(SYSTIMESTAMP)-" +
                        "NUMTODSINTERVAL(?,'MINUTE')))",
                upcomingMinutes, unknownReminderMinutes);
    }

    public int createNotificationIfAbsent(String eventKey, String eventType, String message, long sessionId) {
        try {
            return jdbc.update("MERGE INTO PARKING_NOTIFICATIONS N USING (SELECT ? EVENT_KEY FROM dual) S " +
                            "ON (N.EVENT_KEY=S.EVENT_KEY) WHEN NOT MATCHED THEN INSERT " +
                            "(NOTIFICATION_ID,EVENT_KEY,EVENT_TYPE,MESSAGE,CREATED_AT,SESSION_ID) " +
                            "VALUES (PARKING_NOTIFICATION_SEQ.NEXTVAL,S.EVENT_KEY,?,?,SYS_EXTRACT_UTC(SYSTIMESTAMP),?)",
                    eventKey, eventType, message, sessionId);
        } catch (DuplicateKeyException duplicateEvent) {
            return 0;
        }
    }

    public static ScheduledRequest scheduledRequest(Map<String, Object> row) {
        Object time = row.get("requestedAt");
        Timestamp timestamp = (Timestamp) time;
        return new ScheduledRequest(((Number) row.get("id")).longValue(),
                ((Number) row.get("priority")).intValue(), timestamp.toLocalDateTime());
    }
}
