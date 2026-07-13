package de.visterion.aletheia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the app's main connection ({@code aletheia_app} in prod, spec §6). Falls back
 * to {@code spring.datasource.*} when unset so the single-datasource behavior keeps working.
 */
@ConfigurationProperties(prefix = "aletheia.datasource.app")
public class AppDataSourceProperties {

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
