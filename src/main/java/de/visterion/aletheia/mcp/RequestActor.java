package de.visterion.aletheia.mcp;

import de.visterion.aletheia.auth.AuthFilter;
import de.visterion.aletheia.auth.AuthPrincipal;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The calling principal's name, for the {@code actor} column of {@code counterparty_history}.
 *
 * <p>Resolved from the request attribute {@link AuthFilter} sets. Every MCP tool runs on the
 * request thread, so the principal is available to any service a handler delegates to -- a service
 * that hard-codes its own tool name into {@code actor} discards the one piece of information the
 * audit column exists for. Falls back to {@code "unknown"} for direct (non-HTTP) callers such as
 * integration tests that invoke a bean's methods without a request in flight.
 *
 * <p>Extracted rather than copied a third time: the same eight lines already lived in {@code
 * WriteTools} and {@code CounterpartyMergeService}, and this repository's V18 slice exists because
 * one formula was copied twenty times and then drifted.
 */
public final class RequestActor {

  private RequestActor() {}

  public static String current() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return "unknown";
    }
    Object principal =
        attributes.getAttribute(AuthFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return principal instanceof AuthPrincipal authPrincipal ? authPrincipal.name() : "unknown";
  }
}
