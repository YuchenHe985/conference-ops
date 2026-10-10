package com.yuchenhe.conference.report;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportController {
  private final NamedParameterJdbcTemplate jdbc;

  ReportController(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Organizer only (deny-by-default rule in SecurityConfig). Both sums come from one snapshot. */
  @GetMapping("/api/reports/finance")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  FinanceReport finance() {
    List<FinanceReport.Line> fees =
        lines(
            "SELECT kind AS g, count(*) AS n, sum(fee_cents) AS c FROM attendee GROUP BY kind ORDER BY kind");
    List<FinanceReport.Line> sponsors =
        lines(
            "SELECT tier AS g, count(*) AS n, sum(amount_cents) AS c FROM sponsor GROUP BY tier ORDER BY tier");
    long total =
        fees.stream().mapToLong(FinanceReport.Line::cents).sum()
            + sponsors.stream().mapToLong(FinanceReport.Line::cents).sum();
    return new FinanceReport(fees, sponsors, total);
  }

  private List<FinanceReport.Line> lines(String sql) {
    return jdbc.query(
        sql,
        Map.of(),
        (rs, i) -> new FinanceReport.Line(rs.getString("g"), rs.getLong("n"), rs.getLong("c")));
  }
}
