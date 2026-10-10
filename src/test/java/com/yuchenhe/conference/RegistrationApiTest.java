package com.yuchenhe.conference;

import static com.yuchenhe.conference.ScheduleApiTest.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RegistrationApiTest extends ApiTestBase {

  /** Runs all tasks at the same moment from separate threads and returns their results. */
  private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> t : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return t.call();
                }));
      }
      start.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> f : futures) {
        out.add(f.get());
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sixtySimultaneousRequestsNeverOversellTenSeats() throws Exception {
    long session = session(10);
    List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      long a = attendee("rush" + i + "@test.org");
      tasks.add(() -> register(session, a));
    }
    List<ResponseEntity<JsonNode>> results = concurrently(tasks);
    assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
    assertThat(countStatus(session, "CONFIRMED")).isEqualTo(10);
    assertThat(countStatus(session, "WAITLISTED")).isEqualTo(50);
    assertThat(confirmedColumn(session)).isEqualTo(10);
  }

  @Test
  void cancellationsRacingNewRegistrationsKeepEverySeatFilled() throws Exception {
    long session = session(5);
    List<Long> confirmed = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      long r = created(register(session, attendee("m" + i + "@test.org")));
      if (i < 5) {
        confirmed.add(r);
      }
    }
    List<Callable<Object>> tasks = new ArrayList<>();
    for (long r : confirmed) {
      tasks.add(() -> call(org(), HttpMethod.DELETE, "/api/registrations/" + r, null, null));
    }
    for (int i = 0; i < 10; i++) {
      long a = attendee("n" + i + "@test.org");
      tasks.add(() -> register(session, a));
    }
    concurrently(tasks);
    // 20 live registrations for 5 seats: all 5 seats taken, the counter agrees with the rows
    assertThat(countStatus(session, "CONFIRMED")).isEqualTo(5);
    assertThat(countStatus(session, "WAITLISTED")).isEqualTo(15);
    assertThat(confirmedColumn(session)).isEqualTo(5);
  }

  @Test
  void theWaitlistIsFirstComeFirstServed() {
    long session = session(1);
    long first = created(register(session, attendee("f1@test.org")));
    long second = created(register(session, attendee("f2@test.org")));
    long third = created(register(session, attendee("f3@test.org")));
    assertThat(status(second)).isEqualTo("WAITLISTED");
    var r = call(org(), HttpMethod.DELETE, "/api/registrations/" + first, null, null);
    assertThat(r.getBody().get("status").asText()).isEqualTo("CANCELLED");
    assertThat(status(second)).isEqualTo("CONFIRMED");
    assertThat(status(third)).isEqualTo("WAITLISTED");
  }

  @Test
  void cancellingTwiceIsHarmless() {
    long session = session(1);
    long reg = created(register(session, attendee("t@test.org")));
    call(org(), HttpMethod.DELETE, "/api/registrations/" + reg, null, null);
    var again = call(org(), HttpMethod.DELETE, "/api/registrations/" + reg, null, null);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(confirmedColumn(session)).isZero();
  }

  @Test
  void aDuplicateIsRefusedButRegisteringAgainAfterCancellingWorks() {
    long session = session(5);
    long a = attendee("d@test.org");
    long reg = created(register(session, a));
    assertProblem(register(session, a), HttpStatus.CONFLICT, "already_registered");
    assertThat(confirmedColumn(session)).isEqualTo(1);
    call(org(), HttpMethod.DELETE, "/api/registrations/" + reg, null, null);
    created(register(session, a));
  }

  @Test
  void registrationClosesWhenTheSessionStarts() {
    Instant started = Instant.now().minus(10, ChronoUnit.MINUTES);
    long session =
        created(post(org(), "/api/sessions", sessionBody(room(50), null, started, 60, 10)));
    assertProblem(
        register(session, attendee("late@test.org")),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "session_started");
  }

  @Test
  void aRetriedRequestWithTheSameKeyIsAppliedOnce() {
    long session = session(5);
    long a = attendee("i@test.org");
    String url = "/api/sessions/" + session + "/registrations";
    var first = call(org(), HttpMethod.POST, url, Map.of("attendeeId", a), "key-1");
    var retry = call(org(), HttpMethod.POST, url, Map.of("attendeeId", a), "key-1");
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(countStatus(session, "CONFIRMED")).isEqualTo(1);
  }

  @Test
  void aKeyCannotBeReusedForADifferentRequest() {
    long session = session(5);
    String url = "/api/sessions/" + session + "/registrations";
    call(org(), HttpMethod.POST, url, Map.of("attendeeId", attendee("k1@test.org")), "key-2");
    assertProblem(
        call(org(), HttpMethod.POST, url, Map.of("attendeeId", attendee("k2@test.org")), "key-2"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "idempotency_key_reused");
  }

  @Test
  void simultaneousRetriesWithOneKeyCreateOneRegistration() throws Exception {
    long session = session(5);
    long a = attendee("burst@test.org");
    String url = "/api/sessions/" + session + "/registrations";
    List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(() -> call(org(), HttpMethod.POST, url, Map.of("attendeeId", a), "key-3"));
    }
    List<ResponseEntity<JsonNode>> results = concurrently(tasks);
    assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
    assertThat(results.stream().map(r -> r.getBody().get("id").asLong()).distinct()).hasSize(1);
    assertThat(countStatus(session, "CONFIRMED")).isEqualTo(1);
  }

  @Test
  void aFailedAttemptDoesNotBurnTheKey() {
    long session = session(5);
    String url = "/api/sessions/" + session + "/registrations";
    var missing = call(org(), HttpMethod.POST, url, Map.of("attendeeId", 999), "key-4");
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    jdbc.update(
        "INSERT INTO attendee (id, full_name, email, kind, fee_cents) VALUES (999, 'x', 'x@test.org', 'STUDENT', 5000)");
    var retry = call(org(), HttpMethod.POST, url, Map.of("attendeeId", 999), "key-4");
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }
}
