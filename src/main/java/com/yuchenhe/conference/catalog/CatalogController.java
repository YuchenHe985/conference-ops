package com.yuchenhe.conference.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organizer-only setup endpoints (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api")
class CatalogController {
  private final CatalogService catalog;

  CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  record NewRoom(@NotBlank @Size(max = 80) String name, @Positive @Max(10_000) int capacity) {}

  record NewSpeaker(@NotBlank @Size(max = 120) String fullName, @NotBlank @Email String email) {}

  record NewSponsor(
      @NotBlank @Size(max = 120) String company,
      @NotNull SponsorTier tier,
      @PositiveOrZero long amountCents) {}

  record NewAttendee(
      @NotBlank @Size(max = 120) String fullName,
      @NotBlank @Email String email,
      @NotNull AttendeeKind kind,
      Long sponsorId,
      @Size(min = 12, max = 72) String password) {}

  record Created(long id) {}

  @PostMapping("/rooms")
  @ResponseStatus(HttpStatus.CREATED)
  Created room(@Valid @RequestBody NewRoom r) {
    return new Created(catalog.addRoom(r.name(), r.capacity()).getId());
  }

  @PostMapping("/speakers")
  @ResponseStatus(HttpStatus.CREATED)
  Created speaker(@Valid @RequestBody NewSpeaker s) {
    return new Created(catalog.addSpeaker(s.fullName(), s.email()).getId());
  }

  @PostMapping("/sponsors")
  @ResponseStatus(HttpStatus.CREATED)
  Created sponsor(@Valid @RequestBody NewSponsor s) {
    return new Created(catalog.addSponsor(s.company(), s.tier(), s.amountCents()).getId());
  }

  @PostMapping("/attendees")
  @ResponseStatus(HttpStatus.CREATED)
  Created attendee(@Valid @RequestBody NewAttendee a) {
    return new Created(
        catalog
            .addAttendee(a.fullName(), a.email(), a.kind(), a.sponsorId(), a.password())
            .getId());
  }
}
