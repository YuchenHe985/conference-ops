package com.yuchenhe.conference;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Full stack: real HTTP, real security, real PostgreSQL 16 (embedded, no Docker needed). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
public abstract class ApiTestBase {
  protected static final String ORG = "organizer@test.org";
  protected static final String ORG_PW = "organizer-password-1";
  protected static final String PW = "attendee-password-1";
  protected static final Instant TOMORROW_9 =
      Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);

  @Autowired protected TestRestTemplate rest;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired private PasswordEncoder encoder;
  private int seq;

  @BeforeEach
  void resetDatabase() {
    jdbc.execute(
        "TRUNCATE registration, session, app_user, attendee, sponsor, speaker, room,"
            + " idempotency_record RESTART IDENTITY CASCADE");
    jdbc.update(
        "INSERT INTO app_user (username, password_hash, role) VALUES (?, ?, 'ORGANIZER')",
        ORG,
        encoder.encode(ORG_PW));
  }

  protected TestRestTemplate org() {
    return rest.withBasicAuth(ORG, ORG_PW);
  }

  protected TestRestTemplate as(String email) {
    return rest.withBasicAuth(email, PW);
  }

  protected ResponseEntity<JsonNode> call(
      TestRestTemplate client, HttpMethod method, String url, Object body, String idemKey) {
    HttpHeaders h = new HttpHeaders();
    if (idemKey != null) {
      h.set("Idempotency-Key", idemKey);
    }
    return client.exchange(url, method, new HttpEntity<>(body, h), JsonNode.class);
  }

  protected ResponseEntity<JsonNode> post(TestRestTemplate client, String url, Object body) {
    return call(client, HttpMethod.POST, url, body, null);
  }

  protected long created(ResponseEntity<JsonNode> r) {
    assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    return r.getBody().get("id").asLong();
  }

  protected long room(int capacity) {
    return created(
        post(org(), "/api/rooms", Map.of("name", "Room " + ++seq, "capacity", capacity)));
  }

  protected long speaker() {
    int n = ++seq;
    return created(
        post(
            org(),
            "/api/speakers",
            Map.of("fullName", "Speaker " + n, "email", "s" + n + "@test.org")));
  }

  /** A student attendee who can log in as {@code email} / {@link #PW}. */
  protected long attendee(String email) {
    return created(
        post(
            org(),
            "/api/attendees",
            Map.of("fullName", email, "email", email, "kind", "STUDENT", "password", PW)));
  }

  protected Map<String, Object> sessionBody(
      long room, Long speaker, Instant start, int minutes, int capacity) {
    var m = new java.util.HashMap<String, Object>();
    m.put("title", "Talk " + ++seq);
    m.put("roomId", room);
    m.put("speakerId", speaker);
    m.put("startsAt", start.toString());
    m.put("endsAt", start.plus(minutes, ChronoUnit.MINUTES).toString());
    m.put("capacity", capacity);
    return m;
  }

  protected long session(int capacity) {
    return created(
        post(org(), "/api/sessions", sessionBody(room(500), null, TOMORROW_9, 60, capacity)));
  }

  protected ResponseEntity<JsonNode> register(long session, long attendee) {
    return post(
        org(), "/api/sessions/" + session + "/registrations", Map.of("attendeeId", attendee));
  }

  protected String status(long registration) {
    return jdbc.queryForObject(
        "SELECT status FROM registration WHERE id = ?", String.class, registration);
  }

  protected int countStatus(long session, String status) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM registration WHERE session_id = ? AND status = ?",
        Integer.class,
        session,
        status);
  }

  protected int confirmedColumn(long session) {
    return jdbc.queryForObject(
        "SELECT confirmed_count FROM session WHERE id = ?", Integer.class, session);
  }
}
