package de.visterion.aletheia.ingest;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "aletheia.ingest")
public record IngestProperties(Path dir, @DefaultValue("32MB") DataSize maxFileSize) {}
