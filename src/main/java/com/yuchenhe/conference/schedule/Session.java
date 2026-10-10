package com.yuchenhe.conference.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A scheduled talk. Edits use optimistic locking (@Version). The seat counter is owned by
 * RegistrationService's SQL and is never written through this entity.
 */
@Entity
public class Session {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String title;

  @Column(name = "room_id")
  private long roomId;

  @Column(name = "speaker_id")
  private Long speakerId;

  @Column(name = "starts_at")
  private Instant startsAt;

  @Column(name = "ends_at")
  private Instant endsAt;

  private int capacity;

  @Column(name = "confirmed_count", insertable = false, updatable = false)
  private int confirmedCount;

  @Version private long version;

  protected Session() {}

  public Session(
      String title, long roomId, Long speakerId, Instant startsAt, Instant endsAt, int capacity) {
    this.title = title;
    this.roomId = roomId;
    this.speakerId = speakerId;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.capacity = capacity;
  }

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public long getRoomId() {
    return roomId;
  }

  public Long getSpeakerId() {
    return speakerId;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }

  public int getCapacity() {
    return capacity;
  }

  public int getConfirmedCount() {
    return confirmedCount;
  }

  public long getVersion() {
    return version;
  }

  void reschedule(String title, long roomId, Long speakerId, Instant startsAt, Instant endsAt) {
    this.title = title;
    this.roomId = roomId;
    this.speakerId = speakerId;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  void resize(int capacity) {
    this.capacity = capacity;
  }
}
