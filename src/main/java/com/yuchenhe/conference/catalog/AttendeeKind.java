package com.yuchenhe.conference.catalog;

/** Attendee category; it fixes the registration fee (sponsor staff attend free). */
public enum AttendeeKind {
  STUDENT(50_00),
  PROFESSIONAL(100_00),
  SPONSOR(0);

  private final long feeCents;

  AttendeeKind(long feeCents) {
    this.feeCents = feeCents;
  }

  public long feeCents() {
    return feeCents;
  }
}
