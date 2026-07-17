package de.visterion.aletheia.substrate;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PayPal recognition config (#29). {@code creditorIds} is the allowlist of PayPal SEPA creditor
 * IDs (region-extensible via config — PayPal Europe, PayPal US, …). {@code @DefaultValue} binds
 * an empty list when the property is absent (absent constructor-bound {@code List} binds to
 * null otherwise), so the attribution stage is simply inert until configured. No literal
 * creditor ID is committed; prod supplies it via config.
 */
@ConfigurationProperties(prefix = "aletheia.paypal")
public record PayPalProperties(@DefaultValue List<String> creditorIds) {}
