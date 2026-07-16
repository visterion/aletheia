package de.visterion.aletheia.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.unit.DataSize;

class IngestPropertiesBindingTest {

  private IngestProperties bind(Map<String, Object> props) {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test", props));
    Binder binder = new Binder(ConfigurationPropertySources.get(env));
    return binder.bind("aletheia.ingest", IngestProperties.class).get();
  }

  @Test
  void defaultsMaxFileSizeTo32MbWhenAbsent() {
    IngestProperties p = bind(Map.of("aletheia.ingest.dir", "exports"));
    assertThat(p.dir()).isEqualTo(Path.of("exports"));
    assertThat(p.maxFileSize()).isEqualTo(DataSize.ofMegabytes(32));
  }

  @Test
  void bindsExplicitMaxFileSize() {
    IngestProperties p =
        bind(Map.of("aletheia.ingest.dir", "exports", "aletheia.ingest.max-file-size", "10MB"));
    assertThat(p.maxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
  }
}
