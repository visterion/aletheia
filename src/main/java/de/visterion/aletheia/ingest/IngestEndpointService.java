package de.visterion.aletheia.ingest;

import de.visterion.aletheia.substrate.ContractResolver;
import de.visterion.aletheia.substrate.CounterpartyResolver;
import de.visterion.aletheia.substrate.PayPalAttributionResolver;
import de.visterion.aletheia.substrate.SubstrateLock;
import de.visterion.aletheia.tagrules.TagRuleResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Serialised upload -&gt; ingest -&gt; resolve -&gt; archive pipeline for the HTTP endpoint. */
@Service
public class IngestEndpointService {

  private static final Logger log = LoggerFactory.getLogger(IngestEndpointService.class);

  private final IngestProperties properties;
  private final IngestService ingestService;
  private final PayPalAttributionResolver payPalAttributionResolver;
  private final CounterpartyResolver counterpartyResolver;
  private final ContractResolver contractResolver;
  private final TagRuleResolver tagRuleResolver;
  private final SubstrateLock lock;

  public IngestEndpointService(
      IngestProperties properties,
      IngestService ingestService,
      PayPalAttributionResolver payPalAttributionResolver,
      CounterpartyResolver counterpartyResolver,
      ContractResolver contractResolver,
      TagRuleResolver tagRuleResolver,
      SubstrateLock lock) {
    this.properties = properties;
    this.ingestService = ingestService;
    this.payPalAttributionResolver = payPalAttributionResolver;
    this.counterpartyResolver = counterpartyResolver;
    this.contractResolver = contractResolver;
    this.tagRuleResolver = tagRuleResolver;
    this.lock = lock;
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
        payPalAttributionResolver.resolve();
        counterpartyResolver.resolve();
        contractResolver.resolve();
        try {
          tagRuleResolver.resolve();
        } catch (RuntimeException e) {
          log.warn(
              "Auto-tagging rules failed after ingest; data committed, will retry next pass: {}",
              e.toString());
        }
        Files.move(
            working, imported.resolve(uuid + "-" + safeName), StandardCopyOption.ATOMIC_MOVE);
        return IngestResponse.from(safeName, summary);
      } catch (RuntimeException | IOException e) {
        deleteQuietly(working);
        if (e instanceof RuntimeException re) {
          throw re;
        }
        throw new UncheckedIOException("archive failed", (IOException) e);
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
