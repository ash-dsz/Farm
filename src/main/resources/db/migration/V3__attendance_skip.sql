-- SKIP: the worker was not expected that day (not every worker comes daily).
-- Counts as 0 days like ABSENT, but is not an absence and the day counts as marked.
ALTER TABLE attendance DROP CONSTRAINT attendance_status_check;
ALTER TABLE attendance ADD CONSTRAINT attendance_status_check
    CHECK (status IN ('PRESENT', 'HALF_DAY', 'ABSENT', 'SKIP'));
