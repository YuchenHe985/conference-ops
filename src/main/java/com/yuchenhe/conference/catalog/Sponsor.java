package com.yuchenhe.conference.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Sponsor {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String company;

  @Enumerated(EnumType.STRING)
  private SponsorTier tier;

  @Column(name = "amount_cents")
  private long amountCents;

  protected Sponsor() {}

  public Sponsor(String company, SponsorTier tier, long amountCents) {
    this.company = company;
    this.tier = tier;
    this.amountCents = amountCents;
  }

  public Long getId() {
    return id;
  }

  public String getCompany() {
    return company;
  }

  public SponsorTier getTier() {
    return tier;
  }

  public long getAmountCents() {
    return amountCents;
  }
}
