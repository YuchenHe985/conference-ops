package com.yuchenhe.conference.registration;

import com.yuchenhe.conference.common.ApiException;
import com.yuchenhe.conference.common.Constraints;
import com.yuchenhe.conference.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat allocation. Every change to a session's seats (register, cancel, promote, capacity change)
 * first locks that session's row, so they run one at a time per session while different sessions
 * proceed in parallel. Lock order is always session, then registration, so two operations cannot
 * deadlock. The CHECK constraint {@code session_not_oversold} backs this up in the database.
 */
@Service
public class RegistrationService {
  private static final String VIEW_COLUMNS = "id, session_id, attendee_id, status, created_at";

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;
  private final MeterRegistry meters;

  RegistrationService(NamedParameterJdbcTemplate jdbc, Clock clock, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.meters = meters;
  }

  private record SeatState(int capacity, int confirmed, Instant startsAt) {}

  /** Confirms a seat if one is free, otherwise joins the waitlist. */
  @Transactional
  public RegistrationView register(Caller caller, long sessionId, long attendeeId) {
    caller.mustActFor(attendeeId);
    SeatState seats = lockSession(sessionId);
    if (!seats.startsAt().isAfter(clock.instant())) {
      throw ApiException.rule("session_started", "Registration closes when the session starts");
    }
    Boolean attendeeExists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM attendee WHERE id = :a)",
            Map.of("a", attendeeId),
            Boolean.class);
    if (!Boolean.TRUE.equals(attendeeExists)) {
      throw ApiException.notFound("Attendee", attendeeId);
    }
    RegistrationStatus status =
        seats.confirmed() < seats.capacity()
            ? RegistrationStatus.CONFIRMED
            : RegistrationStatus.WAITLISTED;
    RegistrationView created;
    try {
      created =
          jdbc.queryForObject(
              "INSERT INTO registration (session_id, attendee_id, status) VALUES (:s, :a, :st)"
                  + " RETURNING "
                  + VIEW_COLUMNS,
              Map.of("s", sessionId, "a", attendeeId, "st", status.name()),
              RegistrationService::row);
    } catch (DataIntegrityViolationException e) {
      if (Constraints.violated(e).filter("registration_one_live"::equals).isPresent()) {
        throw ApiException.conflict(
            "already_registered",
            "Attendee " + attendeeId + " is already registered for this session");
      }
      throw e;
    }
    if (status == RegistrationStatus.CONFIRMED) {
      jdbc.update(
          "UPDATE session SET confirmed_count = confirmed_count + 1 WHERE id = :s",
          Map.of("s", sessionId));
    }
    count(status.name().toLowerCase());
    return created;
  }

  /**
   * Cancels a registration. A freed seat goes straight to the oldest waitlisted registration.
   * Cancelling twice is harmless and returns the same result.
   */
  @Transactional
  public RegistrationView cancel(Caller caller, long registrationId) {
    Long sessionId;
    try {
      sessionId =
          jdbc.queryForObject(
              "SELECT session_id FROM registration WHERE id = :r",
              Map.of("r", registrationId),
              Long.class);
    } catch (EmptyResultDataAccessException e) {
      throw ApiException.notFound("Registration", registrationId);
    }
    lockSession(sessionId);
    RegistrationView current =
        jdbc.queryForObject(
            "SELECT " + VIEW_COLUMNS + " FROM registration WHERE id = :r FOR UPDATE",
            Map.of("r", registrationId),
            RegistrationService::row);
    caller.mustActFor(current.attendeeId());
    if (current.status() == RegistrationStatus.CANCELLED) {
      return current;
    }
    jdbc.update(
        "UPDATE registration SET status = 'CANCELLED', updated_at = now() WHERE id = :r",
        Map.of("r", registrationId));
    count("cancelled");
    if (current.status() == RegistrationStatus.CONFIRMED) {
      jdbc.update(
          "UPDATE session SET confirmed_count = confirmed_count - 1 WHERE id = :s",
          Map.of("s", sessionId));
      fillFreeSeats(sessionId);
    }
    return new RegistrationView(
        current.id(),
        current.sessionId(),
        current.attendeeId(),
        RegistrationStatus.CANCELLED,
        current.createdAt());
  }

  /** Moves waitlisted registrations, oldest first, into any free seats. Returns how many moved. */
  @Transactional
  public int fillFreeSeats(long sessionId) {
    SeatState seats = lockSession(sessionId);
    int free = seats.capacity() - seats.confirmed();
    if (free <= 0) {
      return 0;
    }
    int moved =
        jdbc.update(
            """
            UPDATE registration SET status = 'CONFIRMED', updated_at = now()
             WHERE id IN (SELECT id FROM registration
                           WHERE session_id = :s AND status = 'WAITLISTED'
                           ORDER BY id LIMIT :free)
            """,
            Map.of("s", sessionId, "free", free));
    if (moved > 0) {
      jdbc.update(
          "UPDATE session SET confirmed_count = confirmed_count + :n WHERE id = :s",
          Map.of("s", sessionId, "n", moved));
      meters.counter("conference.registrations", "outcome", "promoted").increment(moved);
    }
    return moved;
  }

  @Transactional(readOnly = true)
  public List<RegistrationView> forSession(long sessionId) {
    return jdbc.query(
        "SELECT " + VIEW_COLUMNS + " FROM registration WHERE session_id = :s ORDER BY id",
        Map.of("s", sessionId),
        RegistrationService::row);
  }

  @Transactional(readOnly = true)
  public List<RegistrationView> forAttendee(long attendeeId) {
    return jdbc.query(
        "SELECT " + VIEW_COLUMNS + " FROM registration WHERE attendee_id = :a ORDER BY id",
        Map.of("a", attendeeId),
        RegistrationService::row);
  }

  private SeatState lockSession(long sessionId) {
    try {
      return jdbc.queryForObject(
          "SELECT capacity, confirmed_count, starts_at FROM session WHERE id = :s FOR UPDATE",
          Map.of("s", sessionId),
          (rs, i) ->
              new SeatState(
                  rs.getInt("capacity"),
                  rs.getInt("confirmed_count"),
                  rs.getTimestamp("starts_at").toInstant()));
    } catch (EmptyResultDataAccessException e) {
      throw ApiException.notFound("Session", sessionId);
    }
  }

  private void count(String outcome) {
    meters.counter("conference.registrations", "outcome", outcome).increment();
  }

  private static RegistrationView row(ResultSet rs, int i) throws SQLException {
    return new RegistrationView(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getLong("attendee_id"),
        RegistrationStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant());
  }
}
