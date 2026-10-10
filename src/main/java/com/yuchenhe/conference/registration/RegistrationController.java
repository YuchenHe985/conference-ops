package com.yuchenhe.conference.registration;

import com.yuchenhe.conference.common.ApiException;
import com.yuchenhe.conference.security.Caller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RegistrationController {
  private final RegistrationService registrations;
  private final IdempotencyService idempotency;

  RegistrationController(RegistrationService registrations, IdempotencyService idempotency) {
    this.registrations = registrations;
    this.idempotency = idempotency;
  }

  /** Organizers name the attendee; an attendee may leave it out to register themself. */
  record RegisterBody(Long attendeeId) {}

  @PostMapping("/api/sessions/{sessionId}/registrations")
  ResponseEntity<?> register(
      @AuthenticationPrincipal Caller caller,
      @PathVariable long sessionId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) RegisterBody body) {
    Long attendee =
        body != null && body.attendeeId() != null ? body.attendeeId() : caller.attendeeId();
    if (attendee == null) {
      throw ApiException.rule("attendee_required", "Organizers must name the attendee");
    }
    long attendeeId = attendee;
    return idempotency.once(
        caller.getUsername(),
        key,
        "POST /api/sessions/" + sessionId + "/registrations attendee=" + attendeeId,
        HttpStatus.CREATED,
        () -> registrations.register(caller, sessionId, attendeeId));
  }

  @DeleteMapping("/api/registrations/{id}")
  RegistrationView cancel(@AuthenticationPrincipal Caller caller, @PathVariable long id) {
    return registrations.cancel(caller, id);
  }

  @GetMapping("/api/sessions/{sessionId}/registrations")
  @PreAuthorize("hasRole('ORGANIZER')")
  List<RegistrationView> forSession(@PathVariable long sessionId) {
    return registrations.forSession(sessionId);
  }

  @GetMapping("/api/me/registrations")
  List<RegistrationView> mine(@AuthenticationPrincipal Caller caller) {
    return caller.attendeeId() == null ? List.of() : registrations.forAttendee(caller.attendeeId());
  }
}
