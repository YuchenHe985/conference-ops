package com.yuchenhe.conference.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String username;

  @Column(name = "password_hash")
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  private Role role;

  @Column(name = "attendee_id")
  private Long attendeeId;

  protected AppUser() {}

  public AppUser(String username, String passwordHash, Role role, Long attendeeId) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.role = role;
    this.attendeeId = attendeeId;
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public Long getAttendeeId() {
    return attendeeId;
  }
}
