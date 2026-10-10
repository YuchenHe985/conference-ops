package com.yuchenhe.conference.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuchenhe.conference.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a POST safe to retry. The key is claimed in the same database transaction as the work, so
 * either both are committed or neither is: a failed attempt leaves no record and can be retried,
 * and a second request with the same key waits for the first and then replays its response.
 */
@Service
public class IdempotencyService {
  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper json;

  IdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  public ResponseEntity<?> once(
      String username, String key, String requestFingerprint, HttpStatus status, Supplier<?> work) {
    if (key == null) {
      return ResponseEntity.status(status).body(work.get());
    }
    if (key.isBlank() || key.length() > 100) {
      throw ApiException.rule(
          "invalid_idempotency_key", "Idempotency-Key must be 1-100 characters");
    }
    String hash = sha256(requestFingerprint);
    Map<String, Object> id = Map.of("u", username, "k", key, "h", hash);
    int claimed =
        jdbc.update(
            "INSERT INTO idempotency_record (username, idem_key, request_hash) VALUES (:u, :k, :h)"
                + " ON CONFLICT DO NOTHING",
            id);
    if (claimed == 0) {
      return replay(id, hash);
    }
    Object body = work.get();
    try {
      jdbc.update(
          "UPDATE idempotency_record SET status_code = :c, response_body = :b"
              + " WHERE username = :u AND idem_key = :k",
          Map.of("u", username, "k", key, "c", status.value(), "b", json.writeValueAsString(body)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("response is not serialisable", e);
    }
    return ResponseEntity.status(status).body(body);
  }

  private ResponseEntity<?> replay(Map<String, Object> id, String hash) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT request_hash, status_code, response_body FROM idempotency_record"
                + " WHERE username = :u AND idem_key = :k",
            id);
    Map<String, Object> r = rows.get(0);
    if (!hash.equals(r.get("request_hash"))) {
      throw ApiException.rule(
          "idempotency_key_reused",
          "This Idempotency-Key was already used for a different request");
    }
    return ResponseEntity.status((Integer) r.get("status_code"))
        .contentType(MediaType.APPLICATION_JSON)
        .header(REPLAYED_HEADER, "true")
        .body(r.get("response_body"));
  }

  /** Keys are honoured for 24 hours, like most payment APIs. */
  @Scheduled(fixedDelayString = "${app.idempotency.purge-interval:PT1H}")
  @Transactional
  public void purgeExpired() {
    int n =
        jdbc.update(
            "DELETE FROM idempotency_record WHERE created_at < now() - interval '24 hours'",
            Map.of());
    if (n > 0) {
      log.info("purged {} expired idempotency records", n);
    }
  }

  private static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
