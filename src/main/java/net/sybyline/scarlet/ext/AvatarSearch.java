package net.sybyline.scarlet.ext;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
import net.sybyline.scarlet.util.LRUMap;
import net.sybyline.scarlet.util.URLs;

public interface AvatarSearch
{

    int SEARCH_N = 5000;
    int HYDRATION_SEARCH_N = 50;
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
        URL_ROOT_NEKOSUNEVR = AvatarSearch_VRCDS.SEARCH_ROOT+"/vrcx_search",
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
        // (DNS, HTTP 4xx/5xx, timeouts) never reach WARN or DEBUG -- they say
        // nothing the message doesn't -- and only go to TRACE for deep debugging.
        if (streak <= 1)
            LOG.warn("Avatar search provider unavailable: {} ({})", urlRoot, message);
        else if (streak == QUIET_AFTER_STREAK)
            LOG.warn("Avatar search provider still unavailable after {} tries; backing off and suppressing further warnings: {} ({})", Integer.valueOf(streak), urlRoot, message);
        else if (throttleElapsed)
            LOG.debug("Avatar search provider still unavailable: {} ({})", urlRoot, message);
        LOG.trace("Avatar search provider failure detail: {}", urlRoot, throwable);
    }

    /** Clears a provider's failure state after a successful query, noting recovery if it had been given up on. */
    static void providerSucceeded(String urlRoot)
    {
        Integer streak = providerFailStreak.remove(urlRoot);
        providerBlockedUntil.remove(urlRoot);
        providerLastLog.remove(urlRoot);
        if (streak != null && streak.intValue() >= QUIET_AFTER_STREAK)
            LOG.info("Avatar search provider recovered: {}", urlRoot);
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
            blockProvider(urlRoot, RATE_LIMIT_BACKOFF_MILLIS, "rate limited while searching `"+search+"`", ex);
        else if (ex instanceof SocketTimeoutException || lower.contains("timed out"))
            blockProvider(urlRoot, TIMEOUT_BACKOFF_MILLIS, "timed out while searching `"+search+"`", ex);
        else if (ex instanceof com.google.gson.JsonSyntaxException || message.contains("Expected BEGIN"))
            // The provider is up but not speaking the expected format (endpoint moved,
            // maintenance page, HTML error, ...). Retrying immediately can't succeed and
            // just spams the log with parse stack traces, so back off like a rate limit.
            blockProvider(urlRoot, RATE_LIMIT_BACKOFF_MILLIS, "returned a malformed response while searching `"+search+"`", ex);
        else if (httpCode >= 400 && httpCode < 500)
            // Client error: 403 gated (needs a key), 404/410 gone, 401 unauthorized.
            // None of these fix themselves on retry, so back off hard.
            blockProvider(urlRoot, GONE_BACKOFF_MILLIS, "returned HTTP "+httpCode+" while searching `"+search+"`", ex);
        else if (httpCode >= 500)
            blockProvider(urlRoot, SERVER_ERROR_BACKOFF_MILLIS, "returned HTTP "+httpCode+" while searching `"+search+"`", ex);
        else if (ex instanceof java.net.UnknownHostException
              || lower.contains("no route")
              || lower.contains("refused")
              || lower.contains("name or service not known")
              || lower.contains("connection reset"))
            // DNS or connection failure: the host is down or gone. Back off long.
            blockProvider(urlRoot, GONE_BACKOFF_MILLIS, "unreachable while searching `"+search+"` ("+message+")", ex);
        else
            // Anything else unrecognised: still back off briefly so it can't spin.
            blockProvider(urlRoot, TIMEOUT_BACKOFF_MILLIS, "search failed for `"+search+"`: "+message, ex);
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
        static final Map<String, Map<String, VrcxAvatar[]>> searchCacheByUrlRoot = new ConcurrentHashMap<>(),
                                                            searchCacheByUrlRootByImage = new ConcurrentHashMap<>();
        
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
        if (!urlRoot.startsWith("http://") && !urlRoot.startsWith("https://"))
            return VrcxAvatar.NONE;
        if (!providerAvailable(urlRoot))
            return null;
        try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + Integer.toUnsignedString(n) + "&search=" + URLs.encode(search)))
        {
            VrcxAvatar[] results = parseVrcxResults(in.readAsJson(null, null, JsonElement.class));
            providerSucceeded(urlRoot);
            LOG.debug("{} returned {} result(s) for `{}`", urlRoot, results.length, search);
            return results;
        }
        catch (Exception ex)
        {
            handleSearchFailure(urlRoot, search, ex);
            return null;
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
        java.util.List<VrcxAvatar> out = new java.util.ArrayList<>(array.size());
        for (JsonElement element : array)
        {
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
                if (avatar != null && avatar.id != null)
                    out.add(avatar);
            }
            catch (RuntimeException ignored)
            {
            }
        }
        return out.toArray(new VrcxAvatar[0]);
    }
    static VrcxAvatar[] vrcxFindInCache(String urlRoot, String search)
    {
        return VrcxAvatar.searchCacheByUrlRoot.getOrDefault(urlRoot, Collections.emptyMap()).get(search);
    }
    static VrcxAvatar[] vrcxPutInCache(String urlRoot, String search, VrcxAvatar[] results)
    {
        if (results == null)
            return null;
        VrcxAvatar.searchCacheByUrlRoot.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized()).put(search, results);
        return results;
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, String search)
    {
        return vrcxSearchCached(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, int n, String search)
    {
        Map<String, VrcxAvatar[]> urlCache;
        synchronized (VrcxAvatar.searchCacheByUrlRoot) {
            urlCache = VrcxAvatar.searchCacheByUrlRoot.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized());
        }
        
        synchronized (urlCache) {
            String key = cacheKey(n, search);
            VrcxAvatar[] cached = urlCache.get(key);
            if (cached == null) {
                cached = AvatarSearch.vrcxSearch(urlRoot, n, search);
                if (cached != null) {
                    urlCache.put(key, cached);
                }
            }
            return cached;
        }
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, String search)
    {
        return vrcxSearch(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, int n, String search)
    {
        VrcxAvatar[] results = vrcxSearch0(urlRoot, n, search);
        if (n == SEARCH_N && results != null)
            vrcxPutInCache(urlRoot, cacheKey(n, search), results);
        return results;
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
        return Arrays.stream(urlRoots)
            .filter(Objects::nonNull)
            .map($ -> vrcxSearchCached($, n, search))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .filter(Objects::nonNull)
        ;
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String search)
    {
        return vrcxSearchAll(URL_ROOTS, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String[] urlRoots, String search)
    {
        return Arrays.stream(urlRoots)
            .filter(Objects::nonNull)
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
            if (!urlRoot.startsWith("http://") && !urlRoot.startsWith("https://"))
                return VrcxAvatar.NONE;
            if (!providerAvailable(urlRoot))
                return null;
            try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + Integer.toUnsignedString(n) + "&fileId=" + URLs.encode(imageFileId)))
            {
                VrcxAvatar[] results = parseVrcxResults(in.readAsJson(null, null, JsonElement.class));
                providerSucceeded(urlRoot);
                return results;
            }
            catch (Exception ex)
            {
                handleSearchFailure(urlRoot, imageFileId, ex);
                return null;
            }
        }
        static VrcxAvatar[] vrcxFindInCacheByImage(String urlRoot, String imageFileId)
        {
            return VrcxAvatar.searchCacheByUrlRootByImage.getOrDefault(urlRoot, Collections.emptyMap()).get(imageFileId);
        }
        static VrcxAvatar[] vrcxPutInCacheByImage(String urlRoot, String imageFileId, VrcxAvatar[] results)
        {
            if (results == null)
                return null;
            VrcxAvatar.searchCacheByUrlRootByImage.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized()).put(imageFileId, results);
            return results;
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchCachedByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, int n, String imageFileId)
        {
            Map<String, VrcxAvatar[]> urlCache;
            synchronized (VrcxAvatar.searchCacheByUrlRootByImage) {
                urlCache = VrcxAvatar.searchCacheByUrlRootByImage.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized());
            }
            
            synchronized (urlCache) {
                String key = cacheKey(n, imageFileId);
                VrcxAvatar[] cached = urlCache.get(key);
                if (cached == null) {
                    cached = AvatarSearch.ByImage.vrcxSearchByImage(urlRoot, n, imageFileId);
                    if (cached != null) {
                        urlCache.put(key, cached);
                    }
                }
                return cached;
            }
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, int n, String imageFileId)
        {
            VrcxAvatar[] results = vrcxSearch0ByImage(urlRoot, n, imageFileId);
            if (n == SEARCH_N && results != null)
                vrcxPutInCacheByImage(urlRoot, cacheKey(n, imageFileId), results);
            return results;
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String imageFileId)
        {
            return vrcxSearchAllCachedByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String[] urlRoots, String imageFileId)
        {
            return Arrays.stream(urlRoots)
                .filter(Objects::nonNull)
                .map($ -> vrcxSearchCachedByImage($, SEARCH_N, imageFileId))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
            ;
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String imageFileId)
        {
            return vrcxSearchAllByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String[] urlRoots, String imageFileId)
        {
            return Arrays.stream(urlRoots)
                .filter(Objects::nonNull)
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
            if (!urlRoot.startsWith("http://") && !urlRoot.startsWith("https://"))
                return VrcxAvatar.NONE;
            String stateKey = authorStateKey(urlRoot);
            if (!providerAvailable(stateKey))
                return null;
            try (HttpURLInputStream in = openProvider(urlRoot + "?n=" + Integer.toUnsignedString(n) + "&authorId=" + URLs.encode(authorId)))
            {
                VrcxAvatar[] results = parseVrcxResults(in.readAsJson(null, null, JsonElement.class));
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
            String cacheRoot = authorStateKey(urlRoot);
            Map<String, VrcxAvatar[]> urlCache;
            synchronized (VrcxAvatar.searchCacheByUrlRootByImage) {
                urlCache = VrcxAvatar.searchCacheByUrlRootByImage.computeIfAbsent(cacheRoot, r -> LRUMap.ofSynchronized());
            }
            synchronized (urlCache) {
                String key = cacheKey(n, authorId);
                VrcxAvatar[] cached = urlCache.get(key);
                if (cached == null) {
                    cached = vrcxSearch0ByAuthor(urlRoot, n, authorId);
                    if (cached != null) {
                        urlCache.put(key, cached);
                    }
                }
                return cached;
            }
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
            if (imageFileId == null || authorId == null || authorId.isEmpty())
                return Stream.empty();
            return Arrays.stream(selectedProviders == null ? new String[0] : selectedProviders)
                .filter(Objects::nonNull)
                .filter(url -> !URL_ROOT_AVTRDB.equals(url))
                .distinct()
                .map($ -> vrcxSearchCachedByAuthor($, SEARCH_N, authorId))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
                .filter($ -> imageMatchesFileId($, imageFileId))
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
            String[] direct = selectedImageProviders(selectedProviders);
            return Stream.concat(vrcxSearchAllCachedByImage(direct, imageFileId), vrcxSearchAllByImageViaAuthor(selectedProviders, imageFileId, authorId));
        }

        static String[] selectedImageProviders(String[] selectedProviders)
        {
            if (selectedProviders == null)
                return new String[0];
            java.util.Set<String> supported = new java.util.HashSet<>(Arrays.asList(BY_IMAGE_URL_ROOTS));
            return Arrays.stream(selectedProviders).filter(Objects::nonNull).filter(supported::contains).distinct().toArray(String[]::new);
        }
    }

}
