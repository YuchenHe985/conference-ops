package com.yuchenhe.conference.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@Validated
class ScheduleController {
  private final ScheduleService schedule;

  ScheduleController(ScheduleService schedule) {
    this.schedule = schedule;
  }

  record SessionBody(
      @NotBlank @Size(max = 200) String title,
      @NotNull Long roomId,
      Long speakerId,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @Positive @Max(10_000) int capacity,
      Long version) {
    ScheduleService.Draft draft() {
      return new ScheduleService.Draft(title, roomId, speakerId, startsAt, endsAt, capacity);
    }
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  SessionView create(@Valid @RequestBody SessionBody b) {
    return schedule.create(b.draft());
  }

  /** Full replacement; the body must carry the version the client last read. */
  @PutMapping("/{id}")
  SessionView update(@PathVariable long id, @Valid @RequestBody SessionBody b) {
    if (b.version() == null) {
      throw com.yuchenhe.conference.common.ApiException.rule(
          "version_required", "Send the version you last read, to avoid lost updates");
    }
    return schedule.update(id, b.version(), b.draft());
  }

  @GetMapping("/{id}")
  SessionView one(@PathVariable long id) {
    return schedule.view(id);
  }

  @GetMapping
  List<SessionView> list(
      @RequestParam(required = false) LocalDate day,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    return schedule.list(day, page, size);
  }
}
