package net.sybyline.scarlet.util;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAmount;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.vrchatapi.JSON;
import io.github.vrchatapi.model.FileVersion;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.User;

import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.util.Platform;

public interface MiscUtils
{

    static boolean blank(String string)
    {
        return string == null || string.isEmpty();
    }

    static StringBuilder fmt(StringBuilder sb, String format, Object... params)
    {
        @SuppressWarnings({ "unused", "resource" })
        Formatter fmt = new Formatter(sb).format(format, params);
        return sb;
    }

    static String nonBlankOrNull(String string1)
    {
        return !blank(string1) ? string1 : null;
    }
    static String nonBlankOrNull(String string1, String string2)
    {
        return !blank(string1) ? string1 : !blank(string2) ? string2 : null;
    }
    static String nonBlankOrNull(String string1, String string2, String string3)
    {
        return !blank(string1) ? string1 : !blank(string2) ? string2 : !blank(string3) ? string3 : null;
    }

    static String maybeEllipsis(int maxLength, String string)
    {
        return string == null || string.length() <= maxLength ? string : (string.substring(0, maxLength - 1) + '\u2026');
    }
    static String maybeEllipsisReverse(int maxLength, String string)
    {
        return string == null || string.length() <= maxLength ? string : ('\u2026' + string.substring(maxLength - 1));
    }

    static final ZoneRules DEFAULT_ZONE_RULES = ZoneId.systemDefault().getRules();
    static OffsetDateTime odt2utc(LocalDateTime ldt)
    {
        return OffsetDateTime.of(ldt, DEFAULT_ZONE_RULES.getOffset(ldt)).withOffsetSameInstant(ZoneOffset.UTC);
    }

