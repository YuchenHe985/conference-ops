package com.yuchenhe.conference.schedule;

import com.yuchenhe.conference.catalog.Room;
import com.yuchenhe.conference.catalog.RoomRepository;
import com.yuchenhe.conference.catalog.SpeakerRepository;
import com.yuchenhe.conference.common.ApiException;
import com.yuchenhe.conference.common.Constraints;
import com.yuchenhe.conference.registration.RegistrationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and changing sessions. Double-booking of rooms and speakers is rejected by exclusion
 * constraints in PostgreSQL (not by a check-then-insert that two requests could both pass).
 */
@Service
public class ScheduleService {
  private final SessionRepository sessions;
  private final RoomRepository rooms;
  private final SpeakerRepository speakers;
  private final RegistrationService registrations;
  private final NamedParameterJdbcTemplate jdbc;

  ScheduleService(
      SessionRepository sessions,
      RoomRepository rooms,
      SpeakerRepository speakers,
      RegistrationService registrations,
      NamedParameterJdbcTemplate jdbc) {
    this.sessions = sessions;
    this.rooms = rooms;
    this.speakers = speakers;
    this.registrations = registrations;
    this.jdbc = jdbc;
  }

  public record Draft(
      String title, long roomId, Long speakerId, Instant startsAt, Instant endsAt, int capacity) {}

  @Transactional
  public SessionView create(Draft d) {
    check(d);
    Session s =
        new Session(
            d.title().strip(), d.roomId(), d.speakerId(), d.startsAt(), d.endsAt(), d.capacity());
    return view(saveChecked(s).getId());
  }

  /**
   * Applies a full update if {@code expectedVersion} is still current. A larger capacity promotes
   * people from the waitlist at once; a capacity below the confirmed seats is refused.
   */
  @Transactional
  public SessionView update(long id, long expectedVersion, Draft d) {
    Session s = sessions.findById(id).orElseThrow(() -> ApiException.notFound("Session", id));
    if (s.getVersion() != expectedVersion) {
      throw ApiException.conflict(
          "stale_version",
          "Session " + id + " is at version " + s.getVersion() + ", not " + expectedVersion);
    }
    check(d);
    s.reschedule(d.title().strip(), d.roomId(), d.speakerId(), d.startsAt(), d.endsAt());
    s.resize(d.capacity());
    saveChecked(s);
    registrations.fillFreeSeats(id);
    return view(id);
  }

  @Transactional(readOnly = true)
  public SessionView view(long id) {
    List<SessionView> v = query("WHERE s.id = :id", new MapSqlParameterSource("id", id));
    if (v.isEmpty()) {
      throw ApiException.notFound("Session", id);
    }
    return v.get(0);
  }

  /** Sessions starting on {@code day} (UTC), or all when null; ordered by start time. */
  @Transactional(readOnly = true)
  public List<SessionView> list(LocalDate day, int page, int size) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("limit", size).addValue("offset", (long) page * size);
    String where = "";
    if (day != null) {
      where = "WHERE s.starts_at >= :from AND s.starts_at < :to";
      p.addValue("from", java.sql.Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));
      p.addValue(
          "to", java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    return query(where + " ORDER BY s.starts_at, s.id LIMIT :limit OFFSET :offset", p);
  }

  private List<SessionView> query(String tail, MapSqlParameterSource p) {
    return jdbc.query(
        """
        SELECT s.id, s.title, r.name AS room, sp.full_name AS speaker, s.starts_at, s.ends_at,
               s.capacity, s.confirmed_count, s.version,
               (SELECT count(*) FROM registration w
                 WHERE w.session_id = s.id AND w.status = 'WAITLISTED') AS waitlisted
          FROM session s JOIN room r ON r.id = s.room_id
          LEFT JOIN speaker sp ON sp.id = s.speaker_id
        """
            + tail,
        p,
        (rs, i) ->
            new SessionView(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("room"),
                rs.getString("speaker"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                rs.getInt("capacity"),
                rs.getInt("confirmed_count"),
                rs.getInt("waitlisted"),
                rs.getLong("version")));
  }

  private void check(Draft d) {
    if (!d.endsAt().isAfter(d.startsAt())) {
      throw ApiException.rule("invalid_time_range", "A session must end after it starts");
    }
    Room room =
        rooms.findById(d.roomId()).orElseThrow(() -> ApiException.notFound("Room", d.roomId()));
    if (d.capacity() > room.getCapacity()) {
      throw ApiException.rule(
          "capacity_exceeds_room",
          "Room " + room.getName() + " holds " + room.getCapacity() + " people");
    }
    if (d.speakerId() != null && !speakers.existsById(d.speakerId())) {
      throw ApiException.notFound("Speaker", d.speakerId());
    }
  }

  private Session saveChecked(Session s) {
    try {
      return sessions.saveAndFlush(s);
    } catch (DataIntegrityViolationException e) {
      String c = Constraints.violated(e).orElse("");
      switch (c) {
        case "session_room_no_overlap" ->
            throw ApiException.conflict(
                "room_double_booked", "The room already has a session at that time");
        case "session_speaker_no_overlap" ->
            throw ApiException.conflict(
                "speaker_double_booked", "The speaker already has a session at that time");
        case "session_not_oversold" ->
            throw ApiException.rule(
                "capacity_below_confirmed",
                "More people are confirmed than the new capacity allows");
        default -> throw e;
      }
    }
  }
}
