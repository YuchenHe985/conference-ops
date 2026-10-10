package com.yuchenhe.conference.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Attendee {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "full_name")
  private String fullName;

  private String email;

  @Enumerated(EnumType.STRING)
  private AttendeeKind kind;

  @Column(name = "sponsor_id")
  private Long sponsorId;

  @Column(name = "fee_cents")
  private long feeCents;

  protected Attendee() {}

  public Attendee(String fullName, String email, AttendeeKind kind, Long sponsorId) {
    this.fullName = fullName;
    this.email = email;
    this.kind = kind;
    this.sponsorId = sponsorId;
    this.feeCents = kind.feeCents();
  }

  public Long getId() {
    return id;
  }

  public String getFullName() {
    return fullName;
  }

  public String getEmail() {
    return email;
  }

  public AttendeeKind getKind() {
    return kind;
  }

  public Long getSponsorId() {
    return sponsorId;
  }

  public long getFeeCents() {
    return feeCents;
  }
}
