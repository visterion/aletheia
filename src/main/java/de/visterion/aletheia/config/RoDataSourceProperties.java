package de.visterion.aletheia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the SELECT-only connection ({@code aletheia_ro} in prod, spec §6). Used
 * exclusively by the {@code sql_query} MCP tool so a prompt-injected query cannot write, even if
 * the tool-layer SELECT-only check is somehow bypassed.
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
