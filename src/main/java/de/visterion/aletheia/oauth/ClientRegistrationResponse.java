package de.visterion.aletheia.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * RFC 7591 §3.2.1 — registration response. {@code @JsonInclude(NON_NULL)} keeps optional fields
 * out when empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientRegistrationResponse(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_id_issued_at") long clientIdIssuedAt,
    @JsonProperty("client_name") String clientName,
    @JsonProperty("redirect_uris") List<String> redirectUris,
    @JsonProperty("grant_types") List<String> grantTypes,
    @JsonProperty("response_types") List<String> responseTypes,
    @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
    @JsonProperty("scope") String scope,
    @JsonProperty("client_uri") String clientUri,
    @JsonProperty("logo_uri") String logoUri,
    @JsonProperty("contacts") List<String> contacts,
    @JsonProperty("software_id") String softwareId,
    @JsonProperty("software_version") String softwareVersion) {}
