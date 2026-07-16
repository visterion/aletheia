package de.visterion.aletheia.ingest;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import de.visterion.aletheia.auth.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** HTTP entry point for the Subsembly export upload pipeline (spec §7). */
@RestController
public class IngestController {

  private final IngestEndpointService service;

  public IngestController(IngestEndpointService service) {
    this.service = service;
  }

  @PostMapping("/ingest")
  public IngestResponse ingest(
      @RequestParam("file") MultipartFile file, HttpServletRequest request) {
    AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal == null || principal.role() == AuthRole.READER) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "writer role required");
    }
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file part");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (bytes.length > service.maxFileSizeBytes()) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file too large");
    }
    return service.ingestUpload(bytes, file.getOriginalFilename());
  }
}
