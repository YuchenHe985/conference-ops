package com.yuchenhe.conference.catalog;

import com.yuchenhe.conference.common.ApiException;
import com.yuchenhe.conference.common.Constraints;
import com.yuchenhe.conference.security.AppUser;
import com.yuchenhe.conference.security.AppUserRepository;
import com.yuchenhe.conference.security.Role;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rooms, speakers, sponsors and attendees (with an optional login for the attendee). */
@Service
public class CatalogService {
  private final RoomRepository rooms;
  private final SpeakerRepository speakers;
  private final SponsorRepository sponsors;
  private final AttendeeRepository attendees;
  private final AppUserRepository users;
  private final PasswordEncoder encoder;

  CatalogService(
      RoomRepository rooms,
      SpeakerRepository speakers,
      SponsorRepository sponsors,
      AttendeeRepository attendees,
      AppUserRepository users,
      PasswordEncoder encoder) {
    this.rooms = rooms;
    this.speakers = speakers;
    this.sponsors = sponsors;
    this.attendees = attendees;
    this.users = users;
    this.encoder = encoder;
  }

  @Transactional
  public Room addRoom(String name, int capacity) {
    return unique(() -> rooms.saveAndFlush(new Room(name.strip(), capacity)), "room name");
  }

  @Transactional
  public Speaker addSpeaker(String fullName, String email) {
    return unique(
        () -> speakers.saveAndFlush(new Speaker(fullName.strip(), normalize(email))),
        "speaker email");
  }

  @Transactional
  public Sponsor addSponsor(String company, SponsorTier tier, long amountCents) {
    return unique(
        () -> sponsors.saveAndFlush(new Sponsor(company.strip(), tier, amountCents)),
        "sponsor company");
  }

  /** Registers a person for the conference; with a password they also get an attendee login. */
  @Transactional
  public Attendee addAttendee(
      String fullName, String email, AttendeeKind kind, Long sponsorId, String password) {
    if ((kind == AttendeeKind.SPONSOR) != (sponsorId != null)) {
      throw ApiException.rule(
          "sponsor_mismatch", "Sponsor staff need a sponsorId and only sponsor staff may have one");
    }
    if (sponsorId != null && !sponsors.existsById(sponsorId)) {
      throw ApiException.notFound("Sponsor", sponsorId);
    }
    String mail = normalize(email);
    Attendee a =
        unique(
            () -> attendees.saveAndFlush(new Attendee(fullName.strip(), mail, kind, sponsorId)),
            "attendee email");
    if (password != null) {
      unique(
          () ->
              users.saveAndFlush(
                  new AppUser(mail, encoder.encode(password), Role.ATTENDEE, a.getId())),
          "username");
    }
    return a;
  }

  private static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  private static <T> T unique(Supplier<T> write, String what) {
    try {
      return write.get();
    } catch (DataIntegrityViolationException e) {
      String c = Constraints.violated(e).orElse("");
      if (c.endsWith("_key")) {
        throw ApiException.conflict("duplicate", "That " + what + " is already taken");
      }
      throw e;
    }
  }
}
