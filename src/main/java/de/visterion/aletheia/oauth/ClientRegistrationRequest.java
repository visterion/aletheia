package de.visterion.aletheia.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * RFC 7591 Client Registration Request body. Only fields Aletheia reads are deserialized;
 * unknown fields are ignored by Jackson default config.
 */
public record ClientRegistrationRequest(
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
