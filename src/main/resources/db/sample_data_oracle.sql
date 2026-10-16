-- Repeatable demo seed for a schema created by schema_fresh_oracle.sql or upgraded by phase1_oracle.sql.
-- MERGE inserts only when each unique demo key is absent; it never deletes or overwrites existing rows.
MERGE INTO PARKING_USERS target
USING (SELECT 'DEMO-USER-001' phone, 'Parking Demo User' full_name FROM dual) source
ON (target.PHONE = source.phone)
WHEN NOT MATCHED THEN
    INSERT (USER_ID, FULL_NAME, PHONE, ROLE)
    VALUES (PARKING_USER_SEQ.NEXTVAL, source.full_name, source.phone, 'USER');

MERGE INTO VEHICLES target
USING (
    SELECT USER_ID, 'DEMO-CAR-001' registration_number, 'CAR' vehicle_type
    FROM PARKING_USERS
    WHERE PHONE = 'DEMO-USER-001'
) source
ON (target.REGISTRATION_NUMBER = source.registration_number)
WHEN NOT MATCHED THEN
    INSERT (VEHICLE_ID, USER_ID, REGISTRATION_NUMBER, VEHICLE_TYPE)
    VALUES (VEHICLE_SEQ.NEXTVAL, source.USER_ID, source.registration_number, source.vehicle_type);
