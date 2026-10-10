package com.yuchenhe.conference.common;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Reads which database constraint rejected a write, so it can become a precise API error. */
public final class Constraints {
  private Constraints() {}

  /** Name of the violated constraint or unique index, if the cause chain holds one. */
  public static Optional<String> violated(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof PSQLException p) {
        ServerErrorMessage m = p.getServerErrorMessage();
        return Optional.ofNullable(m == null ? null : m.getConstraint());
      }
    }
    return Optional.empty();
  }
}