    static final DateTimeFormatter
        DAY_NAME_FULL = new DateTimeFormatterBuilder().appendText(ChronoField.DAY_OF_WEEK, TextStyle.FULL).toFormatter(),
        MONTH_NAME_FULL = new DateTimeFormatterBuilder().appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL).toFormatter();

    static String ordinalSuffix(long ordinal)
    {
        switch ((int)Math.abs(ordinal % 10L))
        {
        default: return "th";
        case  1: return "st";
        case  2: return "nd";
        case  3: return "rd";
        }
    }

    @SafeVarargs
    static <T> int indexOf(T target, T... array)
    {
        if (array != null)
    for (int i = 0, l = array.length; i < l; i++)
        if (Objects.equals(array[i], target))
            return i;
        return -1;
    }

    static boolean sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException iex)
        {
            return false;
        }
    }

    static void close(AutoCloseable resource)
    {
        if (resource != null) try
        {
            resource.close();
        }
        catch (Exception ex)
        {
            Scarlet.LOG.error("Exception closing resource", ex);
        }
    }
    static void close(Iterable<? extends AutoCloseable> resources)
    {
        if (resources != null) for (AutoCloseable resource : resources) try
        {
            resource.close();
        }
        catch (Exception ex)
        {
            Scarlet.LOG.error("Exception closing resource", ex);
        }
    }

    static int parseIntElse(String string, int fallback)
    {
        try
        {
            return Integer.parseInt(string);
        }
        catch (RuntimeException rex)
        {
            return fallback;
        }
    }

    static double parseDoubleElse(String string, double fallback)
    {
        try
        {
            return Double.parseDouble(string);
        }
        catch (RuntimeException rex)
        {
            return fallback;
        }
    }

    static Map<DiscordLocale, String> genLocalized(Function<DiscordLocale, String> function)
    {
        Map<DiscordLocale, String> map = new HashMap<>();
        for (DiscordLocale discordLocale : DiscordLocale.values())
            if (discordLocale != DiscordLocale.UNKNOWN)
                map.put(discordLocale, function.apply(discordLocale));
        return map;
    }

    static long lastModified(File file) throws IOException
    {
        return Files.getLastModifiedTime(file.toPath()).toMillis();
    }

    static boolean isNewerThan(File file, long epochMillis) throws IOException
    {
        return file.exists() && lastModified(file) > epochMillis;
    }

    static <T, U> U[] map(T[] arr, IntFunction<U[]> nac, Function<T, U> map)
    {
        int len = arr.length;
        U[] ret = nac.apply(len);
        for (int idx = 0; idx < len; idx++)
            ret[idx] = map.apply(arr[idx]);
        return ret;
    }

    @SafeVarargs
    static <T> T[] append(T[] arr, T... vals)
    {
        if (arr == null || arr.length == 0)
            return vals;
        if (vals == null || vals.length == 0)
            return arr;
        int off = arr.length,
            len = vals.length;
        arr = Arrays.copyOf(arr, off + len);
        System.arraycopy(vals, 0, arr, off, len);
        return arr;
    }

    static String extractTypedUuid(String type, String fallback, String idContaining)
    {
        Matcher m = Pattern.compile(type+"_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(idContaining);
        return m.find() ? m.group() : fallback;
    }

    static final String ROBOT_IMAGE_URL = "https://vrchat.com/api/1/file/file_0e8c4e32-7444-44ea-ade4-313c010d4bae/1/file";

    static String userImageUrl(User user)
    {
        // API 1.21.0+ moved the real image fields (profilePicOverride / userIcon /
        // currentAvatarImageUrl) off User and onto PublicProfile, so a bare User rarely carries a
        // usable iconUrl. Prefer ScarletVRChat.getUserImageUrl(userId); this is a User-only fallback.
        String icon = user == null ? null : user.getIconUrl();
        if (icon != null && !icon.trim().isEmpty())
            return icon;
        return ROBOT_IMAGE_URL;
    }

    static String latestContentUrlOrNull(String fileId)
    {
        if (fileId == null || fileId.trim().isEmpty())
            return null;
        int version;
        try (HttpURLInputStream http = HttpURLInputStream.get("https://vrchat.com/api/1/file/"+fileId))
        {
            version = JSON.getGson().fromJson(new InputStreamReader(http, java.nio.charset.StandardCharsets.UTF_8), ModelFile.class).getVersions().stream().mapToInt(FileVersion::getVersion).max().orElse(1);
        }
        catch (Exception ex)
        {
            version = 1;
        }
        return "https://vrchat.com/api/1/file/"+fileId+"/"+version+"/file";
    }

    /**
     * Reads the stream fully but aborts once more than {@code maxBytes} have been read, so a
     * hostile/oversized (e.g. chunked, no-Content-Length) response cannot OOM the process. Use
     * this for any read of an untrusted/user-supplied URL.
     */
    static byte[] readAllBytes(InputStream input, long maxBytes) throws IOException
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, (int)Math.min(maxBytes, Math.max(0, input.available()))));
        byte[] chunk = new byte[8192];
        long total = 0L;
        for (int n; (n = input.read(chunk)) != -1; )
        {
            total += n;
            if (total > maxBytes)
                throw new IOException("Response exceeds maximum allowed size ("+maxBytes+" bytes)");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    static byte[] readAllBytes(InputStream input) throws IOException
    {
        byte[] buffer = new byte[Math.max(1024, input.available())];
        int offset = 0;
        for (int bytesRead; (bytesRead = input.read(buffer, offset, buffer.length - offset)) != -1; )
            if ((offset += bytesRead) == buffer.length)
                buffer = Arrays.copyOf(buffer, buffer.length + Math.max(input.available(), buffer.length >> 1));
        return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
    }

    static String stringify_ymd_hms_ms_us_ns(TemporalAmount amount)
    {
        if (amount == null)
            return null;
        int years,
            months,
            days,
            hours = 0,
            minutes = 0,
            seconds = 0,
            millis = 0,
            micros = 0,
            nanos = 0;
        if (amount instanceof Period)
        {
            Period period = (Period)amount;
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
        }
        else
        {
            Duration duration = amount instanceof Duration ? (Duration)amount : Duration.from(amount);
            Period period = Period.ofDays((int)(duration.getSeconds() / 86400L)).normalized();
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
            seconds = (int)(duration.getSeconds() % 86400L);
            if (seconds >= 60)
            {
                minutes = seconds / 60;
                seconds = seconds % 60;
                if (minutes >= 60)
                {
                    hours = minutes / 60;
                    minutes = minutes % 60;
                }
            }
            nanos = duration.getNano();
            if (nanos >= 1000)
            {
                micros = nanos / 1000;
                nanos = nanos % 1000;
                if (micros >= 1000)
                {
                    millis = micros / 1000;
                    micros = micros % 1000;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (years != 0) sb.append(years).append("y ");
        if (months != 0) sb.append(months).append("m ");
        if (days != 0) sb.append(days).append("d ");
        sb.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        if (millis != 0 || micros != 0 || nanos != 0) sb.append(String.format(".%03d", millis));
        if (micros != 0 || nanos != 0) sb.append(String.format(".%03d", micros));
        if (nanos != 0) sb.append(String.format(".%03d", nanos));
        return sb.toString();
    }

    static String stringify_ymd_hms(TemporalAmount amount)
    {
        if (amount == null)
            return null;
        int years,
            months,
            days,
            hours = 0,
            minutes = 0,
            seconds = 0;
        if (amount instanceof Period)
        {
            Period period = (Period)amount;
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
        }
        else
        {
            Duration duration = amount instanceof Duration ? (Duration)amount : Duration.from(amount);
            Period period = Period.ofDays((int)(duration.getSeconds() / 86400L)).normalized();
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
            seconds = (int)(duration.getSeconds() % 86400L);
            if (seconds >= 60)
            {
                minutes = seconds / 60;
                seconds = seconds % 60;
                if (minutes >= 60)
                {
                    hours = minutes / 60;
                    minutes = minutes % 60;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (years != 0) sb.append(years).append("y ");
        if (months != 0) sb.append(months).append("m ");
        if (days != 0) sb.append(days).append("d ");
        sb.append(String.format("%02d:%02d:%02d ", hours, minutes, seconds));
        return sb.length() == 0 ? "0s" : sb.substring(0, sb.length() - 1);
    }

    static String stringify_ymd(TemporalAmount amount)
    {
        if (amount == null)
            return null;
        int years,
            months,
            days;
        if (amount instanceof Period)
        {
            Period period = (Period)amount;
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
        }
        else
        {
            Duration duration = amount instanceof Duration ? (Duration)amount : Duration.from(amount);
            Period period = Period.ofDays((int)(duration.getSeconds() / 86400L)).normalized();
            years = period.getYears();
            months = period.getMonths();
            days = period.getDays();
        }
        StringBuilder sb = new StringBuilder();
        if (years != 0) sb.append(years).append("y ");
        if (months != 0) sb.append(months).append("m ");
        if (days != 0) sb.append(days).append("d ");
        return sb.length() == 0 ? "0D" : sb.substring(0, sb.length() - 1);
    }

    static int bin2b64(int len_bin)
    {
        if (len_bin <= 0) return 0;
        int mod = len_bin % 3;
        return (len_bin / 3) * 4 + (mod == 0 ? 0 : (mod + 1));
    }
    static int b642bin(int len_b64)
    {
        if (len_b64 <= 0) return 0;
        int mod = len_b64 & 0x03;
        return (len_b64 / 4) * 3 + (mod == 0 ? 0 : (mod - 1));
    }

    static Runnable withMinimumInterval(long minimumIntervalMillis, Runnable runnable)
    {
        if (runnable == null)
            throw new IllegalArgumentException("runnable == null");
        long minIntervalMs = Math.max(0L, minimumIntervalMillis);
        AtomicLong prev = new AtomicLong(System.currentTimeMillis());
        return () ->
        {
            long nowEpochMs = System.currentTimeMillis(),
                 prevEpochMs = prev.get();
            if (nowEpochMs - prevEpochMs >= minIntervalMs && prev.compareAndSet(prevEpochMs, nowEpochMs))
                runnable.run();
        };
    }

    static Pattern SEMVER = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)([\\.\\-_]?(?<kind>\\w+))?([\\.\\-_]?(?<build>\\d+))?");
    static Comparator<String> SEMVER_CMP_OLDEST_FIRST = MiscUtils::compareSemVer,
                              SEMVER_CMP_NEWEST_FIRST = SEMVER_CMP_OLDEST_FIRST.reversed();
    /**
     * Comparator pair using {@link #compareScarletVersion(String, String)} —
     * a suffixed version (e.g. "0.4.17-b2") is treated as <em>newer</em> than
     * the bare release ("0.4.17"), the convention used by Scarlet for its own
     * fork builds.
     */
    public static final Comparator<String> SCARLET_VERSION_CMP_OLDEST_FIRST = MiscUtils::compareScarletVersion,
                                           SCARLET_VERSION_CMP_NEWEST_FIRST = SCARLET_VERSION_CMP_OLDEST_FIRST.reversed();
    static boolean isValidVersion(String v)
    {
        return v != null && SEMVER.matcher(v).matches();
    }
    static boolean isPreviewVersion(String v)
    {
        if (v == null)
            return false;
        Matcher vm = SEMVER.matcher(v);
        return vm.matches() && (vm.group("kind") != null || vm.group("build") != null);
    }
    static int compareSemVer(String l, String r)
    {
        try
        {
            if (l == null)
                return r == null ? 0 : 1;
            if (r == null)
                return -1;
            Matcher lm = SEMVER.matcher(l),
                    rm = SEMVER.matcher(r);
            if (!lm.matches())
                return !rm.matches() ? l.compareTo(r) : 1;
            if (!rm.matches())
                return -1;
            
            int li, ri, cmp;
            
            li = Integer.parseInt(lm.group("major"));
            ri = Integer.parseInt(rm.group("major"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;
            
            li = Integer.parseInt(lm.group("minor"));
            ri = Integer.parseInt(rm.group("minor"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;
            
            li = Integer.parseInt(lm.group("patch"));
            ri = Integer.parseInt(rm.group("patch"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;
            
            String lx = lm.group("kind"),
                   rx = rm.group("kind");
            
            cmp = lx == null
                ? rx == null
                    ? 0
                    : 1
                : rx == null
                    ? -1
                    : lx.compareTo(rx);
            if (cmp != 0) return cmp;

            lx = lm.group("build");
            rx = rm.group("build");
            
            li = lx == null ? Integer.MAX_VALUE : Integer.parseInt(lx);
            ri = rx == null ? Integer.MAX_VALUE : Integer.parseInt(rx);
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;
        }
        catch (RuntimeException rex)
        {
            // ignore
        }
        return 0;
    }

    /**
     * Scarlet-specific version comparison. Standard semver (see
     * {@link #compareSemVer(String, String)}) treats "0.4.17-b2" as a
     * pre-release of "0.4.17" and therefore older. For Scarlet Logger's
     * own update notifications we instead want the suffix to mean "iteration
     * ahead of the bare release" - so "0.4.17-b2" is <em>newer</em> than
     * "0.4.17", and "0.4.17-b3" is newer than "0.4.17-b2".
     *
     * <p>Returns a value &lt; 0 when {@code l} is older than {@code r}, &gt; 0
     * when newer, 0 when equal. Suitable for direct use as a {@link Comparator}
     * (oldest first); reverse it for newest-first.
     */
    public static int compareScarletVersion(String l, String r)
    {
        try
        {
            if (l == null)
                return r == null ? 0 : -1;
            if (r == null)
                return 1;
            Matcher lm = SEMVER.matcher(l),
                    rm = SEMVER.matcher(r);
            if (!lm.matches())
                return !rm.matches() ? l.compareTo(r) : -1;
            if (!rm.matches())
                return 1;

            int li, ri, cmp;

            li = Integer.parseInt(lm.group("major"));
            ri = Integer.parseInt(rm.group("major"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;

            li = Integer.parseInt(lm.group("minor"));
            ri = Integer.parseInt(rm.group("minor"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;

            li = Integer.parseInt(lm.group("patch"));
            ri = Integer.parseInt(rm.group("patch"));
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;

            String lx = lm.group("kind"),
                   rx = rm.group("kind");
            // Inverted vs. compareSemVer: a suffix means "ahead of the
            // bare release" instead of "pre-release of it".
            // Starting with 0.6.1, previews precede the final stable release.
            // Preserve legacy ordering for previously delivered release series.
            boolean previewSeries = Integer.parseInt(lm.group("major")) > 0
                || Integer.parseInt(lm.group("minor")) > 6
                || Integer.parseInt(lm.group("minor")) == 6 && Integer.parseInt(lm.group("patch")) >= 1;
            if (previewSeries && (lx == null || rx == null) && !Objects.equals(lx, rx))
                return lx == null ? 1 : -1;
            cmp = lx == null
                ? rx == null
                    ? 0
                    : -1
                : rx == null
                    ? 1
                    : compareScarletVersionKind(lx, rx);
            if (cmp != 0) return cmp;

            lx = lm.group("build");
            rx = rm.group("build");
            // Attached iteration numbers (a9, a10) were compared numerically
            // above. This branch handles a separate numeric build group.
            li = lx == null ? 0 : Integer.parseInt(lx);
            ri = rx == null ? 0 : Integer.parseInt(rx);
            if ((cmp = Integer.compare(li, ri)) != 0) return cmp;
        }
        catch (RuntimeException rex)
        {
            // ignore — fall through to "equal" so a malformed version never
            // produces a spurious update prompt
        }
        return 0;
    }

    /** Compare Scarlet suffix labels and their trailing iteration numbers. */
    static int compareScarletVersionKind(String l, String r)
    {
        boolean lt = l.matches("T[0-9]+"), rt = r.matches("T[0-9]+");
        if (lt != rt) return lt ? 1 : -1;
        int li = l.length(), ri = r.length();
        while (li > 0 && l.charAt(li - 1) >= '0' && l.charAt(li - 1) <= '9') li--;
        while (ri > 0 && r.charAt(ri - 1) >= '0' && r.charAt(ri - 1) <= '9') ri--;
        // Compare prefixes consistently, including labels without a trailing
        // number, so mixed labels cannot create a non-transitive ordering.
        int cmp = l.substring(0, li).compareTo(r.substring(0, ri));
        if (cmp != 0) return cmp;
        boolean ln = li < l.length(), rn = ri < r.length();
        if (!ln || !rn) return Boolean.compare(ln, rn);
        return new java.math.BigInteger(l.substring(li)).compareTo(new java.math.BigInteger(r.substring(ri)));
    }

    public static List<String> paginateOnLines(StringBuilder input, int maxComponentLen)
    {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < input.length())
        {
            int end = start + maxComponentLen;
            if (end >= input.length())
            {
                parts.add(input.substring(start).trim());
                start = input.length();
            }
            else
            {
                int lastNewline = input.lastIndexOf("\n", end);
                if (lastNewline > start && lastNewline < end)
                {
                    parts.add(input.substring(start, lastNewline).trim());
                    start = lastNewline + 1;
                }
                else
                {
                    parts.add(input.substring(start, end).trim());
                    start = end;
                }
            }
        }
        return parts;
    }

    static Writer writer(File file) throws FileNotFoundException
    {
        return new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
    }

    static Reader reader(File file) throws FileNotFoundException
    {
        return new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
    }

    static Color lerp(Color lhs, Color rhs, float lerp)
    {
        lerp = Math.max(0, Math.min(1, lerp));
        int r = (int)(lhs.getRed()   + lerp * (rhs.getRed()   - lhs.getRed()));
        int g = (int)(lhs.getGreen() + lerp * (rhs.getGreen() - lhs.getGreen()));
        int b = (int)(lhs.getBlue()  + lerp * (rhs.getBlue()  - lhs.getBlue()));
        int a = (int)(lhs.getAlpha() + lerp * (rhs.getAlpha() - lhs.getAlpha()));
        return new Color(r, g, b, a);
    }

    interface AWTToolkit
    {
        static void set(String text)
        {
            try
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), (c, t) -> {});
            }
            catch (Exception ex)
            {
            }
        }
        static String get()
        {
            try
            {
                Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor))
                    return (String)t.getTransferData(DataFlavor.stringFlavor);
            }
            catch (Exception ex)
            {
            }
            return null;
        }
    }

    interface AWTDesktop
    {
        static boolean open(File file)
        {
            // Try AWT Desktop first (works reliably on Windows/macOS)
            if (Desktop.isDesktopSupported()) try
            {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN))
                {
                    desktop.open(file);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Scarlet.LOG.error("Exception opening file "+file, ex);
            }
            // Linux fallback: xdg-open
            if (Platform.CURRENT.is$nix())
            {
                try
                {
                    new ProcessBuilder("xdg-open", file.getAbsolutePath())
                        .inheritIO()
                        .start();
                    return true;
                }
                catch (Exception ex)
                {
                    Scarlet.LOG.error("Exception opening file via xdg-open "+file, ex);
                }
            }
            Scarlet.LOG.warn("Desktop OPEN action is not supported on this platform");
            return false;
        }
        static boolean edit(File file)
        {
            // Try AWT Desktop EDIT first (works when a file-type editor is registered)
            if (Desktop.isDesktopSupported()) try
            {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.EDIT))
                {
                    desktop.edit(file);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Scarlet.LOG.warn("Desktop EDIT failed for {} ({}), trying OPEN fallback", file.getName(), ex.getMessage());
            }
            // Fall back to OPEN — on Windows this uses the default app for the
            // file extension (Notepad for .json if nothing else is registered).
            if (Desktop.isDesktopSupported()) try
            {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN))
                {
                    desktop.open(file);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Scarlet.LOG.warn("Desktop OPEN fallback also failed for {} ({})", file.getName(), ex.getMessage());
            }
            // Windows last resort: launch notepad.exe directly
            if (Platform.CURRENT.isNT()) try
            {
                new ProcessBuilder("notepad.exe", file.getAbsolutePath())
                    .inheritIO()
                    .start();
                return true;
            }
            catch (Exception ex)
            {
                Scarlet.LOG.error("Exception launching notepad.exe for "+file, ex);
            }
            // Linux: xdg-open opens with the default text editor
            if (Platform.CURRENT.is$nix())
            {
                try
                {
                    new ProcessBuilder("xdg-open", file.getAbsolutePath())
                        .inheritIO()
                        .start();
                    return true;
                }
                catch (Exception ex)
                {
                    Scarlet.LOG.error("Exception editing file via xdg-open "+file, ex);
                }
            }
            Scarlet.LOG.warn("Desktop EDIT action is not supported on this platform");
            return false;
        }
        static boolean browse(URI uri)
        {
            // Try AWT Desktop first (works reliably on Windows/macOS)
            if (Desktop.isDesktopSupported()) try
            {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE))
                {
                    desktop.browse(uri);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Scarlet.LOG.error("Exception browsing to uri "+uri, ex);
            }
            // Linux fallback: try xdg-open, then common browsers
            if (Platform.CURRENT.is$nix())
            {
                String[] commands = isBrowserHandledScheme(uri)
                    ? new String[]{"xdg-open", "sensible-browser", "x-www-browser", "firefox", "chromium-browser", "google-chrome"}
                    : new String[]{"xdg-open"};
                for (String cmd : commands)
                {
                    final String finalCmd = cmd;
                    try
                    {
                        new ProcessBuilder(finalCmd, uri.toString())
                            .inheritIO()
                            .start();
                        Scarlet.LOG.info("Opened URI via "+finalCmd+": "+uri);
                        return true;
                    }
                    catch (IOException ex)
                    {
                        // This command not found, try next
                    }
                    catch (Exception ex)
                    {
                        Scarlet.LOG.error("Exception browsing to uri via "+finalCmd+" "+uri, ex);
                    }
                }
            }
            Scarlet.LOG.warn("Desktop BROWSE action is not supported on this platform");
            return false;
        }
        static boolean isBrowserHandledScheme(URI uri)
        {
            String scheme = uri == null ? null : uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme);
        }

        /**
         * Opens a directory in the platform's native file manager.
         *
         * <p>Priority order:
         * <ol>
         *   <li>AWT {@code Desktop.open()} — opens Explorer on Windows,
         *       the default file manager on macOS, and whatever is registered
         *       for directories via {@code xdg-open} on Linux.</li>
         *   <li>Windows: {@code explorer.exe} directly.</li>
         *   <li>Linux: {@code xdg-open}, then common file managers
         *       (Nautilus, Dolphin, Thunar, Nemo, PCManFM, Caja).</li>
         *   <li>Last resort: {@code browse(dir.toURI())} — opens in a web
         *       browser if no file manager is available.</li>
         * </ol>
         *
         * @param dir the directory to reveal in the file manager
         * @return true if the directory was opened successfully
         */
        static boolean browseDirectory(File dir)
        {
            // Attempt 1: AWT Desktop.open() — works for directories on all
            // platforms and correctly invokes the file manager, not a browser.
            if (Desktop.isDesktopSupported()) try
            {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN))
                {
                    desktop.open(dir);
                    Scarlet.LOG.info("Opened directory via Desktop.open(): {}", dir);
                    return true;
                }
            }
            catch (Exception ex)
            {
                Scarlet.LOG.warn("Desktop.open() failed for directory {}: {}", dir, ex.getMessage());
            }

            // Attempt 2: Windows — call explorer.exe directly
            if (Platform.CURRENT.isNT()) try
            {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath())
                    .inheritIO()
                    .start();
                Scarlet.LOG.info("Opened directory via explorer.exe: {}", dir);
                return true;
            }
            catch (Exception ex)
            {
                Scarlet.LOG.warn("explorer.exe failed for {}: {}", dir, ex.getMessage());
            }

            // Attempt 3: Linux — xdg-open, then named file managers
            if (Platform.CURRENT.is$nix())
            {
                for (String cmd : new String[]{
                    "xdg-open",       // standard freedesktop launcher
                    "nautilus",       // GNOME
                    "dolphin",        // KDE
                    "thunar",         // XFCE
                    "nemo",           // Cinnamon
                    "pcmanfm",        // LXDE
                    "caja",           // MATE
                })
                {
                    try
                    {
                        new ProcessBuilder(cmd, dir.getAbsolutePath())
                            .inheritIO()
                            .start();
                        Scarlet.LOG.info("Opened directory via {}: {}", cmd, dir);
                        return true;
                    }
                    catch (IOException ex)
                    {
                        // Command not available — try next
                    }
                    catch (Exception ex)
                    {
                        Scarlet.LOG.warn("'{}' failed for {}: {}", cmd, dir, ex.getMessage());
                    }
                }
            }

            // Last resort: open as a file:// URI in whatever handles it
            Scarlet.LOG.warn("No file manager found; falling back to browser for directory: {}", dir);
            return browse(dir.toURI());
        }
    }

    /**
     * Computes the Levenshtein (edit) distance between two strings.
     * Drop-in replacement for commons-text LevenshteinDistance.getDefaultInstance().apply(a, b).
     * Uses a single-row DP approach — O(min(m,n)) space.
     */
    public static int levenshtein(String a, String b)
    {
        if (a == null) a = "";
        if (b == null) b = "";
        // Keep b as the shorter string to minimise memory
        if (a.length() < b.length()) { String tmp = a; a = b; b = tmp; }
        int bLen = b.length();
        int[] prev = new int[bLen + 1];
        for (int j = 0; j <= bLen; j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++)
        {
            int[] curr = new int[bLen + 1];
            curr[0] = i;
            for (int j = 1; j <= bLen; j++)
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1)
                    ? prev[j - 1]
                    : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            prev = curr;
        }
        return prev[bLen];
    }

    /**
     * Parses an Excel-dialect CSV (RFC 4180: comma separator, double-quote quoting, "" escape).
     * Drop-in replacement for: CSVFormat.EXCEL.parse(reader)
     * Each returned String[] is one row; fields are accessible by index (like CSVRecord.get(n)).
     * Empty trailing fields are preserved. The reader is not closed.
     */
    public static Iterable<String[]> parseExcelCsv(java.io.Reader reader) throws java.io.IOException
    {
        java.io.BufferedReader br = reader instanceof java.io.BufferedReader
            ? (java.io.BufferedReader) reader
            : new java.io.BufferedReader(reader);
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        String line;
        while ((line = br.readLine()) != null)
        {
            // Skip completely blank lines (Excel CSVFormat skips them too)
            if (line.isEmpty()) continue;
            java.util.List<String> fields = new java.util.ArrayList<>();
            int i = 0, len = line.length();
            while (i <= len)
            {
                if (i < len && line.charAt(i) == '"')
                {
                    // Quoted field — consume until closing quote, "" → "
                    StringBuilder sb = new StringBuilder();
                    i++; // skip opening quote
                    while (i < len)
                    {
                        char c = line.charAt(i++);
                        if (c == '"')
                        {
                            if (i < len && line.charAt(i) == '"') { sb.append('"'); i++; } // escaped ""
                            else break; // closing quote
                        }
                        else sb.append(c);
                    }
                    fields.add(sb.toString());
                    // skip comma after closing quote (or we're at end)
                    if (i < len && line.charAt(i) == ',') i++;
                    else i = len + 1; // signal end
                }
                else
                {
                    // Unquoted field — read until next comma or end-of-line
                    int start = i;
                    while (i < len && line.charAt(i) != ',') i++;
                    fields.add(line.substring(start, i));
                    if (i < len) i++; // skip comma
                    else i = len + 1; // signal end
                }
            }
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }

}
