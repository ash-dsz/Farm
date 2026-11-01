-- Phone + password accounts (OTP and server-side PIN login are no longer used by the app).
-- The offline PIN now lives only on the phone. Old PIN columns are kept, unused, so no data is lost.
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN login_failed_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN login_locked_until TIMESTAMPTZ;
