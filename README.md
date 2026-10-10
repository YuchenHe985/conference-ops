# Conference Ops

A backend for running a conference. It has a schedule, attendee registration with a waitlist, and a finance report. It is written in Java 17 with Spring Boot 3 and PostgreSQL 16.

This began as a 2025 database course project: a 12-table MySQL schema behind PHP pages. In October 2026 it was rebuilt from scratch as a service, around the problems a real event system has:
- many people booking the last seats at once;
- clients that retry after a timeout;
- two organizers editing the same session at the same time.

## What it does

- **Catalog:** rooms, speakers, sponsors (by tier) and attendees. Attendee kinds are student, professional and sponsor, and the fee is derived from the kind. Attendees can be given a login.
- **Schedule:** create, list (by day, paged) and edit sessions.
  - A room or a speaker can never be booked into two overlapping sessions.
  - Back-to-back sessions are allowed.
  - Session capacity cannot exceed the room.
- **Registration:** a seat is confirmed if one is free; otherwise the attendee joins the waitlist.
  - A cancellation hands the seat to the oldest waitlisted registration.
  - Raising a session's capacity promotes from the waitlist at once.
  - Registration closes when the session starts.
- **Finance report:** fees by attendee kind, sponsorship by tier, and the total, read from one consistent snapshot.
- **Operations:**
  - health, liveness and readiness probes;
  - Prometheus metrics, including a registration-outcome counter;
  - optional ECS JSON logs;
  - graceful shutdown;
  - OpenAPI docs at `/swagger-ui.html`;
  - Docker image, docker-compose and a backup script.

## API

| Method | Path | Who |
|---|---|---|
| POST | `/api/rooms`, `/api/speakers`, `/api/sponsors`, `/api/attendees` | organizer |
| POST | `/api/sessions` | organizer |
| PUT | `/api/sessions/{id}` (body carries `version`) | organizer |
| GET | `/api/sessions?day=&page=&size=`, `/api/sessions/{id}` | any user |
| POST | `/api/sessions/{id}/registrations` (optional `Idempotency-Key` header) | organizer, or an attendee for themself |
| DELETE | `/api/registrations/{id}` | organizer, or the owning attendee |
| GET | `/api/sessions/{id}/registrations` | organizer |
| GET | `/api/me/registrations` | any user |
| GET | `/api/reports/finance` | organizer |

Errors use RFC 9457 problem details, plus a stable `code` field, for example:

```json
{"status": 409, "title": "Conflict", "detail": "The room already has a session at that time", "code": "room_double_booked"}
```

Other codes include `speaker_double_booked`, `already_registered`, `session_started`, `stale_version`, `capacity_exceeds_room`, `capacity_below_confirmed`, `idempotency_key_reused` and `validation_failed` (with a `fields` map).

## Design decisions

| Problem | Decision | Trade-off |
|---|---|---|
| Overselling the last seats | Every seat change locks that session's row first (`SELECT … FOR UPDATE`): register, cancel, promote and capacity change. Changes run one at a time per session, while different sessions run in parallel. A `CHECK (confirmed_count BETWEEN 0 AND capacity)` constraint backs this up in the database. | One very popular session serializes its own bookings. That is fine at conference scale. A queue or sharded counters would be next. |
| Deadlocks | Locks are always taken in the same order, session then registration. Waitlist promotion happens under the session lock, so it can be strict FIFO without `SKIP LOCKED`. | Cancelling reads the registration's session first, which costs one extra query. |
| Double-booking rooms and speakers | PostgreSQL exclusion constraints (`EXCLUDE USING gist … tstzrange(starts_at, ends_at) WITH &&`). A check-then-insert in Java could be passed by two requests at the same moment. The constraint name maps to a precise 409. | Ties the schema to PostgreSQL (`btree_gist`). |
| Retries after timeouts | `Idempotency-Key`. The key is claimed with `INSERT … ON CONFLICT DO NOTHING` in the same transaction as the work: a concurrent duplicate waits and then replays the stored response, and a failed attempt leaves no record. Reusing a key for a different request gives 422. Keys expire after 24 h. | One extra row per keyed request. |
| Lost updates between organizers | Optimistic locking: a session has a `version`, an edit must send the version it read, and an old one gets 409. | Clients must re-read and retry. |
| Duplicate registrations | A partial unique index on `(session_id, attendee_id) WHERE status <> 'CANCELLED'`. A cancelled registration does not block registering again. | None worth noting. |
| Rules in two places | Validation in the API, constraints in the schema (`CHECK`, `UNIQUE`, `EXCLUDE`, sponsor ⇔ kind). A bug in one layer cannot store a bad row. | Rules are duplicated; the tests keep them aligned. |
| Schema changes | Flyway migrations. Hibernate only validates (`ddl-auto: validate`). | Every schema change is a hand-written migration. |
| Authentication | Stateless HTTP Basic with BCrypt, so there is no server session and CSRF does not apply. Deny by default: any endpoint not listed needs ORGANIZER. Ownership is checked in the service, not just in the URL rules. The first organizer comes from environment variables; there is no default password. | Basic auth needs TLS in front of it. Single sign-on (OIDC) would replace it in a real deployment. |
| Hot paths in SQL | Seat allocation and reports use plain SQL (JdbcTemplate), so locks and queries are explicit. JPA is used for simple catalog CRUD. | Two data-access styles in one codebase. |

## Running

Requirements: JDK 17. Docker is needed only for the compose setup.

```bash
./gradlew check                  # format check + all tests (embedded PostgreSQL 16, no Docker needed)
cp .env.example .env             # then set real passwords
docker compose up --build        # API on :8080, PostgreSQL 16 with a volume
scripts/backup.sh                # pg_dump (custom format) into backups/
```

Configuration is read from environment variables:
- `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE`
- `BOOTSTRAP_ORGANIZER_USER`, `BOOTSTRAP_ORGANIZER_PASSWORD` (at least 12 characters)
- `LOG_FORMAT=ecs` for JSON logs

## Tests

25 integration tests, all passing (`./gradlew check`, Oct 2026). Each one runs over real HTTP against a real PostgreSQL 16 with the full security chain. JaCoCo coverage is 88% of lines and 68% of branches.

| Area | What is proven |
|---|---|
| Concurrency | 60 simultaneous registrations for 10 seats give exactly 10 confirmed and 50 waitlisted, and the counter matches the rows. Five cancellations racing ten new registrations leave every seat filled and the counter consistent. When the row lock is removed, the 60-request test fails, so the test does detect the race. |
| Waitlist | Strict first-come-first-served promotion on cancel and on a capacity increase. Capacity cannot drop below the confirmed seats. |
| Idempotency | A retry replays the same response (`Idempotent-Replayed: true`) and books once. Ten simultaneous requests with one key create one registration. A reused key with a different body gets 422. A failed attempt does not burn the key. |
| Schedule | Room and speaker overlaps are rejected; back-to-back sessions are allowed. Capacity above the room is rejected. A stale version gets 409. Validation errors are reported per field. Day filtering works. |
| Security | Health is public; everything else gets 401 without a login. Attendees get 403 on organizer endpoints, including the method-level rule. Attendees cannot register or cancel for someone else, and they see only their own registrations. |
| Rules | Duplicate registration gives 409. Registering again after a cancel works. Registration after the start gets 422. Cancelling twice is harmless. Duplicate emails give 409. The finance report totals add up. |

## Limitations

- There is no payment integration. Fees are recorded, not charged.
- A single node with a connection pool. It needs nothing else to scale out, because no state lives in memory, but this has not been load-tested beyond the concurrency tests above.
- There is no UI; the API is documented in OpenAPI.
