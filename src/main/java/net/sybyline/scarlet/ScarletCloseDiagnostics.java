package net.sybyline.scarlet;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import io.github.vrchatapi.ApiException;

/** Bounded, deliberately lossy diagnostics; never a verbatim response or exception dump. */
final class ScarletCloseDiagnostics
{
    private static final int BODY_LIMIT = 16384, MESSAGE_LIMIT = 2048, TOKEN_LIMIT = 512, DEPTH_LIMIT = 8;
    private static final String REDACTED = "[redacted]";
    private static final String ALREADY_CLOSED = "You're not allowed to close an already closed instance.";
    private static final Set<String> WORDS = new HashSet<>(Arrays.asList((
        "a an the this that these those i you you're your it its is isn't are aren't was were be been being "
      + "not no never yes to of for from on in at by with without and or but as if when then than only "
      + "can cannot can't could couldn't may must should do don't does doesn't did didn't have has had "
      + "allowed allow denied deny forbidden unauthorized unauthenticated authenticated authentication "
      + "permission permissions required missing invalid expired revoked unavailable unsupported unknown "
      + "instance instances group groups owner ownership member members moderator account user users "
      + "close closed closing closure already open opened scheduled request requests requested rejected "
      + "failed failure error found find exist exists deleted removed access manage management operation "
      + "too many quickly fast slow down wait retry later rate limit limited exceeded timeout timed out "
      + "server service internal temporarily temporary busy please try again before after currently "
      + "resource conflict bad valid value parameter parameters world worlds expired credentials "
      + "own owns owned belong belongs belonging creator create created creating private public "
      + "permanent persistent persistence active inactive full empty blocked block blocking security firewall policy region "
      + "group-instance-manage group_instance_manage").split(" ")));
    // A value may itself consist of innocent dictionary words. Once a credential
    // label is encountered, omit its complete remaining span before word filtering.
    private static final Pattern SECRET = Pattern.compile(
        "(?i)\\b(?:authorization|bearer|basic|cookies?|set-cookie|password|passwd|pwd|tokens?|"
      + "access[_-]?token|refresh[_-]?token|secrets?|api[_-]?key|client[_-]?secret|sessions?|"
      + "nonce|authcookie|twofactorauth|credentials?|keys?|user[_ -]?(?:name|id)|display[_ -]?name|"
      + "group[_ -]?id|email|private[_ -]?key|access[_ -]?key|secret[_ -]?key|auth[_ -]?(?:token|key)|"
      + "auth(?=[\"'`]?\\s*[:=]))\\b[\"'`]?\\s*(?=[:=]|\\s|$)");
    private static final Pattern QUOTED = Pattern.compile(
        "\"[^\"]*(?:\"|$)|(?<![A-Za-z])'[^']*(?:'|$)|`[^`]*(?:`|$)|"
      + "\u201c[^\u201d]*(?:\u201d|$)|\u2018[^\u2019]*(?:\u2019|$)");

    private ScarletCloseDiagnostics() { }

    static String describe(ApiException exception)
    {
        if (exception == null) return "error response absent";
        String body = exception.getResponseBody();
        if (body == null || body.isEmpty()) return "structured error body absent";
        if (body.length() > BODY_LIMIT || body.getBytes(StandardCharsets.UTF_8).length > BODY_LIMIT)
            return "error body omitted: size limit exceeded";
        if (body.trim().isEmpty()) return "structured error body absent";
        try
        {
            Details root = readResponse(body);
            Details error = root.error;
            Details message = error != null && error.message != null ? error : root;
            StringBuilder safe = new StringBuilder();
            if (message.message != null)
                safe.append(message == error ? "error.message=" : "top-level message (fallback)=")
                    .append(filterMessage(message.message));
            else
                safe.append("structured error message absent or unsupported");
            Details codes = error != null ? error : root;
            if (codes.status != null) safe.append("; status_code=").append(codes.status);
            if (codes.waf != null) safe.append("; waf_code=").append(codes.waf);
            return safe.toString();
        }
        catch (LimitException limit)
        {
            return "error body omitted: structure limit exceeded";
        }
        catch (IOException | RuntimeException invalid)
        {
            return "error body omitted: malformed or unsupported JSON";
        }
    }

    /** Preserve the exact already-closed exception without parsing an unbounded JSON tree. */
    static boolean isAlreadyClosed(ApiException exception)
    {
        if (exception == null || exception.getCode() != 403) return false;
        try
        {
            Details error = readResponse(exception.getResponseBody()).error;
            return error != null && error.message != null && ALREADY_CLOSED.equals(error.message.trim())
                && (!error.statusPresent || "403".equals(error.status));
        }
        catch (IOException | RuntimeException invalid)
        {
            return false;
        }
    }

