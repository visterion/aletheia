package de.visterion.aletheia.substrate;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Process-wide serialization lock for substrate mutations that run the resolvers (HTTP ingest and
 * the {@code reattribute_transaction} MCP tool, #43). Callers acquire it <b>before</b> starting any
 * database work and release it in a {@code finally} block, so a JVM-lock/DB-row-lock deadlock
 * cannot form. Serialising the multi-row {@code ON CONFLICT} upserts of the resolvers also avoids
 * concurrent-upsert deadlocks between an ingest and a reattribution.
 */
@Component
public class SubstrateLock {

  private final ReentrantLock lock = new ReentrantLock();

  public void lock() {
    lock.lock();
  }

  public void unlock() {
    lock.unlock();
  }
}
