package net.sybyline.scarlet.ext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.JsonAdapters;
import net.sybyline.scarlet.ScarletAvatarProviderCache;
import net.sybyline.scarlet.util.URLs;

public interface AvatarSearch
{

    int SEARCH_N = 5000;
    int HYDRATION_SEARCH_N = 50;
    int MAX_PROVIDER_RESPONSE_BYTES = 8 * 1024 * 1024;
    // A new key requires explicit confirmation instead of trusting the former plain checkbox.
    String IDENTIFIED_LOOKUP_CONSENT_KEY = "avatar_search_identified_lookups_consent_v2";
    Logger LOG = LoggerFactory.getLogger("Scarlet/AvatarSearch");
    long RATE_LIMIT_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5L),
         TIMEOUT_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(45L),
         SERVER_ERROR_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5L),
         GONE_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(30L),
         MAX_BACKOFF_MILLIS = TimeUnit.HOURS.toMillis(2L),
         ERROR_LOG_THROTTLE_MILLIS = TimeUnit.MINUTES.toMillis(1L);
    // After this many consecutive failures a provider is treated as long-term
    // down: we stop warning and only whisper at TRACE, so a dead or gated host
        // can't flood the console for the rest of the session.
    int QUIET_AFTER_STREAK = 3;
    Map<String, Long> providerBlockedUntil = new ConcurrentHashMap<>(),
                      providerLastLog = new ConcurrentHashMap<>();
    Map<String, Integer> providerFailStreak = new ConcurrentHashMap<>();
    // Canonical VRCX-format search endpoints as published by the providers
    // themselves (cross-checked against ShayBox/VRC-LOG's supported-provider
    // list). Inclusion bar: the provider must honor avatar removal/blacklist
    // requests from creators. All of these are queried politely: cached per
    // search, identified by User-Agent, and backed off on 429/timeout/garbage.
    String
        // avtrDB (restored in 0.4.20): it had been rejecting datacenter/VPN IPs; its maintainer now
        // lets requests identified as Scarlet (our User-Agent) through, so it's a text-search
        // provider again. Deliberately NOT used for author lookup / "search by picture", which were
        // rebuilt without it (see AUTHOR_LOOKUP_URL_ROOTS and BY_IMAGE_URL_ROOTS).
        URL_ROOT_AVTRDB = "https://api.avtrdb.com/v2/avatar/search/vrcx",
        URL_ROOT_NEKOSUNEVR = AvatarSearch_NSVR.API_ROOT_SEARCH+"/vrcx_search",
        URL_ROOT_VRCDB = "https://vrcx.vrcdb.com/avatars/Avatar/VRCX",
        URL_ROOT_WORLDBALANCER = "https://avatarwbvrcxsearch.worldbalancer.com/vrcx_search",
        URL_ROOT_PAW = "https://paw-api.amelia.fun/vrcx_search",
        URL_ROOT_KITSUNEDB = "https://avtr.fumikoecho.net/api/integrations/avatars/vrcx",
        URL_ROOTS[] =
        {
            URL_ROOT_AVTRDB,
            URL_ROOT_NEKOSUNEVR,
            URL_ROOT_VRCDB,
            URL_ROOT_WORLDBALANCER,
            URL_ROOT_PAW,
            URL_ROOT_KITSUNEDB,
        };

    static String cacheKey(int n, String search)
    {
        return n == SEARCH_N ? search : Integer.toUnsignedString(n)+"\u0000"+search;
    }

    /** The same explicit runtime permission applies to every provider lookup mode. */
    static String[] effectiveProviders(boolean featureEnabled, Boolean consent, Boolean customEnabled,
            String[] configured, String blockedHost)
    {
        if (!featureEnabled || !Boolean.TRUE.equals(consent))
            return new String[0];
        return selectedProviders(customEnabled, configured, blockedHost);
    }

    /** Settings selection is independent of consent; this does not authorize any request. */
    static String[] selectedProviders(Boolean customEnabled, String[] configured, String blockedHost)
    {
        String[] selected = Boolean.TRUE.equals(customEnabled) ? configured : URL_ROOTS;
        if (selected == null)
            return new String[0];
        return Arrays.stream(selected).filter(Objects::nonNull).map(String::trim)
            .filter(url -> !url.isEmpty())
            .filter(url -> blockedHost == null || blockedHost.isEmpty()
                || !url.toLowerCase(java.util.Locale.ROOT).contains(blockedHost.toLowerCase(java.util.Locale.ROOT)))
            .distinct().toArray(String[]::new);
    }

    /** All active provider GETs share DNS-pinned public-address and redirect checks. */
    static HttpURLInputStream openProvider(String url) throws IOException
    {
        return HttpURLInputStream.get(url, ExtendedUserAgent.init_avatar_provider_conn, HttpURLInputStream.PUBLIC_ONLY);
    }

    static boolean providerAvailable(String urlRoot)
    {
        Long until = providerBlockedUntil.get(urlRoot);
        if (until == null)
            return true;
        long now = System.currentTimeMillis();
        if (until.longValue() > now)
            return false;
        providerBlockedUntil.remove(urlRoot, until);
        return true;
    }

    static void logProviderFailure(String urlRoot, String message, Throwable throwable)
    {
        int streak = providerFailStreak.getOrDefault(urlRoot, Integer.valueOf(0)).intValue();
        long now = System.currentTimeMillis();
        Long last = providerLastLog.put(urlRoot, Long.valueOf(now));
        boolean throttleElapsed = last == null || now - last.longValue() >= ERROR_LOG_THROTTLE_MILLIS;
        // One warning when a provider first goes down, one more when we give up
        // on it, then silence. Stack traces for these expected network failures
        // (DNS, HTTP 4xx/5xx, timeouts) never include query text, configured URLs,
        // response bodies or exception details in the log.
        if (streak <= 1)
            LOG.warn("Avatar search provider unavailable ({})", message);
        else if (streak == QUIET_AFTER_STREAK)
            LOG.warn("Avatar search provider still unavailable after {} tries; backing off and suppressing further warnings ({})", Integer.valueOf(streak), message);
        else if (throttleElapsed)
            LOG.debug("Avatar search provider still unavailable ({})", message);
    }

    /** Clears a provider's failure state after a successful query, noting recovery if it had been given up on. */
    static void providerSucceeded(String urlRoot)
    {
        Integer streak = providerFailStreak.remove(urlRoot);
        providerBlockedUntil.remove(urlRoot);
        providerLastLog.remove(urlRoot);
        if (streak != null && streak.intValue() >= QUIET_AFTER_STREAK)
            LOG.info("An avatar search provider recovered");
    }

    /** Forgets all provider backoffs, e.g. after the user deliberately changes the provider list. */
    static void clearProviderBackoffs()
    {
        providerBlockedUntil.clear();
        providerLastLog.clear();
        providerFailStreak.clear();
    }

    /**
     * Provider URL to remaining backoff in milliseconds (0 == available), for the
     * diagnostics view. Includes every built-in provider plus any currently-blocked
     * custom one.
     */
    static java.util.Map<String, Long> providerBackoffRemainingMs()
    {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String url : URL_ROOTS)
            out.put(url, 0L);
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Long> entry : providerBlockedUntil.entrySet())
            out.put(entry.getKey(), Math.max(0L, entry.getValue().longValue() - now));
        return out;
    }

    static void blockProvider(String urlRoot, long millis, String message, Throwable throwable)
    {
        int streak = providerFailStreak.getOrDefault(urlRoot, Integer.valueOf(1)).intValue();
        // Escalate: each repeat doubles the wait (capped), so a persistently dead
        // or gated endpoint is retried every couple of hours, not every search.
        int shift = Math.min(streak - 1, 6);
        long backoff = Math.min(MAX_BACKOFF_MILLIS, millis << shift);
        providerBlockedUntil.put(urlRoot, Long.valueOf(System.currentTimeMillis() + backoff));
        logProviderFailure(urlRoot, message+"; backing off for "+TimeUnit.MILLISECONDS.toSeconds(backoff)+"s", throwable);
    }

    static void handleSearchFailure(String urlRoot, String search, Exception ex)
    {
        providerFailStreak.merge(urlRoot, Integer.valueOf(1), Integer::sum);
        String message = ex.getMessage();
        if (message == null)
            message = ex.getClass().getSimpleName();
        String lower = message.toLowerCase();
        int httpCode = httpResponseCode(message);
        if (message.contains("429") || httpCode == 429)
            blockProvider(urlRoot, RATE_LIMIT_BACKOFF_MILLIS, "rate limited", ex);
        else if (ex instanceof SocketTimeoutException || lower.contains("timed out"))
            blockProvider(urlRoot, TIMEOUT_BACKOFF_MILLIS, "request timed out", ex);
        else if (ex instanceof com.google.gson.JsonSyntaxException || message.contains("Expected BEGIN"))
            // The provider is up but not speaking the expected format (endpoint moved,
            // maintenance page, HTML error, ...). Retrying immediately can't succeed and
            // just spams the log with parse stack traces, so back off like a rate limit.
            blockProvider(urlRoot, RATE_LIMIT_BACKOFF_MILLIS, "returned a malformed or oversized response", ex);
        else if (httpCode >= 400 && httpCode < 500)
            // Client error: 403 gated (needs a key), 404/410 gone, 401 unauthorized.
            // None of these fix themselves on retry, so back off hard.
            blockProvider(urlRoot, GONE_BACKOFF_MILLIS, "returned HTTP "+httpCode, ex);
        else if (httpCode >= 500)
            blockProvider(urlRoot, SERVER_ERROR_BACKOFF_MILLIS, "returned HTTP "+httpCode, ex);
        else if (ex instanceof java.net.UnknownHostException
              || lower.contains("no route")
              || lower.contains("refused")
              || lower.contains("name or service not known")
              || lower.contains("connection reset"))
            // DNS or connection failure: the host is down or gone. Back off long.
            blockProvider(urlRoot, GONE_BACKOFF_MILLIS, "provider unreachable", ex);
        else
            // Anything else unrecognised: still back off briefly so it can't spin.
            blockProvider(urlRoot, TIMEOUT_BACKOFF_MILLIS, "request failed", ex);
    }

    /** Parses the numeric code out of "Server returned HTTP response code: NNN for URL: ..." (0 if absent). */
    static int httpResponseCode(String message)
    {
        if (message == null)
            return 0;
        int i = message.indexOf("response code: ");
        if (i < 0)
            return 0;
        i += "response code: ".length();
        int code = 0, n = message.length();
        while (i < n && message.charAt(i) >= '0' && message.charAt(i) <= '9')
        {
            code = code * 10 + (message.charAt(i) - '0');
            i++;
        }
        return code;
    }

    class VrcxAvatar
    {
        static final VrcxAvatar[] NONE = new VrcxAvatar[0];
        
        @SerializedName(value = "id", alternate = { "avatarId", "avatar_id", "vrcId", "vrc_id" })
        public String id;
        public String id() { return this.id; }
        
        @SerializedName(value = "name", alternate = { "display", "displayName", "display_name", "avatarName", "avatar_name", "avatarDisplay", "avatar_display", "avatarDisplayName", "avatar_display_name" })
        public String name;
        public String name() { return this.name; }
        
        @SerializedName(value = "authorId", alternate = { "author_id" })
        public String authorId;
        public String authorId() { return this.authorId; }
        
        @SerializedName(value = "authorName", alternate = { "author_name", "authorDisplay", "author_display", "authorDisplayName", "author_display_name" })
        public String authorName;
        public String authorName() { return this.authorName; }
        
        @SerializedName(value = "description", alternate = { "desc", "avatarDesc", "avatar_desc", "avatarDescription", "avatar_description" })
        public String description;
        public String description() { return this.description; }
        
        @SerializedName(value = "imageUrl", alternate = { "image_url", "image", "avatarImage", "avatar_image", "avatarImageUrl", "avatar_image_url" })
        public String imageUrl;
        public String imageUrl() { return this.imageUrl; }
        
        @SerializedName(value = "thumbnailImageUrl", alternate = { "thumbnail_image_url", "thumbnailImage", "thumbnail_image", "avatarThumbnail", "avatar_thumbnail", "avatarThumbnailImage", "avatar_thumbnail_image", "avatarThumbnailUrl", "avatar_thumbnail_url", "avatarThumbnailImageUrl", "avatar_thumbnail_image_url" }) 
        public String thumbnailImageUrl;
        public String thumbnailImageUrl() { return this.thumbnailImageUrl; }
        
        @SerializedName(value = "releaseStatus", alternate = { "release_status", "release", "status", "avatarRelease", "avatar_release", "avatarStatus", "avatar_status", "avatarReleaseStatus", "avatar_release_status" })
        public String releaseStatus;
        public String releaseStatus() { return this.releaseStatus; }
        
        @SerializedName(value = "created_at", alternate = { "createdAt", "created", "avatarCreated", "avatar_created", "avatarCreatedAt", "avatar_created_at" })
        public OffsetDateTime createdAt;
        public OffsetDateTime createdAt() { return this.createdAt; }
        
        @SerializedName(value = "updated_at", alternate = { "updatedAt", "updated", "avatarUpdated", "avatar_updated", "avatarUpdatedAt", "avatar_updated_at" })
        public OffsetDateTime updatedAt;
        public OffsetDateTime updatedAt() { return this.updatedAt; }
        
        @SerializedName(value = "performance", alternate = { "perf", "avatarPerf", "avatar_perf", "avatarPerformance", "avatar_performance" })
        public Performance performance;
        public Performance performance() { return this.performance; }
        public static class Performance
        {
            @SerializedName(value = "pc_rating", alternate = { "pcRating", "pc" })
            public String pcRating;
            public String pcRating() { return this.pcRating; }
            
            @SerializedName(value = "android_rating", alternate = { "quest_rating", "androidRating", "questRating", "android", "quest" })
            public String androidRating;
            public String androidRating() { return this.androidRating; }
            
            @SerializedName(value = "ios_rating", alternate = { "iosRating", "ios" })
            public String iosRating;
            public String iosRating() { return this.iosRating; }
            
            @SerializedName(value = "has_impostor", alternate = { "hasImpostor", "impostor" })
            public boolean hasImpostor;
            public boolean hasImpostor() { return this.hasImpostor; }
            
            @SerializedName(value = "has_security_variant", alternate = { "hasSecurityVariant", "securityVariant", "security_variant", "security", "hasSecurity", "has_security" })
            public Boolean hasSecurityVariant;
            public Boolean hasSecurityVariant() { return this.hasSecurityVariant; }
        }
        
        public VrcxAvatar merge(VrcxAvatar found)
        {
            if (found == null)
                return this;
            if (!Objects.equals(this.id, found.id))
                return this;
            
            if (this.authorId == null)
                this.authorId = found.authorId;
            
            if (this.authorName == null)
                this.authorName = found.authorName;
            
            if (this.description == null)
                this.description = found.description;
            
            if (this.imageUrl == null)
                this.imageUrl = found.imageUrl;
            
            if (this.thumbnailImageUrl == null)
                this.thumbnailImageUrl = found.thumbnailImageUrl;
            
            if (this.createdAt == null)
                this.createdAt = found.createdAt;
            
            if (this.updatedAt == null)
                this.updatedAt = found.updatedAt;
            
            if (this.releaseStatus == null)
                this.releaseStatus = found.releaseStatus;
            
            if (this.performance == null)
                this.performance = found.performance;
            else if (found.performance != null)
            {
                if (this.performance.pcRating == null)
                    this.performance.pcRating = found.performance.pcRating;
                
                if (this.performance.androidRating == null)
                    this.performance.androidRating = found.performance.androidRating;
                
                if (this.performance.iosRating == null)
                    this.performance.iosRating = found.performance.iosRating;
                
                this.performance.hasImpostor |= found.performance.hasImpostor;
                
                if (this.performance.hasSecurityVariant == null)
                    this.performance.hasSecurityVariant = found.performance.hasSecurityVariant;
            }
            
            return this;
        }
        @Override
        public int hashCode()
        {
            return Objects.hashCode(this.id);
        }
        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof VrcxAvatar && Objects.equals(this.id, ((VrcxAvatar)obj).id);
        }
        private String toString;
        @Override
        public String toString()
        {
            String toString = this.toString;
            if (toString == null)
            {
                StringBuilder sb = new StringBuilder();
                
                sb.append(this.name).append(" (").append(this.id)
                .append(") by ").append(this.authorName);
                if (this.authorId != null)
                {
                    sb.append(" (").append(this.authorId).append(")");
                }
                
                if (this.description != null && !this.description.trim().isEmpty())
                {
                    sb.append(": ").append(this.description);
                }
                
                this.toString = toString = sb.toString();
            }
            return toString;
        }
    }
    public static class VrcxAvatarTypeAdapter extends TypeAdapter<VrcxAvatar>
    {
        @Override
        public void write(JsonWriter out, VrcxAvatar value) throws IOException
        {
            if (value == null)
            {
                out.nullValue();
                return;
            }
            out.beginObject();
            {
                out.endObject();
            }
        }
        @Override
        public VrcxAvatar read(JsonReader in) throws IOException
        {
            // handles nulls implicitly
            if (in.peek() != JsonToken.BEGIN_OBJECT)
            {
                in.skipValue();
                return null;
            }
            in.beginObject();
            VrcxAvatar ret = new VrcxAvatar();
            while (in.peek() != JsonToken.END_OBJECT)
            {
                String prop = in.nextName();
                switch (prop.toLowerCase().replace("_", ""))
                {
                case "id":
                case "avatarid":
                case "vrcid": {
                    ret.id = in.nextString();
                } break;
                case "name":
                case "display":
                case "displayname":
                case "avatarname":
                case "avatardisplay":
                case "avatardisplayname": {
                    ret.name = in.nextString();
                } break;
                case "userid":
                case "authorid":{
                    ret.authorId = in.nextString();
                } break;
                case "username":
                case "userdisplay":
                case "userdisplayname":
                case "authorname":
                case "authordisplay":
                case "authordisplayname": {
                    ret.authorName = in.nextString();
                } break;
                case "user":
                case "author": {
                    if (in.peek() == JsonToken.BEGIN_OBJECT)
                    {
                        in.beginObject();
                        while (in.peek() != JsonToken.END_OBJECT)
                        {
                            String authorProp = in.nextName();
                            switch (authorProp.toLowerCase().replace("_", ""))
                            {
                            case "id":
                            case "vrcid":
                            case "userid":
                            case "authorid": {
                                ret.authorId = in.nextString();
                            } break;
                            case "name":
                            case "display":
                            case "displayname":
                            case "username":
                            case "userdisplay":
                            case "userdisplayname":
                            case "authorname":
                            case "authordisplay":
                            case "authordisplayname": {
                                ret.authorName = in.nextString();
                            } break;
                            default: {
                                in.skipValue();
                            }
                            }
                        }
                        in.endObject();
                    }
                    else if (in.peek() == JsonToken.STRING)
                    {
                        ret.authorName = in.nextString();
                    }
                } break;
                case "desc":
                case "description":
                case "avatardesc":
                case "avatardescription": {
                    ret.description = in.nextString();
                } break;
                case "image":
                case "imageurl":
                case "avatarimage":
                case "avatarimageurl": {
                    ret.imageUrl = in.nextString();
                } break;
                case "thumbnail":
                case "thumbnailimage":
                case "thumbnailurl":
                case "thumbnailimageurl":
                case "avatarthumbnail":
                case "avatarthumbnailimage":
                case "avatarthumbnailurl":
                case "avatarthumbnailimageurl": {
                    ret.imageUrl = in.nextString();
                } break;
                case "created":
                case "createdat":
                case "avatarcreated":
                case "avatarcreatedat": {
                    ret.createdAt = JsonAdapters.json2offsetDateTime(in.nextString());
                } break;
                case "updated":
                case "updatedat":
                case "avatarupdated":
                case "avatarupdatedat": {
                    ret.updatedAt = JsonAdapters.json2offsetDateTime(in.nextString());
                } break;
                case "release":
                case "status":
                case "releasestatus":
                case "avatarrelease":
                case "avatarstatus":
                case "avatarreleasestatus": {
                    ret.releaseStatus = in.nextString();
                } break;
                case "performance":
                case "perf":
                case "avatarperformance":
                case "avatarperf": {
                    if (in.peek() == JsonToken.BEGIN_OBJECT)
                    {
                        in.beginObject();
                        if (ret.performance == null)
                            ret.performance = new VrcxAvatar.Performance();
                        while (in.peek() != JsonToken.END_OBJECT)
                        {
                            String authorProp = in.nextName();
                            switch (authorProp.toLowerCase().replace("_", ""))
                            {
                            case "pc":
                            case "pcrating": {
                                ret.performance.pcRating = in.nextString();
                            } break;
                            case "android":
                            case "androidrating":
                            case "quest":
                            case "questrating": {
                                ret.performance.androidRating = in.nextString();
                            } break;
                            case "ios":
                            case "iosrating": {
                                ret.performance.iosRating = in.nextString();
                            } break;
                            case "hasimpostor":
                            case "impostor": {
                                if (in.peek() == JsonToken.BOOLEAN)
                                {
                                    ret.performance.hasImpostor = in.nextBoolean();
                                }
                                else if (in.peek() == JsonToken.STRING)
                                {
                                    String hasImpostor = in.nextString();
                                    if (!"null".equalsIgnoreCase(hasImpostor) && !"none".equalsIgnoreCase(hasImpostor))
                                    {
                                        ret.performance.hasImpostor = !hasImpostor.isEmpty() && !"false".equalsIgnoreCase(hasImpostor);
                                    }
                                }
                                else
                                {
                                    in.skipValue();
                                }
                            } break;
                            case "hassecurityvariant":
                            case "securityvariant":
                            case "hassecurity":
                            case "security": {
                                if (in.peek() == JsonToken.BOOLEAN)
                                {
                                    ret.performance.hasSecurityVariant = in.nextBoolean();
                                }
                                else if (in.peek() == JsonToken.STRING)
                                {
                                    String hasSecurityVariant = in.nextString();
                                    if (!"null".equalsIgnoreCase(hasSecurityVariant) && !"none".equalsIgnoreCase(hasSecurityVariant))
                                    {
                                        ret.performance.hasSecurityVariant = !hasSecurityVariant.isEmpty() && !"false".equalsIgnoreCase(hasSecurityVariant);
                                    }
                                }
                                else
                                {
                                    in.skipValue();
                                }
                            } break;
                            default: {
                                in.skipValue();
                            }
                            }
                        }
                        in.endObject();
                    }
                    else if (in.peek() == JsonToken.STRING)
                    {
                        ret.authorName = in.nextString();
                    }
                } break;
                default: {
                    in.skipValue();
                }
                }
            }
            in.endObject();
            return ret;
        }
    }

    static VrcxAvatar[] vrcxSearch0(String urlRoot, int n, String search)
    {
        int count = resultLimit(n);
        if (!validProvider(urlRoot) || search == null)
            return null;
        if (!providerAvailable(urlRoot))
            return null;
        try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + count + "&search=" + URLs.encode(search)))
        {
            VrcxAvatar[] results = readVrcxResults(in, in.connection().getContentEncoding(), count);
            providerSucceeded(urlRoot);
            LOG.debug("Avatar search provider returned {} result(s)", results.length);
            return results;
        }
        catch (Exception ex)
        {
            handleSearchFailure(urlRoot, search, ex);
            return null;
        }
    }

    static int resultLimit(int n)
    {
        if (n <= 0) throw new IllegalArgumentException("Avatar search count must be positive");
        return Math.min(n, SEARCH_N);
    }

    static boolean validProvider(String provider)
    {
        return provider != null && (provider.startsWith("http://") || provider.startsWith("https://"));
    }

    static boolean lookupAllowed(BooleanSupplier allowed)
    {
        try { return allowed != null && allowed.getAsBoolean(); }
        catch (RuntimeException ex) { return false; }
    }

    static Stream<String> selectedProviderStream(String[] providers)
    {
        return Arrays.stream(providers == null ? new String[0] : providers.clone())
            .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct();
    }

    /** Reject oversized transfer and decoded bodies before Gson is allowed to allocate a JSON tree. */
    static VrcxAvatar[] readVrcxResults(InputStream response, String contentEncoding, int n) throws IOException
    {
        int limit = resultLimit(n);
        String encoding = contentEncoding == null ? "" : contentEncoding.trim();
        InputStream bounded = new ProviderResponseInput(response);
        InputStream decoded;
        if (encoding.isEmpty() || "identity".equalsIgnoreCase(encoding)) decoded = bounded;
        else if ("gzip".equalsIgnoreCase(encoding)) decoded = new GZIPInputStream(bounded);
        else if ("deflate".equalsIgnoreCase(encoding)) decoded = new InflaterInputStream(bounded);
        else throw new JsonSyntaxException("Unsupported avatar provider content encoding");
        try (InputStream body = decoded; ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192))
        {
            byte[] buffer = new byte[8192];
            int total = 0, read;
            while ((read = body.read(buffer, 0, Math.min(buffer.length, MAX_PROVIDER_RESPONSE_BYTES - total + 1))) != -1)
            {
                total += read;
                if (total > MAX_PROVIDER_RESPONSE_BYTES)
                    throw new JsonSyntaxException("Avatar provider response exceeds the byte limit");
                bytes.write(buffer, 0, read);
            }
            JsonElement json = HttpURLInputStream.readJson(new ByteArrayInputStream(bytes.toByteArray()), null, null, JsonElement.class);
            return parseVrcxResults(json, limit);
        }
    }

    final class ProviderResponseInput extends FilterInputStream
    {
        private int count;
        ProviderResponseInput(InputStream input) { super(Objects.requireNonNull(input, "response")); }
        private void counted(int read)
        {
            if (read > 0 && (this.count += read) > MAX_PROVIDER_RESPONSE_BYTES)
                throw new JsonSyntaxException("Avatar provider response exceeds the byte limit");
        }
        @Override public int read() throws IOException
        {
            int value = this.in.read();
            if (value != -1) this.counted(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException
        {
            if (length == 0) return 0;
            int read = this.in.read(bytes, offset, Math.min(length, MAX_PROVIDER_RESPONSE_BYTES - this.count + 1));
            this.counted(read);
            return read;
        }
    }

    /**
     * Providers have drifted from the original bare-array VRCX response format
     * toward their own search engines' native shapes: wrapper objects around
     * the result array, nested author records, renamed fields. Accept all of
     * them — a bare array, or an object wrapping the array under a common key,
     * with per-entry tolerance so one odd record can't sink the result set.
     * Anything unrecognizable throws, which backs the provider off as malformed.
     */
    static VrcxAvatar[] parseVrcxResults(JsonElement json)
    {
        return parseVrcxResults(json, SEARCH_N);
    }

    static VrcxAvatar[] parseVrcxResults(JsonElement json, int n)
    {
        int limit = resultLimit(n);
        JsonArray array = null;
        if (json != null && json.isJsonArray())
        {
            array = json.getAsJsonArray();
        }
        else if (json != null && json.isJsonObject())
        {
            JsonObject object = json.getAsJsonObject();
            for (String key : new String[] { "avatars", "results", "data", "items", "records" })
            {
                JsonElement inner = object.get(key);
                if (inner != null && inner.isJsonArray())
                {
                    array = inner.getAsJsonArray();
                    break;
                }
            }
        }
        if (array == null)
            throw new JsonSyntaxException("unrecognized avatar search response shape");
        java.util.List<VrcxAvatar> out = new java.util.ArrayList<>(Math.min(array.size(), limit));
        for (JsonElement element : array)
        {
            if (out.size() >= limit) break;
            if (!element.isJsonObject())
                continue;
            JsonObject entry = element.getAsJsonObject();
            // Flatten nested author records (native avtrDB-style) onto the flat
            // fields the VRCX schema uses.
            JsonElement author = entry.get("author");
            if (author != null && author.isJsonObject())
            {
                JsonObject authorObject = author.getAsJsonObject();
                if (!entry.has("authorId") && authorObject.has("vrc_id"))
                    entry.add("authorId", authorObject.get("vrc_id"));
                if (!entry.has("authorName") && authorObject.has("name"))
                    entry.add("authorName", authorObject.get("name"));
                entry.remove("author");
            }
            try
            {
                VrcxAvatar avatar = net.sybyline.scarlet.Scarlet.GSON.fromJson(entry, VrcxAvatar.class);
                if (avatar != null && avatar.id != null && !avatar.id.trim().isEmpty())
                    out.add(avatar);
            }
            catch (RuntimeException ignored)
            {
            }
        }
        if (!array.isEmpty() && out.isEmpty())
            throw new JsonSyntaxException("Avatar provider response contains no usable records");
        return out.toArray(new VrcxAvatar[0]);
    }
    /** Compatibility search wrappers retain only a bounded process-local cache. */
    final class LegacyCacheHolder
    {
        private LegacyCacheHolder() { }
        static final ScarletAvatarProviderCache CACHE = new ScarletAvatarProviderCache(null);
    }

    static VrcxAvatar[] boundedResults(VrcxAvatar[] results, int n)
    {
        int limit = resultLimit(n);
        return results == null ? null : results.length <= limit ? results : Arrays.copyOf(results, limit);
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, String search)
    {
        return vrcxSearchCached(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, int n, String search)
    {
        return vrcxSearchCached(LegacyCacheHolder.CACHE, urlRoot, n, search, () -> true);
    }
    static VrcxAvatar[] vrcxSearchCached(ScarletAvatarProviderCache cache, String urlRoot, int n,
        String search, BooleanSupplier allowed)
    {
        resultLimit(n);
        if (cache == null || !validProvider(urlRoot) || search == null || !lookupAllowed(allowed)) return null;
        VrcxAvatar[] results = cache.get(urlRoot, "text", n, search, allowed,
            () -> lookupAllowed(allowed) ? vrcxSearch0(urlRoot, n, search) : null);
        return lookupAllowed(allowed) ? boundedResults(results, n) : null;
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, String search)
    {
        return vrcxSearch(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, int n, String search)
    {
        return vrcxSearch0(urlRoot, n, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String search)
    {
        return vrcxSearchAllCached(URL_ROOTS, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String[] urlRoots, String search)
    {
        return vrcxSearchAllCached(urlRoots, SEARCH_N, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String[] urlRoots, int n, String search)
    {
        return vrcxSearchAllCached(LegacyCacheHolder.CACHE, urlRoots, n, search, () -> true);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(ScarletAvatarProviderCache cache, String[] urlRoots, int n,
        String search, BooleanSupplier allowed)
    {
        resultLimit(n);
        if (cache == null || search == null || !lookupAllowed(allowed)) return Stream.empty();
        return selectedProviderStream(urlRoots)
            .map($ -> vrcxSearchCached(cache, $, n, search, allowed))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .filter(Objects::nonNull)
            .filter(ignored -> lookupAllowed(allowed))
        ;
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String search)
    {
        return vrcxSearchAll(URL_ROOTS, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String[] urlRoots, String search)
    {
        return selectedProviderStream(urlRoots)
            .map($ -> vrcxSearch($, SEARCH_N, search))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .filter(Objects::nonNull)
        ;
    }

    interface ByImage
    {
        // No provider answers ?fileId= directly any more: "search by picture" resolves the
        // image's owner through VRChat's file API and does an author lookup instead (see
        // AUTHOR_LOOKUP_URL_ROOTS). avtrDB is back for name search only and is intentionally
        // not listed here. Left as an explicit empty list so a by-image provider can be
        // dropped straight in.
        String BY_IMAGE_URL_ROOTS[] =
        {
        };
        static VrcxAvatar[] vrcxSearch0ByImage(String urlRoot, int n, String imageFileId)
        {
            int count = resultLimit(n);
            if (!validProvider(urlRoot) || imageFileId == null)
                return null;
            if (!providerAvailable(urlRoot))
                return null;
            try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + count + "&fileId=" + URLs.encode(imageFileId)))
            {
                VrcxAvatar[] results = readVrcxResults(in, in.connection().getContentEncoding(), count);
                providerSucceeded(urlRoot);
                return results;
            }
            catch (Exception ex)
            {
                handleSearchFailure(urlRoot, imageFileId, ex);
                return null;
            }
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchCachedByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, int n, String imageFileId)
        {
            return vrcxSearchCachedByImage(LegacyCacheHolder.CACHE, urlRoot, n, imageFileId, () -> true);
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(ScarletAvatarProviderCache cache, String urlRoot, int n,
            String imageFileId, BooleanSupplier allowed)
        {
            resultLimit(n);
            if (cache == null || !validProvider(urlRoot) || imageFileId == null || !lookupAllowed(allowed)) return null;
            VrcxAvatar[] results = cache.get(urlRoot, "image", n, imageFileId, allowed,
                () -> lookupAllowed(allowed) ? vrcxSearch0ByImage(urlRoot, n, imageFileId) : null);
            return lookupAllowed(allowed) ? boundedResults(results, n) : null;
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, int n, String imageFileId)
        {
            return vrcxSearch0ByImage(urlRoot, n, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String imageFileId)
        {
            return vrcxSearchAllCachedByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String[] urlRoots, String imageFileId)
        {
            return vrcxSearchAllCachedByImage(LegacyCacheHolder.CACHE, urlRoots, imageFileId, () -> true);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(ScarletAvatarProviderCache cache, String[] urlRoots,
            String imageFileId, BooleanSupplier allowed)
        {
            if (cache == null || imageFileId == null || !lookupAllowed(allowed)) return Stream.empty();
            return selectedProviderStream(urlRoots)
                .map($ -> vrcxSearchCachedByImage(cache, $, SEARCH_N, imageFileId, allowed))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
                .filter(ignored -> lookupAllowed(allowed))
            ;
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String imageFileId)
        {
            return vrcxSearchAllByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String[] urlRoots, String imageFileId)
        {
            return selectedProviderStream(urlRoots)
                .map($ -> vrcxSearchByImage($, SEARCH_N, imageFileId))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
            ;
        }

        // --- Dependency-free reverse-image lookup (no keyed provider needed) ---
        // avtrDB used to answer ?fileId= directly; with it gone, "search by
        // picture" is reconstructed from parts we already trust: the caller
        // resolves the image's owner via VRChat's authenticated file API, we query
        // each provider by author (the standard VRCX ?authorId= mode), and keep
        // only the avatars whose own image references the same file id.

        /** Providers queried for author lookups; same endpoints as text search. */
        // Every text-search provider except avtrDB, which is restored for name search only.
        String[] AUTHOR_LOOKUP_URL_ROOTS = java.util.Arrays.stream(URL_ROOTS)
            .filter(url -> !URL_ROOT_AVTRDB.equals(url))
            .toArray(String[]::new);

        /** Backoff/state key for a provider's author-lookup mode, kept separate from its text-search state so one can't block the other. */
        static String authorStateKey(String urlRoot)
        {
            return urlRoot + "#author";
        }

        static VrcxAvatar[] vrcxSearch0ByAuthor(String urlRoot, int n, String authorId)
        {
            int count = resultLimit(n);
            if (!validProvider(urlRoot) || authorId == null)
                return null;
            String stateKey = authorStateKey(urlRoot);
            if (!providerAvailable(stateKey))
                return null;
            try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + count + "&authorId=" + URLs.encode(authorId)))
            {
                VrcxAvatar[] results = readVrcxResults(in, in.connection().getContentEncoding(), count);
                providerSucceeded(stateKey);
                return results;
            }
            catch (Exception ex)
            {
                handleSearchFailure(stateKey, authorId, ex);
                return null;
            }
        }

        static VrcxAvatar[] vrcxSearchCachedByAuthor(String urlRoot, int n, String authorId)
        {
            return vrcxSearchCachedByAuthor(LegacyCacheHolder.CACHE, urlRoot, n, authorId, () -> true);
        }
        static VrcxAvatar[] vrcxSearchCachedByAuthor(ScarletAvatarProviderCache cache, String urlRoot, int n,
            String authorId, BooleanSupplier allowed)
        {
            resultLimit(n);
            if (cache == null || !validProvider(urlRoot) || authorId == null || !lookupAllowed(allowed)) return null;
            VrcxAvatar[] results = cache.get(urlRoot, "author", n, authorId, allowed,
                () -> lookupAllowed(allowed) ? vrcxSearch0ByAuthor(urlRoot, n, authorId) : null);
            return lookupAllowed(allowed) ? boundedResults(results, n) : null;
        }

        /** Does this avatar's image or thumbnail reference the given VRChat file id? */
        static boolean imageMatchesFileId(VrcxAvatar avatar, String imageFileId)
        {
            if (avatar == null || imageFileId == null)
                return false;
            return (avatar.imageUrl != null && avatar.imageUrl.contains(imageFileId))
                || (avatar.thumbnailImageUrl != null && avatar.thumbnailImageUrl.contains(imageFileId));
        }

        /**
         * The reverse-image fallback: query every provider by {@code authorId} and
         * keep only avatars whose image references {@code imageFileId}. The author
         * is the owner of the image file, resolved by the caller via VRChat's file
         * API. Empty if the author is unknown or not indexed by any provider.
         */
        static Stream<VrcxAvatar> vrcxSearchAllByImageViaAuthor(String[] selectedProviders, String imageFileId, String authorId)
        {
            return vrcxSearchAllByImageViaAuthor(LegacyCacheHolder.CACHE, selectedProviders, imageFileId, authorId, () -> true);
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImageViaAuthor(ScarletAvatarProviderCache cache, String[] selectedProviders,
            String imageFileId, String authorId, BooleanSupplier allowed)
        {
            if (cache == null || imageFileId == null || authorId == null || authorId.isEmpty() || !lookupAllowed(allowed))
                return Stream.empty();
            return selectedProviderStream(selectedProviders)
                .filter(url -> !URL_ROOT_AVTRDB.equals(url))
                .map($ -> vrcxSearchCachedByAuthor(cache, $, SEARCH_N, authorId, allowed))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
                .filter($ -> imageMatchesFileId($, imageFileId))
                .filter(ignored -> lookupAllowed(allowed))
            ;
        }

        /** By-image with author fallback: native ?fileId= providers (if any) plus the author-match path. */
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String[] selectedProviders, String imageFileId, String authorId)
        {
            String[] direct = selectedImageProviders(selectedProviders);
            return Stream.concat(vrcxSearchAllByImage(direct, imageFileId), vrcxSearchAllByImageViaAuthor(selectedProviders, imageFileId, authorId));
        }

        /** Cached by-image with author fallback. */
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String[] selectedProviders, String imageFileId, String authorId)
        {
            return vrcxSearchAllCachedByImage(LegacyCacheHolder.CACHE, selectedProviders, imageFileId, authorId, () -> true);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(ScarletAvatarProviderCache cache, String[] selectedProviders,
            String imageFileId, String authorId, BooleanSupplier allowed)
        {
            if (cache == null || !lookupAllowed(allowed)) return Stream.empty();
            String[] direct = selectedImageProviders(selectedProviders);
            return Stream.concat(vrcxSearchAllCachedByImage(cache, direct, imageFileId, allowed),
                vrcxSearchAllByImageViaAuthor(cache, selectedProviders, imageFileId, authorId, allowed));
        }

        static String[] selectedImageProviders(String[] selectedProviders)
        {
            if (selectedProviders == null)
                return new String[0];
            java.util.Set<String> supported = new java.util.HashSet<>(Arrays.asList(BY_IMAGE_URL_ROOTS));
            return selectedProviderStream(selectedProviders).filter(supported::contains).toArray(String[]::new);
        }
    }

}
