# Smart Parking Allocation System

A Java 25, Spring Boot, Spring JDBC, Oracle, and vanilla JavaScript academic project. It demonstrates parking allocation as a database-backed workflow, deterministic request scheduling, safe concurrent slot claims, versioned inventory edits, audit events, session billing, and operational reports.

This is a local learning prototype, not a production service. It has no authentication or authorization; do not expose it to an untrusted network.

## Architecture and current scope

```mermaid
flowchart LR
    UI[Static HTML / CSS / JavaScript] -->|JSON| C[Spring MVC controllers]
    C --> S[Workflow service]
    S -->|transactions and rules| R[JdbcTemplate repository]
    R -->|bound SQL parameters| O[(Oracle)]
    S --> SCH[FCFS / priority / priority aging]
    S --> BILL[Session-rate billing]
    R --> AUD[Transactional audit events]
    R --> REP[SQL reports]
```

The allocation API is intentionally synchronous. There is no durable background job queue, worker pool, retry state machine, or worker-restart recovery in this version. Allocation outcomes and queue metrics are not fabricated or presented as if a queue existed. The existing concurrency unit test is a controlled Java-thread simulation using a mocked conditional database update; it is not an Oracle locking test.

## Requirements and configuration

- JDK 25
- Maven 3.9+
- Oracle Database with the included Oracle-compatible DDL (the SQL is written to remain compatible with Oracle 11g XE)

The application imports an optional, ignored root-level `application-local.properties`. Set a local Oracle password there, or provide it through `PARKING_DB_PASSWORD`. For example:

```properties
spring.datasource.password=your-local-password
```

Never commit the local properties file or put real credentials in documentation. The connection URL and username are defined in `src/main/resources/application.properties`; the hourly rate for new sessions is configured with `parking.billing.hourly-rate` (default `2.00`).

## Oracle setup and safe migration

The scripts are for different database states; do not run all of them:

1. **Brand-new empty schema:** run `src/main/resources/db/schema_fresh_oracle.sql` once. It creates the full current schema, constraints, sequences, and indexes.
2. **Existing legacy inventory schema:** if it has `PARKING_SLOTS` and `PARKING_SLOT_SEQ` but not the workflow tables, review and run `src/main/resources/db/phase1_oracle.sql` once to add the original workflow schema. Then review and run `src/main/resources/db/phase2_audit_locking_oracle.sql` once.
3. **Existing workflow schema already created from `phase1_oracle.sql`:** back up and inspect the schema, edit the `DEFAULT 2.00` in `phase2_audit_locking_oracle.sql` to match the hourly rate that was in force, then run that migration once.
4. `src/main/resources/db/sample_data_oracle.sql` is an optional repeatable `MERGE` seed for its demo user and vehicle. It does not delete or overwrite records.

The migration adds `PARKING_SLOTS.VERSION_NO`, rate/fee columns on sessions, audit objects, and report-supporting indexes. It does not drop tables, recreate sequences, or reset IDs. The rate default must be reviewed before migrating existing sessions so their rate snapshot is appropriate. Back up first, inspect object names and existing constraints/indexes, and do not rerun one-time DDL. Never run the fresh-schema script over an existing database.

The application does not automatically create or migrate database objects. Oracle SQL was not executed as part of this work; verify the selected script against the actual schema before applying it.

## Build, test, and run

```powershell
mvn test
mvn package
mvn spring-boot:run
```

Open `http://localhost:8080/`. Spring serves the dashboard from `src/main/resources/static/`. `/api/health` does not require Oracle; database-backed endpoints require the reviewed schema and the configured credentials. `/api/database/test` checks the Oracle connection.

## User workflow

1. Add inventory slots, or use existing slots in the database.
2. Register a user, then register a vehicle of type `BIKE`, `CAR`, or `EV`.
3. Submit a request with priority `0`–`100`.
4. Select FCFS, fixed priority, or priority aging on the dashboard. Compare all three pending orders, or allocate the next eligible request / a specific request.
5. Allocation picks the first available slot with an exact matching vehicle type. A successful allocation creates an active session; if no matching slot exists, the request remains pending and the failed attempt is recorded.
6. Release the active session to free its slot and return the duration and billed fee. The hourly rate is snapshotted when the session is created.
7. Review allocation history, recent audit events, and occupancy/revenue/allocation-outcome reports.

## Scheduling policies

- **FCFS:** earliest request timestamp, then lowest request ID.
- **PRIORITY:** highest submitted priority, then earliest timestamp, then lowest request ID.
- **PRIORITY_AGING:** effective priority is `min(100, basePriority + floor(max(waitMinutes, 0) / 5))`. Priority is bounded at 100. Equal effective priorities use earliest request timestamp, then lowest request ID. Aging is computed at scheduling time and does not change the stored base priority.

