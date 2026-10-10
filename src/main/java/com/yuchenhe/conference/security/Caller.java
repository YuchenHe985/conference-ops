package com.yuchenhe.conference.security;

import com.yuchenhe.conference.common.ApiException;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The authenticated caller: who they are, their role and (for attendees) their attendee id. */
public final class Caller extends User {
  private final Role role;
  private final Long attendeeId;

  Caller(
      String username,
      String passwordHash,
      Collection<? extends GrantedAuthority> authorities,
      Role role,
      Long attendeeId) {
    super(username, passwordHash, authorities);
    this.role = role;
    this.attendeeId = attendeeId;
  }

  public boolean isOrganizer() {
    return role == Role.ORGANIZER;
  }

  public Long attendeeId() {
    return attendeeId;
  }

  /** Organizers act for anyone; an attendee only for themself. */
  public void mustActFor(long attendee) {
    if (!isOrganizer() && (attendeeId == null || attendeeId != attendee)) {
      throw ApiException.forbidden("Attendees can only act on their own registrations");
    }
  }
}
