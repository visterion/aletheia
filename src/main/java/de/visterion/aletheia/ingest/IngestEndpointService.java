package de.visterion.aletheia.ingest;

import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Serialised upload -&gt; ingest -&gt; resolve -&gt; archive pipeline for the HTTP endpoint. */
@Service
public class IngestEndpointService {

  private static final Logger log = LoggerFactory.getLogger(IngestEndpointService.class);

  private final IngestProperties properties;
  private final IngestService ingestService;
  private final CounterpartyResolver counterpartyResolver;
  private final ContractResolver contractResolver;
  private final ReentrantLock lock = new ReentrantLock();

  public IngestEndpointService(
      IngestProperties properties,
      IngestService ingestService,
      CounterpartyResolver counterpartyResolver,
      ContractResolver contractResolver) {
    this.properties = properties;
    this.ingestService = ingestService;
    this.counterpartyResolver = counterpartyResolver;
    this.contractResolver = contractResolver;
  }

  /**
   * Stages {@code bytes} as a unique working file, ingests it, refreshes both resolvers so the
   * substrate reflects the upload without a restart, then archives the working file. On any
   * failure the working file is deleted so {@code incoming/} never accumulates leftovers.
   */
  public IngestResponse ingestUpload(byte[] bytes, String rawClientName) {
    String safeName = FileNameSanitizer.sanitize(rawClientName);
    lock.lock();
    try {
      Path incoming = properties.dir().resolve("incoming");
      Path imported = properties.dir().resolve("imported");
      Files.createDirectories(incoming);
      Files.createDirectories(imported);
      String uuid = UUID.randomUUID().toString();
      Path working = incoming.resolve(uuid + ".json.part");
      Files.write(working, bytes);
      try {
        ImportSummary summary = ingestService.ingest(working, safeName);
        counterpartyResolver.resolve();
        contractResolver.resolve();
        Files.move(
            working, imported.resolve(uuid + "-" + safeName), StandardCopyOption.ATOMIC_MOVE);
        return IngestResponse.from(safeName, summary);
      } catch (RuntimeException e) {
        deleteQuietly(working);
        throw e;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("upload staging failed", e);
    } finally {
      lock.unlock();
    }
  }

  /** Byte threshold used by the controller for the app-level 413 (spec §7). */
  public long maxFileSizeBytes() {
    return properties.maxFileSize().toBytes();
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      log.warn("could not delete working file after failure: {}", e.getMessage());
    }
  }
}