    private static Details readResponse(String body) throws IOException
    {
        if (body == null || body.length() > BODY_LIMIT || body.getBytes(StandardCharsets.UTF_8).length > BODY_LIMIT)
            throw new IOException("Unsupported error body size");
        try (JsonReader reader = new JsonReader(new StringReader(body)))
        {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Expected error object");
            Details root = readObject(reader, new Budget(), 0, true);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing error content");
            return root;
        }
    }

    private static Details readObject(JsonReader reader, Budget budget, int depth, boolean root) throws IOException
    {
        budget.take(depth);
        Details details = new Details();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext())
        {
            budget.take(depth);
            String name = reader.nextName();
            if (!names.add(name)) throw new IOException("Duplicate JSON field");
            if ("status_code".equals(name)) details.statusPresent = true;
            if (root && "error".equals(name) && reader.peek() == JsonToken.BEGIN_OBJECT)
                details.error = readObject(reader, budget, depth + 1, false);
            else if ("message".equals(name) && reader.peek() == JsonToken.STRING)
            {
                budget.take(depth + 1);
                details.message = reader.nextString();
            }
            else if (("status_code".equals(name) || "waf_code".equals(name)) && reader.peek() == JsonToken.NUMBER)
            {
                budget.take(depth + 1);
                String number = reader.nextString();
                if ("status_code".equals(name) && number.matches("[1-5][0-9]{2}")) details.status = number;
                if ("waf_code".equals(name) && number.matches("[0-9]{1,6}")) details.waf = number;
            }
            else
                skip(reader, budget, depth + 1);
        }
        reader.endObject();
        return details;
    }

    /** Do not use an unbounded recursive tree parser or skipValue on unknown content. */
    private static void skip(JsonReader reader, Budget budget, int depth) throws IOException
    {
        budget.take(depth);
        switch (reader.peek())
        {
        case BEGIN_OBJECT:
            reader.beginObject();
            while (reader.hasNext())
            {
                budget.take(depth);
                reader.nextName();
                skip(reader, budget, depth + 1);
            }
            reader.endObject();
            break;
        case BEGIN_ARRAY:
            reader.beginArray();
            while (reader.hasNext()) skip(reader, budget, depth + 1);
            reader.endArray();
            break;
        case STRING: case NUMBER: reader.nextString(); break;
        case BOOLEAN: reader.nextBoolean(); break;
        case NULL: reader.nextNull(); break;
        default: throw new IOException("Unexpected JSON token");
        }
    }

    private static String filterMessage(String message)
    {
        if (message.length() > MESSAGE_LIMIT) return "[message omitted: size limit exceeded]";
        if (message.trim().isEmpty()) return "[message empty]";
        Matcher secret = SECRET.matcher(message);
        if (secret.find()) message = message.substring(0, secret.start()) + " " + REDACTED;
        message = QUOTED.matcher(message).replaceAll(" " + REDACTED + " ");
        // No non-ASCII glyphs, controls, newlines, terminal escapes, or bidi markers
        // survive. Unknown whole tokens (including IDs/URLs/IPs/emails) are masked.
        message = message.replaceAll("[\\x00-\\x20\\x7F]+", " ")
            .replaceAll("[^\\x20-\\x7E]", " " + REDACTED + " ");
        StringBuilder output = new StringBuilder();
        String last = null;
        for (String raw : message.trim().split(" +"))
        {
            String word = raw.toLowerCase(Locale.ROOT).replaceAll("^[.,!?;:]+|[.,!?;:]+$", "");
            String next = WORDS.contains(word) ? word : REDACTED;
            if (REDACTED.equals(next) && next.equals(last)) continue;
            if (output.length() + next.length() + 1 > 340)
            {
                output.append(" [truncated]");
                break;
            }
            if (output.length() > 0) output.append(' ');
            output.append(next);
            last = next;
        }
        return output.length() == 0 ? "[message empty]" : output.toString();
    }

    private static final class Details
    {
        String message, status, waf;
        boolean statusPresent;
        Details error;
    }

    private static final class Budget
    {
        int tokens;
        void take(int depth) throws LimitException
        {
            if (depth > DEPTH_LIMIT || ++this.tokens > TOKEN_LIMIT) throw new LimitException();
        }
    }

    private static final class LimitException extends IOException
    {
        private static final long serialVersionUID = 1L;
    }
}
