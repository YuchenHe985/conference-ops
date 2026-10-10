-- Conference operations schema. Business rules live here as well as in the service layer, so a bug in
-- one layer still cannot store an oversold session, a double-booked room or a duplicate registration.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE room (
    id        BIGSERIAL PRIMARY KEY,
    name      TEXT NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 80),
    capacity  INT  NOT NULL CHECK (capacity > 0)
);

CREATE TABLE speaker (
    id         BIGSERIAL PRIMARY KEY,
    full_name  TEXT NOT NULL CHECK (length(full_name) BETWEEN 1 AND 120),
    email      TEXT NOT NULL UNIQUE CHECK (email = lower(email))
);

CREATE TABLE sponsor (
    id            BIGSERIAL PRIMARY KEY,
    company       TEXT   NOT NULL UNIQUE CHECK (length(company) BETWEEN 1 AND 120),
    tier          TEXT   NOT NULL CHECK (tier IN ('PLATINUM', 'GOLD', 'SILVER', 'BRONZE')),
    amount_cents  BIGINT NOT NULL CHECK (amount_cents >= 0)
);

CREATE TABLE attendee (
    id          BIGSERIAL PRIMARY KEY,
    full_name   TEXT   NOT NULL CHECK (length(full_name) BETWEEN 1 AND 120),
    email       TEXT   NOT NULL UNIQUE CHECK (email = lower(email)),
    kind        TEXT   NOT NULL CHECK (kind IN ('STUDENT', 'PROFESSIONAL', 'SPONSOR')),
    sponsor_id  BIGINT REFERENCES sponsor (id) ON DELETE RESTRICT,
    fee_cents   BIGINT NOT NULL CHECK (fee_cents >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'SPONSOR') = (sponsor_id IS NOT NULL))
);

CREATE TABLE session (
    id               BIGSERIAL PRIMARY KEY,
    title            TEXT   NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    room_id          BIGINT NOT NULL REFERENCES room (id) ON DELETE RESTRICT,
    speaker_id       BIGINT REFERENCES speaker (id) ON DELETE RESTRICT,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    capacity         INT    NOT NULL CHECK (capacity > 0),
    confirmed_count  INT    NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    -- the last line of defence against overselling, whatever the application does
    CONSTRAINT session_not_oversold CHECK (confirmed_count BETWEEN 0 AND capacity),
    -- a room or a speaker can never be in two overlapping sessions; [start, end) so back-to-back is fine
    CONSTRAINT session_room_no_overlap
        EXCLUDE USING gist (room_id WITH =, tstzrange(starts_at, ends_at) WITH &&),
    CONSTRAINT session_speaker_no_overlap
        EXCLUDE USING gist (speaker_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
);
CREATE INDEX session_starts_at ON session (starts_at);

CREATE TABLE registration (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT NOT NULL REFERENCES session (id) ON DELETE RESTRICT,
    attendee_id  BIGINT NOT NULL REFERENCES attendee (id) ON DELETE RESTRICT,
    status       TEXT   NOT NULL CHECK (status IN ('CONFIRMED', 'WAITLISTED', 'CANCELLED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- one live registration per attendee and session; a cancelled one does not block registering again
CREATE UNIQUE INDEX registration_one_live
    ON registration (session_id, attendee_id) WHERE status <> 'CANCELLED';
-- the waitlist is served first come, first served
CREATE INDEX registration_waitlist
    ON registration (session_id, id) WHERE status = 'WAITLISTED';
CREATE INDEX registration_attendee ON registration (attendee_id);

CREATE TABLE app_user (
    id             BIGSERIAL PRIMARY KEY,
    username       TEXT NOT NULL UNIQUE CHECK (username = lower(username)),
    password_hash  TEXT NOT NULL,
    role           TEXT NOT NULL CHECK (role IN ('ORGANIZER', 'ATTENDEE')),
    attendee_id    BIGINT UNIQUE REFERENCES attendee (id) ON DELETE CASCADE,
    CHECK ((role = 'ATTENDEE') = (attendee_id IS NOT NULL))
);

-- Idempotency-Key support: a retried POST replays the stored response instead of acting twice.
CREATE TABLE idempotency_record (
    username      TEXT NOT NULL,
    idem_key      TEXT NOT NULL CHECK (length(idem_key) BETWEEN 1 AND 100),
    request_hash  TEXT NOT NULL,
    status_code   INT,
    response_body TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (username, idem_key)
);
CREATE INDEX idempotency_record_created ON idempotency_record (created_at);
