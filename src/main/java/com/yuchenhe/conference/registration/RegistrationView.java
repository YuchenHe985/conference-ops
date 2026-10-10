package com.yuchenhe.conference.registration;

import java.time.Instant;

public record RegistrationView(
    long id, long sessionId, long attendeeId, RegistrationStatus status, Instant createdAt) {}
