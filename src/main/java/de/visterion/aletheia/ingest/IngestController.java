package de.visterion.aletheia.ingest;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** HTTP entry point for the Subsembly export upload pipeline (spec §7 + raw-body variant). */
@RestController
public class IngestController {

  private static final Logger log = LoggerFactory.getLogger(IngestController.class);
  private static final DateTimeFormatter DEFAULT_NAME_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final IngestEndpointService service;

  public IngestController(IngestEndpointService service) {
    this.service = service;
  }

  /** Multipart upload (curl -F, browser, Android app): the file arrives as the {@code file} part. */
  @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public IngestResponse ingestMultipart(
      @RequestParam("file") MultipartFile file, HttpServletRequest request) {
    AuthPrincipal principal = principalOrForbid(request);
    if (file == null || file.isEmpty()) {
      log.warn("ingest rejected: empty file part (token={})", principal.name());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file part");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return handle(bytes, file.getOriginalFilename(), principal, "multipart", request.getContentType());
  }

  /**
   * Raw-body upload (iOS Shortcut "Datei" mode): the request body IS the export file. No {@code
   * consumes} restriction on purpose — this handler must catch <em>every</em> non-multipart
   * Content-Type (the iOS "Datei" mode's `application/json`, `application/octet-stream`,
   * `text/plain`, a charset-parameterised json, a vendor `+json`, even an absent Content-Type,
   * which the servlet treats as octet-stream). The multipart handler above still wins for
   * `multipart/form-data` by consumes-specificity, so multipart routing is unchanged.
   */
  @PostMapping(value = "/ingest")
  public IngestResponse ingestRaw(
      @RequestHeader(value = "X-Filename", required = false) String fileNameHeader,
      HttpServletRequest request) {
    AuthPrincipal principal = principalOrForbid(request);
    byte[] bytes = readBoundedBody(request, service.maxFileSizeBytes());
    String fileName =
        (fileNameHeader != null && !fileNameHeader.isBlank())
            ? fileNameHeader
            : "raw-" + LocalDateTime.now().format(DEFAULT_NAME_FMT) + ".json";
    return handle(bytes, fileName, principal, "raw", request.getContentType());
  }

  /** Shared post-body pipeline for both variants: log, validate size, delegate to the service. */
  private IngestResponse handle(
      byte[] bytes, String fileName, AuthPrincipal principal, String mode, String contentType) {
    log.info(
        "ingest received: mode={} contentType={} bytes={} token={}/{}",
        mode, contentType, bytes.length, principal.name(), principal.role());
    if (bytes.length == 0) {
      log.warn("ingest rejected: empty body (token={})", principal.name());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty body");
    }
    if (bytes.length > service.maxFileSizeBytes()) {
      log.warn("ingest rejected: file too large ({} bytes, token={})", bytes.length, principal.name());
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file too large");
    }
    return service.ingestUpload(bytes, fileName);
  }

  private AuthPrincipal principalOrForbid(HttpServletRequest request) {
    AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal == null || principal.role() == AuthRole.READER) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "writer role required");
    }
    return principal;
  }

  /**
   * Reads the request body into memory, bounded to {@code maxBytes + 1} so an oversized raw body
   * is not buffered without limit. The one extra byte lets {@link #handle} detect and 413 an
   * over-limit upload; reads stop at the cap, so at most {@code maxBytes + 1} bytes are held.
   */
  private static byte[] readBoundedBody(HttpServletRequest request, long maxBytes) {
    long cap = maxBytes + 1;
    try (InputStream in = request.getInputStream()) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      long total = 0;
      int r;
      while (total < cap
          && (r = in.read(chunk, 0, (int) Math.min(chunk.length, cap - total))) != -1) {
        buffer.write(chunk, 0, r);
        total += r;
      }
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("reading request body failed", e);
    }
  }
}
