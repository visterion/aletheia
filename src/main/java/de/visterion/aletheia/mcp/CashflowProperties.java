package de.visterion.aletheia.mcp;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Payer-pinned income override for {@code cashflow}: counterparty identity values (creditor-id /
 * IBAN of the Landesoberkasse/Beihilfestelle) whose bookings are household income in both
 * directions. Empty by default (no literal value committed; prod sets the real ids via env).
 */
@ConfigurationProperties(prefix = "aletheia.cashflow")
public record CashflowProperties(@DefaultValue List<String> incomePayerIds) {}
