package de.visterion.aletheia.config;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.util.StringUtils;

/**
 * Wires the two datasources from spec §6/§7: a {@code @Primary} app connection and a named {@code
 * roDsl} connection used exclusively by the SELECT-only {@code sql_query} tool.
 *
 * <p>In prod the app/ro credentials bind to two distinct, differently-privileged Postgres roles
 * ({@code aletheia_app} / {@code aletheia_ro}, provisioned out-of-band by a Postgres init script,
 * spec §7). Tests run single-role: both {@code aletheia.datasource.app.*} and {@code
 * aletheia.datasource.ro.*} are pointed at the same Testcontainers instance, so this class only
 * verifies the bean wiring, not privilege separation (grant enforcement is prod-only).
 *
 * <p>Credential precedence for each datasource: explicit {@code aletheia.datasource.app|ro.*}
 * properties → a {@link JdbcConnectionDetails} bean (what Testcontainers' {@code @ServiceConnection}
 * publishes, so ITs that don't set the explicit props keep working) → the standard {@code
 * spring.datasource.*} ({@link DataSourceProperties}, the pre-existing single-datasource creds).
 *
 * <p>Defining these {@link DataSource} beans makes Boot's own {@code DataSourceAutoConfiguration}
 * back off ({@code @ConditionalOnMissingBean(DataSource.class)}); marking the app {@link DSLContext}
 * {@code @Primary} makes Boot's {@code JooqAutoConfiguration} back off too, so no beans duplicate.
 * Because the auto-configured DataSource no longer consumes {@link JdbcConnectionDetails} for us, we
 * resolve it ourselves above.
 */
@Configuration
@EnableConfigurationProperties({AppDataSourceProperties.class, RoDataSourceProperties.class})
public class DataSourceConfig {

  @Primary
  @Bean
  public DataSource dataSource(
      AppDataSourceProperties appProperties,
      ObjectProvider<JdbcConnectionDetails> connectionDetails,
      DataSourceProperties fallback) {
    return buildDataSource(
        appProperties.getUrl(),
        appProperties.getUsername(),
        appProperties.getPassword(),
        connectionDetails.getIfAvailable(),
        fallback);
  }

  @Bean
  public DataSource roDataSource(
      RoDataSourceProperties roProperties,
      ObjectProvider<JdbcConnectionDetails> connectionDetails,
      DataSourceProperties fallback) {
    return buildDataSource(
        roProperties.getUrl(),
        roProperties.getUsername(),
        roProperties.getPassword(),
        connectionDetails.getIfAvailable(),
        fallback);
  }

  @Primary
  @Bean
  public DSLContext dslContext(@Qualifier("dataSource") DataSource dataSource) {
    return buildDslContext(dataSource);
  }

  @Bean("roDsl")
  public DSLContext roDsl(@Qualifier("roDataSource") DataSource roDataSource) {
    return buildDslContext(roDataSource);
  }

  private static DataSource buildDataSource(
      String url,
      String username,
      String password,
      JdbcConnectionDetails details,
      DataSourceProperties fallback) {
    String resolvedUrl =
        resolve(url, details != null ? details.getJdbcUrl() : null, fallback.getUrl());
    String resolvedUsername =
        resolve(username, details != null ? details.getUsername() : null, fallback.getUsername());
    String resolvedPassword =
        resolve(password, details != null ? details.getPassword() : null, fallback.getPassword());
    return DataSourceBuilder.create()
        .url(resolvedUrl)
        .username(resolvedUsername)
        .password(resolvedPassword)
        .build();
  }

  private static DSLContext buildDslContext(DataSource dataSource) {
    DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    configuration.set(SQLDialect.POSTGRES);
    return new DefaultDSLContext(configuration);
  }

  /** Returns the first candidate that carries text (empty env-var defaults count as unset). */
  private static String resolve(
      String explicit, String fromConnectionDetails, String fromFallback) {
    if (StringUtils.hasText(explicit)) {
      return explicit;
    }
    if (StringUtils.hasText(fromConnectionDetails)) {
      return fromConnectionDetails;
    }
    return fromFallback;
  }
}