The API accepts these policy strings in an allocation body. The existing `FCFS` default is preserved when the body or algorithm is omitted. `GET /api/parking/requests/scheduling/simulate` returns request ID orders under all three policies.

## Allocation, transactions, and concurrency

Each synchronous allocation runs in a Spring JDBC transaction. A request is conditionally claimed only while `PENDING`; a candidate slot is conditionally updated only while `AVAILABLE`; session creation and allocation history/audit writes are in the same transaction. The database unique function-based index permits at most one active session per slot across application instances. Failed database writes roll back the related state changes and audit records. Oracle sequence values are non-transactional, so gaps after a rollback are normal.

Slot edits lock the slot row with `SELECT ... FOR UPDATE`, compare the submitted version, and update with `WHERE VERSION_NO=?` while incrementing the version. An edit with an old version returns HTTP 409 instead of silently overwriting a newer edit. The status dashboard sends the version it loaded; API clients must do the same for slot `PUT` and status `PATCH`.

The `ParkingSlotClaimConcurrencyTest` starts competing Java executor tasks against an atomic mock of the repository's conditional claim and verifies one winner. This is a **simulated contract test**, not evidence that Oracle locking was exercised. Oracle-backed race/rollback tests require a dedicated test database and are not included or claimed here.

## Audit and reporting

Audit events cover slot create/edit/status/delete, request creation, allocation attempts (including `NO_SLOT`), and session release. Event rows include event type/time, related request/session/slot IDs where applicable, outcome, and before/after or billing details. They are inserted in the transaction of the operation they describe. Audit rows intentionally do not use foreign keys to entities such as slots so that a slot deletion does not erase its audit history.

`GET /api/parking/audit?limit=50` returns the newest events; `limit` must be between 1 and 200.

`GET /api/parking/reports?from=2026-10-01&to=2026-10-31` returns:

- Daily occupancy snapshots and utilization percentage. Historical days are sampled at midnight at the end of the day; the current day is sampled at query time. Utilization divides by the current slot inventory, so it is a current-capacity comparison rather than a reconstruction of past inventory.
- Daily revenue and closed-session counts grouped by session end date. Stored billed fees are used when present; migrated pre-existing sessions use the stored hourly-rate snapshot and billing formula as a fallback.
- Daily allocation attempt counts grouped by `ALLOCATED` / `NO_SLOT`.

Dates are inclusive, default to the most recent 30 days through today, may not extend into the future, and are limited to 366 days. The report queries use bound parameters. The schema includes indexes for the report date filters and recent audit-event ordering. No performance gain is claimed or measured.

For Oracle plan inspection, use the same query shape and representative bind values in a test/schema environment:

```sql
EXPLAIN PLAN FOR
SELECT SESSION_ID, ENDED_AT
FROM PARKING_SESSIONS
WHERE SESSION_STATUS = 'CLOSED'
  AND ENDED_AT >= TIMESTAMP '2026-10-01 00:00:00'
  AND ENDED_AT <  TIMESTAMP '2026-11-01 00:00:00';

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
```

`EXPLAIN PLAN` records a plan in Oracle's plan table; it is not a runtime benchmark. Check privileges and use `DBMS_XPLAN.DISPLAY_CURSOR` with actual cursor statistics when an execution-level plan is needed.

## API reference

All bodies are JSON unless noted. Slot statuses are `AVAILABLE`, `OCCUPIED`, and `RESERVED`. Slot and vehicle types are `BIKE`, `CAR`, and `EV`.

### Health and inventory

| Method and path | Behavior |
|---|---|
| `GET /api/health` | Application health string; no database query |
| `GET /api/database/test` | Oracle connectivity check |
| `GET /api/parking/stats` | Total/available/occupied/reserved slot counts |
| `GET /api/parking/slots?status=AVAILABLE&floor=0` | Filtered slots, including `version` |
| `GET /api/parking/slots/{id}` | Slot detail and version |
| `POST /api/parking/slots` | Create slot; status defaults to `AVAILABLE` |
| `PUT /api/parking/slots/{id}` | Edit slot using the currently loaded `version` |
| `PATCH /api/parking/slots/{id}/status` | Change status using the currently loaded `version` |
| `DELETE /api/parking/slots/{id}` | Delete only if no session history references the slot |

Example:

```json
POST /api/parking/slots
{
  "slotNumber": "C-01",
  "floor": 0,
  "slotType": "CAR",
  "status": "AVAILABLE"
}
```

Slot updates require a `version`, returned by slot reads. A version 0 edit increments it to 1:

```json
PUT /api/parking/slots/7
{
  "slotNumber": "C-07",
  "floor": 0,
  "slotType": "CAR",
  "status": "AVAILABLE",
  "version": 0
}
```

Status changes use `{"status":"RESERVED","version":1}`. A stale version returns 409 and asks the caller to refresh.

### Users, vehicles, requests, sessions

