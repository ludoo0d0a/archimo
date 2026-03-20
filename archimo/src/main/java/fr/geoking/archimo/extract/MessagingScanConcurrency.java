package fr.geoking.archimo.extract;

/**
 * How {@link MessagingScanner} parallelizes work across application classes.
 */
public enum MessagingScanConcurrency {

    /**
     * Use virtual threads when the JVM supports them (Java 21+), otherwise a bounded platform thread pool.
     */
    AUTO,

    /**
     * Prefer {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} when available;
     * otherwise fall back like {@link #AUTO}.
     */
    VIRTUAL,

    /**
     * Bounded platform thread pool only (no virtual threads).
     */
    PLATFORM;

    /**
     * @param raw CLI value (case-insensitive): auto, virtual, platform
     */
    public static MessagingScanConcurrency parseCli(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: Invalid --messaging-scan-concurrency value '" + raw + "'. Using AUTO.");
            return AUTO;
        }
    }
}
