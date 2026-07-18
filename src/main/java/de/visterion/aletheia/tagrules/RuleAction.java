package de.visterion.aletheia.tagrules;

/** One rule action {@code {dimension, value}} (spec §3): set tag {@code value} on {@code dimension}. */
public record RuleAction(String dimension, String value) {}
