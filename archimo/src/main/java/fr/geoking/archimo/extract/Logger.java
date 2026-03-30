package fr.geoking.archimo.extract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modern logger with emoji and indentation support.
 */
public final class Logger {

    private static final boolean SUPPORTS_EMOJI = isEmojiSupported();

    private static boolean isEmojiSupported() {
        if (!java.nio.charset.Charset.defaultCharset().name().toUpperCase().contains("UTF-8")) {
            return false;
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return System.getenv("WT_SESSION") != null || 
                   System.getenv("TERMINAL_EMULATOR") != null ||
                   "vscode".equals(System.getenv("TERM_PROGRAM")) ||
                   "mintty".equals(System.getenv("TERM_PROGRAM"));
        }
        return true;
    }

    private static Logger instance = new Logger(false, null);

    private final boolean verbose;
    private final File logFile;
    private final AtomicInteger indentationLevel = new AtomicInteger(0);

    public Logger(boolean verbose, File logFile) {
        this.verbose = verbose;
        this.logFile = logFile;
    }

    public static void setInstance(Logger logger) {
        instance = logger;
    }

    public static Logger getInstance() {
        return instance;
    }

    public void indent() {
        indentationLevel.incrementAndGet();
    }

    public void unindent() {
        if (indentationLevel.get() > 0) {
            indentationLevel.decrementAndGet();
        }
    }

    public void info(String msg) {
        log(SUPPORTS_EMOJI ? "ℹ️" : "[INFO]", msg, false);
        logToDisk("INFO: " + msg);
    }

    public void debug(String msg) {
        if (verbose) {
            log(SUPPORTS_EMOJI ? "🔍" : "[DEBUG]", msg, false);
        }
        logToDisk("DEBUG: " + msg);
    }

    public void warn(String msg) {
        log(SUPPORTS_EMOJI ? "⚠️" : "[WARN]", msg, true);
        logToDisk("WARN: " + msg);
    }

    public void error(String msg) {
        log(SUPPORTS_EMOJI ? "❌" : "[ERROR]", msg, true);
        logToDisk("ERROR: " + msg);
    }

    public void error(String msg, Throwable t) {
        log(SUPPORTS_EMOJI ? "❌" : "[ERROR]", msg, true);
        t.printStackTrace(System.err);
        logToDisk("ERROR: " + msg);
        if (logFile != null) {
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(logFile, true))) {
                t.printStackTrace(pw);
            } catch (IOException ignored) {}
        }
    }

    public void success(String msg) {
        log(SUPPORTS_EMOJI ? "✅" : "[OK]", msg, false);
        logToDisk("SUCCESS: " + msg);
    }

    private void log(String emoji, String msg, boolean error) {
        String prefix = "  ".repeat(indentationLevel.get());
        String formatted = String.format("%s %s %s", emoji, prefix, msg);
        if (error) {
            System.err.println(formatted);
        } else {
            System.out.println(formatted);
        }
    }

    private synchronized void logToDisk(String msg) {
        if (logFile != null) {
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(logFile, true))) {
                pw.println(msg);
            } catch (IOException ignored) {}
        }
    }
}