| Method and path | Behavior |
|---|---|
| `GET /api/parking/users`, `POST /api/parking/users` | List/register a user |
| `GET /api/parking/vehicles`, `POST /api/parking/vehicles` | List/register a vehicle |
| `GET /api/parking/requests`, `POST /api/parking/requests` | List/create a request |
| `GET /api/parking/requests/{id}` | Request detail |
| `POST /api/parking/requests/{id}/allocate` | Allocate a specific request only if it is next eligible under the policy |
| `POST /api/parking/requests/allocate-next` | Allocate the next compatible pending request |
| `GET /api/parking/requests/scheduling/simulate` | Compare FCFS, fixed priority, and aging order |
| `GET /api/parking/sessions/active` | Active sessions with estimated fees |
| `GET /api/parking/sessions/{id}` | Session details and estimated/final fee |
| `POST /api/parking/sessions/{id}/release` | Close session, free slot, persist billed fee |
| `GET /api/parking/history` | Allocation attempts and session history |
| `GET /api/parking/audit?limit=50` | Recent audit events; limit 1–200 |
| `GET /api/parking/reports?from=YYYY-MM-DD&to=YYYY-MM-DD` | Occupancy, revenue, and allocation outcomes |

Example registration and allocation:

```json
POST /api/parking/users
{ "fullName": "Demo Driver", "phone": "555-0100" }

POST /api/parking/vehicles
{ "userId": 1, "registrationNumber": "ABC-123", "vehicleType": "CAR" }

POST /api/parking/requests
{ "vehicleId": 1, "priority": 20 }

POST /api/parking/requests/allocate-next
{ "algorithm": "PRIORITY_AGING" }
```

Successful allocation returns `allocated`, `requestId`, `sessionId`, `slot`, and `algorithm`. Release returns `durationSeconds`, `chargedHours`, `hourlyRate`, and `fee`. Any positive partial hour rounds up; a zero-duration session is free.

Example allocation response:

```json
{
  "allocated": true,
  "requestId": 14,
  "sessionId": 22,
  "slot": { "id": 7, "slotNumber": "C-01", "floor": 0, "slotType": "CAR" },
  "algorithm": "PRIORITY_AGING"
}
```

The scheduling comparison response has `fcfs`, `priority`, and `priorityAging` arrays of request IDs. Reports return `from`, `to`, `occupancy`, `revenue`, and `allocationOutcomes`; each daily occupancy row includes occupied/total slot counts and `utilizationPercent`.

### Validation and errors

Invalid input returns HTTP 400, missing records HTTP 404, and stale versions, already-processed requests, uniqueness failures, scheduling conflicts, or unavailable compatible slots HTTP 409. Errors use Spring's HTTP error response with a useful `detail` when available. Request priority is 0–100; user names, phone values, registration numbers, slot numbers, floor values, and policy names are checked at the API boundary.

## Database model and OS/DBMS concepts

- **Operating systems:** FCFS and non-preemptive priority scheduling, bounded priority aging, deterministic tie-breaking, and a barrier-coordinated multi-thread contention simulation.
- **DBMS:** normalized users/vehicles/requests/sessions/history, Oracle sequences and constraints, bound SQL parameters, transactions, `SELECT ... FOR UPDATE`, compare-and-increment optimistic versions, conditional updates, a function-based unique index, audit trail, aggregate reports, and query-plan inspection.
- **Billing:** session rate is captured at allocation; release computes and persists the final fee, so later rate configuration changes do not rewrite recorded revenue.

The existing synchronous API remains the only allocation path. Durable queueing, retries/recovery, cancellation, payments, role-based access control, and production deployment hardening remain out of scope.

## Tests and verification limits

The Maven test suite covers FCFS/fixed-priority ordering, aging and deterministic ties, billing rounding/invalid intervals, slot validation and optimistic stale-version conflicts, existing endpoint contracts, and the simulated competing slot claim. The repository has no configured Oracle integration-test profile or dedicated test database. Do not point tests at a production or unknown database.

No Oracle setup/migration, live endpoint workflow, Oracle row-locking race, or audit rollback was exercised without a configured test database. Database-specific DDL and SQL must be validated against the target Oracle version/schema before deployment.

## Troubleshooting

- Connection refused: confirm Oracle is running at the configured JDBC URL and the service name is correct.
- Authentication failure: check local `application-local.properties` or `PARKING_DB_PASSWORD`; do not commit credentials.
- Missing table/column/sequence: review schema state and apply only the correct one-time setup/migration.
- `ORA-00001` on allocation: the unique active-slot constraint prevented conflicting claims; refresh request/slot state.
- HTTP 409 on slot edit: reload slots and resubmit using the latest version.
- HTTP 409 on allocation: another request may be ahead under the selected policy, or no compatible slot remains.
- Reports empty for revenue: only closed sessions contribute; reports group revenue by session end date.
