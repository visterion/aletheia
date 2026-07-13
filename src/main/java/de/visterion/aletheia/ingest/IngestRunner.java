package de.visterion.aletheia.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup, ingests every *.json in the configured directory. Never throws.
 *
 * <p>{@code @Order(1)} guarantees this runs before {@link
 * de.visterion.aletheia.substrate.CounterpartyResolver} ({@code @Order(2)}) — Spring does not
 * order {@link ApplicationRunner} beans without explicit {@code @Order} (spec §3, adversarial
 * review M5).
 */
@Component
@Order(1)
public class IngestRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

  private final IngestProperties properties;
  private final IngestService ingestService;

  public IngestRunner(IngestProperties properties, IngestService ingestService) {
    this.properties = properties;
    this.ingestService = ingestService;
  }

  @Override
  public void run(ApplicationArguments args) {
    Path dir = properties.dir();
    if (dir == null || !Files.isDirectory(dir)) {
      log.info("Ingest directory {} absent — nothing to ingest", dir);
      return;
    }
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    } catch (IOException e) {
      log.warn("Cannot list ingest directory {}: {}", dir, e.getMessage());
      return;
    }
    for (Path file : files) {
      try {
        ImportSummary summary = ingestService.ingest(file);
        log.info("Ingest of {} -> {}", file.getFileName(), summary);
      } catch (RuntimeException e) {
        log.error("Ingest of {} failed, continuing with next file", file.getFileName(), e);
      }
    }
  }
}
