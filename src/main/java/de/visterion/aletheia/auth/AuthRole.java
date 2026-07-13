package de.visterion.aletheia.auth;

import java.util.Locale;
import java.util.Objects;

/**
 * Aletheia's roles (spec §6): two working roles plus admin. HiveMem's four-role enum
 * (ADMIN/WRITER/READER/AGENT) is narrowed to three — Aletheia has no agent identity concept
 * yet, and {@code api_tokens.role} (V4 migration) only allows {@code reader|writer|admin}.
 */
public enum AuthRole {
  ADMIN,
  WRITER,
  READER;

  public static AuthRole fromWireValue(String value) {
    Objects.requireNonNull(value, "value");
    return AuthRole.valueOf(value.toUpperCase(Locale.ROOT));
  }

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
