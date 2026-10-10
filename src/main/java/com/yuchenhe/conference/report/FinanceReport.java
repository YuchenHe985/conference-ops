package com.yuchenhe.conference.report;

import java.util.List;

/** Income in cents: registration fees by attendee kind and sponsorship by tier. */
public record FinanceReport(List<Line> registrationFees, List<Line> sponsorships, long totalCents) {
  public record Line(String group, long count, long cents) {}
}
