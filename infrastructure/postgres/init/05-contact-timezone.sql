-- EmailContact.lastActivityAt es un Instant: Hibernate 6 lo espera como
-- "timestamp with time zone". Las bases creadas antes de esta migración tienen
-- TIMESTAMP sin zona y con ddl-auto=validate el backend no arranca.
ALTER TABLE email_contact
    ALTER COLUMN last_activity_at TYPE TIMESTAMPTZ;
