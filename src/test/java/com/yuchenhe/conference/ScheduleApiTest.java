package com.yuchenhe.conference;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ScheduleApiTest extends ApiTestBase {

  @Test
  void aRoomCannotHostTwoOverlappingSessions() {
    long room = room(100);
    created(post(org(), "/api/sessions", sessionBody(room, null, TOMORROW_9, 60, 50)));
    var clash =
        post(
            org(),
            "/api/sessions",
            sessionBody(room, null, TOMORROW_9.plus(30, ChronoUnit.MINUTES), 60, 50));
    assertProblem(clash, HttpStatus.CONFLICT, "room_double_booked");
  }

  @Test
  void aSpeakerCannotBeInTwoRoomsAtOnce() {
    long speaker = speaker();
    created(post(org(), "/api/sessions", sessionBody(room(100), speaker, TOMORROW_9, 60, 50)));
    var clash =
        post(
            org(),
            "/api/sessions",
            sessionBody(room(100), speaker, TOMORROW_9.plus(59, ChronoUnit.MINUTES), 30, 50));
    assertProblem(clash, HttpStatus.CONFLICT, "speaker_double_booked");
  }

  @Test
  void backToBackSessionsInOneRoomAreAllowed() {
    long room = room(100);
    long speaker = speaker();
    created(post(org(), "/api/sessions", sessionBody(room, speaker, TOMORROW_9, 60, 50)));
    created(
        post(
            org(),
            "/api/sessions",
            sessionBody(room, speaker, TOMORROW_9.plus(60, ChronoUnit.MINUTES), 60, 50)));
  }

  @Test
  void sessionCapacityCannotExceedTheRoom() {
    assertProblem(
        post(org(), "/api/sessions", sessionBody(room(40), null, TOMORROW_9, 60, 41)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "capacity_exceeds_room");
  }

  @Test
  void invalidInputIsRejectedFieldByField() {
    Map<String, Object> body = sessionBody(room(40), null, TOMORROW_9, 60, 10);
    body.put("title", " ");
    body.put("capacity", 0);
    var r = post(org(), "/api/sessions", body);
    assertProblem(r, HttpStatus.BAD_REQUEST, "validation_failed");
    assertThat(r.getBody().get("fields").has("title")).isTrue();
    assertThat(r.getBody().get("fields").has("capacity")).isTrue();
  }

  @Test
  void anUpdateBasedOnAnOldVersionIsRefused() {
    long room = room(100);
    long id = created(post(org(), "/api/sessions", sessionBody(room, null, TOMORROW_9, 60, 50)));
    Map<String, Object> edit = sessionBody(room, null, TOMORROW_9, 90, 60);
    edit.put("version", 0);
    var first = call(org(), HttpMethod.PUT, "/api/sessions/" + id, edit, null);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody().get("version").asLong()).isEqualTo(1);
    assertProblem(
        call(org(), HttpMethod.PUT, "/api/sessions/" + id, edit, null),
        HttpStatus.CONFLICT,
        "stale_version");
  }

  @Test
  void raisingCapacityPromotesTheWaitlistInOrder() {
    long room = room(100);
    long id = created(post(org(), "/api/sessions", sessionBody(room, null, TOMORROW_9, 60, 2)));
    long[] regs = new long[4];
    for (int i = 0; i < 4; i++) {
      regs[i] = created(register(id, attendee("a" + i + "@test.org")));
    }
    Map<String, Object> edit = sessionBody(room, null, TOMORROW_9, 60, 3);
    edit.put("version", 0);
    var r = call(org(), HttpMethod.PUT, "/api/sessions/" + id, edit, null);
    assertThat(r.getBody().get("confirmed").asInt()).isEqualTo(3);
    assertThat(r.getBody().get("waitlisted").asInt()).isEqualTo(1);
    assertThat(status(regs[2])).isEqualTo("CONFIRMED");
    assertThat(status(regs[3])).isEqualTo("WAITLISTED");
  }

  @Test
  void capacityCannotDropBelowConfirmedSeats() {
    long room = room(100);
    long id = created(post(org(), "/api/sessions", sessionBody(room, null, TOMORROW_9, 60, 3)));
    for (int i = 0; i < 3; i++) {
      created(register(id, attendee("b" + i + "@test.org")));
    }
    Map<String, Object> edit = sessionBody(room, null, TOMORROW_9, 60, 2);
    edit.put("version", 0);
    assertProblem(
        call(org(), HttpMethod.PUT, "/api/sessions/" + id, edit, null),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "capacity_below_confirmed");
  }

  @Test
  void listingFiltersByDayAndShowsSeatCounts() {
    long id = session(1);
    created(register(id, attendee("c1@test.org")));
    created(register(id, attendee("c2@test.org")));
    var day = TOMORROW_9.toString().substring(0, 10);
    var r = org().getForEntity("/api/sessions?day=" + day, JsonNode.class);
    assertThat(r.getBody()).hasSize(1);
    assertThat(r.getBody().get(0).get("confirmed").asInt()).isEqualTo(1);
    assertThat(r.getBody().get(0).get("waitlisted").asInt()).isEqualTo(1);
    var other = org().getForEntity("/api/sessions?day=2000-01-01", JsonNode.class);
    assertThat(other.getBody()).isEmpty();
  }

  static void assertProblem(ResponseEntity<JsonNode> r, HttpStatus status, String code) {
    assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
    assertThat(r.getBody().get("code").asText()).isEqualTo(code);
  }
}
