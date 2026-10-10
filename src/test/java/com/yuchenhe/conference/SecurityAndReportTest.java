package com.yuchenhe.conference;

import static com.yuchenhe.conference.ScheduleApiTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class SecurityAndReportTest extends ApiTestBase {

  @Test
  void healthIsPublicAndEverythingElseNeedsALogin() {
    assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(rest.getForEntity("/api/sessions", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            rest.withBasicAuth(ORG, "wrong-password-xx")
                .getForEntity("/api/sessions", String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void attendeesCannotUseOrganizerEndpoints() {
    attendee("p@test.org");
    assertThat(
            post(as("p@test.org"), "/api/rooms", Map.of("name", "X", "capacity", 5))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(as("p@test.org").getForEntity("/api/reports/finance", String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    long session = session(5);
    assertThat(
            as("p@test.org")
                .getForEntity("/api/sessions/" + session + "/registrations", String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void attendeesActOnlyForThemselves() {
    long session = session(5);
    attendee("me@test.org");
    long other = attendee("other@test.org");
    var self = post(as("me@test.org"), "/api/sessions/" + session + "/registrations", null);
    assertThat(self.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertProblem(
        post(
            as("me@test.org"),
            "/api/sessions/" + session + "/registrations",
            Map.of("attendeeId", other)),
        HttpStatus.FORBIDDEN,
        "forbidden");
    long othersReg = created(register(session, other));
    assertProblem(
        call(as("me@test.org"), HttpMethod.DELETE, "/api/registrations/" + othersReg, null, null),
        HttpStatus.FORBIDDEN,
        "forbidden");
    var mine = as("me@test.org").getForEntity("/api/me/registrations", JsonNode.class);
    assertThat(mine.getBody()).hasSize(1);
    assertThat(mine.getBody().get(0).get("id").asLong())
        .isEqualTo(self.getBody().get("id").asLong());
  }

  @Test
  void duplicateEmailsAreRefused() {
    attendee("dup@test.org");
    assertProblem(
        post(
            org(),
            "/api/attendees",
            Map.of("fullName", "Again", "email", "dup@test.org", "kind", "PROFESSIONAL")),
        HttpStatus.CONFLICT,
        "duplicate");
  }

  @Test
  void financeReportAddsFeesAndSponsorships() {
    attendee("s1@test.org");
    attendee("s2@test.org");
    created(
        post(
            org(),
            "/api/attendees",
            Map.of("fullName", "Pro", "email", "pro@test.org", "kind", "PROFESSIONAL")));
    long sponsor =
        created(
            post(
                org(),
                "/api/sponsors",
                Map.of("company", "Acme", "tier", "GOLD", "amountCents", 300_000)));
    created(
        post(
            org(),
            "/api/attendees",
            Map.of(
                "fullName",
                "Rep",
                "email",
                "rep@acme.com",
                "kind",
                "SPONSOR",
                "sponsorId",
                sponsor)));
    JsonNode r = org().getForEntity("/api/reports/finance", JsonNode.class).getBody();
    assertThat(r.get("totalCents").asLong()).isEqualTo(5_000 + 5_000 + 10_000 + 300_000);
  }

  @Test
  void aSponsorAttendeeMustNameAnExistingSponsor() {
    var r =
        post(
            org(),
            "/api/attendees",
            Map.of("fullName", "Rep", "email", "rep2@acme.com", "kind", "SPONSOR"));
    assertThat(r.getStatusCode().is4xxClientError()).isTrue();
  }
}
