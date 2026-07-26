package de.visterion.aletheia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the read-only connection ({@code aletheia_ro} in prod, spec §6). Used
 * exclusively by the {@code sql_query} MCP tool. Neither this role's grants nor the session-level
 * {@code default_transaction_read_only} setting on this connection (both defense in depth) are
 * the actual write boundary -- {@code ReadTools#sqlQuery} wraps every statement in its own {@code
 * SET TRANSACTION READ ONLY} transaction, which Postgres enforces regardless of role or session
 * state.
 */
@ConfigurationProperties(prefix = "aletheia.datasource.ro")
public class RoDataSourceProperties {

  private String url;
  private String username;
  private String password;

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
