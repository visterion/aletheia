package de.visterion.aletheia.ingest;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aletheia.ingest")
public record IngestProperties(Path dir) {}
