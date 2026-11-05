package net.sybyline.scarlet.log;

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.util.MavenDepsLoader;

public class ScarletLogger extends LegacyAbstractLogger
{

    // Offline migration must not create or append files inside the snapshot it owns.
    private static volatile boolean consoleOnly = Boolean.getBoolean("scarlet.offline");
    private static final java.util.concurrent.atomic.AtomicLong errors = new java.util.concurrent.atomic.AtomicLong();

    public static long errorCount() { return errors.get(); }
    public static boolean awaitConsoleIdle(long timeoutMillis) { return ConsoleOutput.sink.awaitIdle(timeoutMillis); }

    public static void setConsoleOnly(boolean value)
    {
        consoleOnly = value;
    }

    private static final long serialVersionUID = -875763706664943747L;

    public static final String linesep = String.format("%n");
    public static final Pattern lfpattern = Pattern.compile("\\Ascarlet_log_\\d\\d\\d\\d-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d\\.txt\\z"),
                                errpkg = Pattern.compile("^(?<ws>\\s*at\\s+)(?<pkg>net\\.(?:sybyline|hatson321)\\.scarlet(\\.[\\w$]+)*)\\.(?<cls>[\\w$]+)\\.(?<mth>[\\w$<>]+)\\((?<file>[\\w$]+\\.java)\\:(?<line>\\d+)\\)$");

    /** Returns the active file, or null while offline/startup logging is console-only. */
    public static File getActiveLogFile()
    {
        return consoleOnly ? null : FileOutput.logFile;
    }
    public static final Level level = level0();
    private static final boolean is_error = level.compareTo(Level.ERROR) >= 0,
                                 is_warn = level.compareTo(Level.WARN) >= 0,
                                 is_info = level.compareTo(Level.INFO) >= 0,
                                 is_debug = level.compareTo(Level.DEBUG) >= 0,
                                 is_trace = level.compareTo(Level.TRACE) >= 0;
    private static Level level0()
    {
        try
        {
            String configured = System.getProperty("net.sybyline.scarlet.log.level");
            if (configured == null)
                configured = System.getProperty("net.hatson321.scarlet.log.level", "DEBUG");
            return Level.valueOf(configured);
        }
        catch (Exception ex)
        {
            return Level.DEBUG;
        }
    }

    public ScarletLogger()
    {
        this("");
    }

    public ScarletLogger(String name)
    {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled()
    {
        return is_trace;
    }

    @Override
    public boolean isDebugEnabled()
    {
        return is_debug;
    }

    @Override
    public boolean isInfoEnabled()
    {
        return is_info;
    }

    @Override
    public boolean isWarnEnabled()
    {
        return is_warn;
    }

    @Override
    public boolean isErrorEnabled()
    {
        return is_error;
    }

    @Override
    protected String getFullyQualifiedCallerName()
    {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable)
    {
        if (level == Level.ERROR) errors.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        FormattingTuple result = MessageFormatter.arrayFormat(messagePattern, arguments);
        PrintStream stream = level == Level.ERROR ? System.err : System.out;
        String name = marker == null ? this.name : (this.name + "/" + marker.getName());
        String logMessage = String.format("%1$tF %1$tT [%2$s] [%3$s] %4$s%n", now, level, name, result.getMessage());

        if (throwable != null)
        {
            LimitedWriter stack = new LimitedWriter();
            try { throwable.printStackTrace(new PrintWriter(stack)); }
            catch (StackLimit reached) { stack.text.append("\n[stack trace truncated]\n"); }
            String trace = stack.text.toString();
            if (MavenDepsLoader.jarPath() != null)
                trace = Arrays.stream(trace.split("\\R")).map(line -> {
                    Matcher match = errpkg.matcher(line);
                    if (!match.find()) return line;
                    return match.group("ws") + match.group("pkg") + "." + match.group("cls") + "." + match.group("mth")
                        + "([" + match.group("file") + ":" + match.group("line") + "](" + Scarlet.GITHUB_URL + "/tree/" + Scarlet.VERSION
                        + "/src/main/java/" + match.group("pkg").replace('.', '/') + "/" + match.group("file") + "#L" + match.group("line") + "))";
                }).collect(Collectors.joining(linesep));
            logMessage += trace + linesep;
        }
        boolean important = level == Level.ERROR || level == Level.WARN;
        ConsoleOutput.sink.offer(logMessage, important, level == Level.ERROR, stream);
        if (!consoleOnly) FileOutput.sink.offer(logMessage, important, level == Level.ERROR);
    }

    static final class StackLimit extends RuntimeException { private static final long serialVersionUID = 1L; }
    static final class LimitedWriter extends java.io.Writer
    {
        final StringBuilder text = new StringBuilder();
        @Override public void write(char[] chars, int offset, int length)
        {
            int remaining = BoundedLogSink.MAX_ENTRY - this.text.length();
            this.text.append(chars, offset, Math.min(remaining, length));
            if (length > remaining) throw new StackLimit();
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    static final class ConsoleOutput
    {
        static final BoundedLogSink sink = new BoundedLogSink("Scarlet console logging", 512, 1024 * 1024,
            entry -> (entry.stream != null ? entry.stream : entry.error ? System.err : System.out).print(entry.text), () -> {});
        static { Runtime.getRuntime().addShutdownHook(new Thread(() -> sink.close(1000), "Scarlet console drain")); }
    }
    static final class FileOutput
    {
        static final File logFile = new File(Scarlet.dir, "logs/scarlet_log_" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now()) + ".txt");
        static PrintStream stream;
        static final BoundedLogSink sink = new BoundedLogSink("Scarlet file logging", 2048, 4 * 1024 * 1024, entry -> {
            try
            {
                if (stream == null)
                {
                    java.nio.file.Files.createDirectories(logFile.toPath().getParent());
                    stream = new PrintStream(logFile, "UTF-8");
                }
                stream.print(entry.text); stream.flush();
                if (stream.checkError()) throw new IllegalStateException("Log file write failed");
            }
            catch (java.io.IOException failure) { throw new IllegalStateException("Could not open log file", failure); }
        }, () -> { if (stream != null) stream.close(); });
        static { Runtime.getRuntime().addShutdownHook(new Thread(() -> sink.close(1000), "Scarlet file drain")); }
    }
}
