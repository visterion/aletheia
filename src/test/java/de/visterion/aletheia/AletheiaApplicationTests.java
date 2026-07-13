package de.visterion.aletheia;

import org.junit.jupiter.api.Test;

/**
 * Placeholder so the test module compiles before the schema/ingest work lands.
 * A real {@code @SpringBootTest} context test is intentionally deferred until the
 * Postgres schema (derived from the actual Subsembly export) exists, so this does
 * not silently pass against an empty context.
 */
class AletheiaApplicationTests {

    @Test
    void skeletonCompiles() {
        // No-op: proves the build wiring is sound. Replaced by a Testcontainers
        // context test once db/migration holds the first real schema.
    }
}
