package com.yuchenhe.conference.schedule;

import java.time.Instant;

/** A session as the API shows it, with live seat numbers. */
public record SessionView(
    long id,
    String title,
    String room,
    String speaker,
    Instant startsAt,
    Instant endsAt,
    int capacity,
    int confirmed,
    int waitlisted,
    long version) {}
