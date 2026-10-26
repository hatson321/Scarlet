package net.sybyline.scarlet;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.j256.twofactorauth.TimeBasedOneTimePasswordUtil;

import io.github.vrchatapi.ApiCallback;
import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.ApiResponse;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.ProgressResponseBody;
import io.github.vrchatapi.api.AuthenticationApi;
import io.github.vrchatapi.api.AvatarsApi;
import io.github.vrchatapi.api.CalendarApi;
import io.github.vrchatapi.api.FilesApi;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.api.InstancesApi;
import io.github.vrchatapi.api.MiscellaneousApi;
import io.github.vrchatapi.api.PrintsApi;
import io.github.vrchatapi.api.PropsApi;
import io.github.vrchatapi.api.UsersApi;
import io.github.vrchatapi.api.WorldsApi;
import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.BanGroupMemberRequest;
import io.github.vrchatapi.model.CalendarEvent;
import io.github.vrchatapi.model.CreateGroupAnnouncementRequest;
import io.github.vrchatapi.model.CreateCalendarEventRequest;
import io.github.vrchatapi.model.CreateGroupPostRequest;
import io.github.vrchatapi.model.CreateGroupInviteRequest;
import io.github.vrchatapi.model.CreateInstanceRequest;
import io.github.vrchatapi.model.CurrentUser;
import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.GroupPostsResponse;
import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupAnnouncement;
import io.github.vrchatapi.model.GroupAuditLogEntryUnknown;
import io.github.vrchatapi.model.GroupGalleryImage;
import io.github.vrchatapi.model.GroupInstance;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupPost;
import io.github.vrchatapi.model.GroupRole;
import io.github.vrchatapi.model.GroupTransferable;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.InventoryItem;
import io.github.vrchatapi.model.LimitedUserGroups;
import io.github.vrchatapi.model.LimitedUserInstance;
import io.github.vrchatapi.model.LimitedUserSearch;
import io.github.vrchatapi.model.LimitedWorld;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.OrderOption;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;
import io.github.vrchatapi.model.Print;
import io.github.vrchatapi.model.Prop;
import io.github.vrchatapi.model.RespondGroupJoinRequest;
import io.github.vrchatapi.model.GroupMemberSearchResponse;
import io.github.vrchatapi.model.SortOption;
import io.github.vrchatapi.model.Success;
import io.github.vrchatapi.model.TransferGroupRequest;
import io.github.vrchatapi.model.TwoFactorAuthCode;
import io.github.vrchatapi.model.TwoFactorEmailCode;
import io.github.vrchatapi.model.UpdateGroupMemberRequest;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.World;

import net.sybyline.scarlet.ext.ExtendedUserAgent;
import net.sybyline.scarlet.util.I18n;
import net.sybyline.scarlet.util.EnumHelper;
import net.sybyline.scarlet.util.Location;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.VersionedFile;
import net.sybyline.scarlet.util.VrcAllGroupPermissions;
import net.sybyline.scarlet.util.VrcWeb;

import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ScarletVRChat implements Closeable
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/VRChat");
    private static final String LEGACY_TLS_COMPAT_SETTING = "vrchat_legacy_tls_compat_enabled";
    private static final String[] LEGACY_TLS_COMPAT_CERT_RESOURCES = {
        "/net/sybyline/scarlet/compat/GoGetSSLECCDVSSLCA2.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationRootE46.pem"
    };

    public static final Comparator<GroupAuditLogEntryUnknown> OLDEST_TO_NEWEST = Comparator.comparing(GroupAuditLogEntryUnknown::getCreatedAt);

    private static Gson configuredJson;

    static synchronized void initJson()
    {
        Gson previous = JSON.getGson();
        if (previous == configuredJson)
            return;
        // The SDK installs its model, date and byte-array adapters after createGson().
        // Preserve those registrations while adding Scarlet's compatibility layer.
        configuredJson = previous.newBuilder().registerTypeAdapterFactory(new TypeAdapterFactory() {
            @Override
            public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                TypeAdapter<T> fbTA = Scarlet.GSON.getAdapter(type);
                // Use this Gson's delegates so nested response objects also use Scarlet's
                // audit-data adapter rather than an earlier Gson's generated model adapters.
                TypeAdapter<T> prevTA = gson.getDelegateAdapter(this, type);
                TypeAdapter<JsonElement> prevJE = gson.getAdapter(JsonElement.class);
                return new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                            return;
                        }
                        JsonElement je;
                        try {
                            je = prevTA.toJsonTree(value);
                        } catch (Exception ex) {
                            je = fbTA.toJsonTree(value);
                        }
                        prevJE.write(out, je);
                    }
                    @Override
                    public T read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        JsonElement je = prevJE.read(in);

                        // Based on Chloethecat's compatibility fix in
                        // https://github.com/Chloethecat/Scarlet (commit 17e6ca4).
                        normalizeCurrentUserPresenceAvatarTags(type, je);
                        normalizeInstanceUserJoinDates(type, je);

                        T value;
                        try {
                            value = prevTA.fromJsonTree(je);
                        } catch (Exception ex) {
                            @SuppressWarnings("rawtypes")
                            Class enumType = type.getRawType();
                            if (enumType.isEnum()) try {
                                value = fbTA.fromJsonTree(je);
                            } catch (Exception ex1) {
                                if (enumType.isEnum() && enumType.getName().replace('/', '.').startsWith("io.github.vrchatapi.model.")) {
                                    String enumValue = je.getAsString();
                                    LOG.warn("Encountering new API enum value for "+enumType.getName()+": `"+enumValue+"`, implicitly instantiating instance...");
                                    @SuppressWarnings("unchecked")
                                    Object newValue = EnumHelper.addJsonStringEnum(enumType, enumValue);
                                    try {
                                        value = fbTA.fromJsonTree(je);
                                        if (newValue != value) {
                                            LOG.error("Mismatch in parsed values: expected `"+newValue+"`, got `"+value+"`");
                                        }
                                    } catch (Exception ex2) {
                                        LOG.error("Failed to instantiate encountered enum, returning null instead");
                                        value = null;
                                    }
                                } else {
                                    LOG.error("Unknown enum value for `"+je.getAsString()+"`, returning null instead");
                                    value = null;
                                }
                            } else {
                                value = fbTA.fromJsonTree(je);
                            }
                        }
                        return value;
                    }
                };
            }
        }).registerTypeAdapterFactory(new ScarletAuditData.Factory(true)).create();
        JSON.setGson(configuredJson);
    }

    public ScarletVRChat(Scarlet scarlet, String domain, File cookieFile)
    {
        scarlet.splash.splashSubtext("Configuring VRChat Api");
        // ensure default ApiClient initialized
        // the ApiClient constructor resets JSON.gson
        Configuration.getDefaultApiClient();
        
        this.scarlet = scarlet;
        this.username = scarlet.settings.new RegistryStringEncrypted(domain+":username", true);
        this.password = scarlet.settings.new RegistryStringEncrypted(domain+":password", true);
        this.totpsecret = scarlet.settings.new RegistryStringEncrypted(domain+":totpsecret", true);
        this.cookies = new ScarletVRChatCookieJar(scarlet, domain, cookieFile);
        this.legacyTrustCompatEnabled = Boolean.TRUE.equals(this.scarlet.settings.getObject(LEGACY_TLS_COMPAT_SETTING, Boolean.class));
        OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();
        if (this.legacyTrustCompatEnabled)
            this.applyLegacyTlsCompat(okHttpBuilder);
        this.client = new ApiClient(okHttpBuilder
                // ── VRChat Basic Auth encoding fix ────────────────────────────────────────
                // VRChat's API is non-standard: it expects credentials to be URL-encoded
                // BEFORE Base64-encoding for HTTP Basic Auth. Their web client does this
                // automatically, which is why logging in via browser works with any password,
                // but API clients that follow RFC 7617 (raw UTF-8 bytes in Basic Auth) fail
                // for passwords containing characters that URL-encoding changes — most
                // visibly % (→ %25) and & (→ %26), but also others like + (→ %2B) and
                // space (→ %20).
                //
                // Standard Basic Auth (RFC 7617): Base64(username + ":" + password)
                // VRChat's expected form:         Base64(url(username) + ":" + url(password))
                //
                // We URL-encode both username and password using percent-encoding before
                // passing them to Credentials.basic(). The result is then Base64-encoded
                // by Credentials.basic() as normal. This matches what VRChat's own web
                // client sends.
                .addInterceptor(chain ->
                {
                    Request req = chain.request();
                    String u = ScarletVRChat.this.pendingUsername;
                    String p = ScarletVRChat.this.pendingPassword;
                    if (req.tag(SnapshotIdentity.class) == null && u != null && !u.isEmpty() && p != null && !p.isEmpty())
                    {
                        String uEnc = encodeVrchatBasicAuthComponent(u);
                        String pEnc = encodeVrchatBasicAuthComponent(p);
                        String cred = okhttp3.Credentials.basic(uEnc, pEnc, java.nio.charset.StandardCharsets.UTF_8);
                        req = req.newBuilder()
                            .header("Authorization", cred)
                            .build();
                    }
                    return chain.proceed(req);
                })
                // All calls share backoff; audit GETs additionally share a slow,
                // single-permit pacer. Only scoped historical reads retry 429s.
                .addInterceptor(this.auditRequests)
                .addNetworkInterceptor(this::intercept)
                .cookieJar(this.cookies)
                .build())
            .setBasePath(Scarlet.API_BASE_2)
            .setUserAgent(Scarlet.USER_AGENT);
        // override default Gson
        initJson();
        // override default ApiClient
        Configuration.setDefaultApiClient(this.client);
        this.cookies.setup(this.client);
        this.groupId = MiscUtils.extractTypedUuid("grp", "", scarlet.settings.getStringOrRequireInput("vrchat_group_id", "VRChat Group ID", false));
        ExtendedUserAgent.setCurrentUserId(null);
        ExtendedUserAgent.setCurrentGroupId(this.groupId);
        this.groupOwnerId = null;
        this.group = null;
        this.groupLimitedMember = null;
        this.groupRoles = Collections.synchronizedMap(new LinkedHashMap<>());
        this.allGroupPermissions = new VrcAllGroupPermissions(null);
        this.currentUser = null;
        this.currentUserId = null;
        this.secureStoreReady = false;
        this.cachedUsers = new ScarletJsonCache<>("usr", User.class);
        this.cachedWorlds = new ScarletJsonCache<>("wrld", World.class);
        this.cachedGroups = new ScarletJsonCache<>("grp", Group.class);
        this.cachedUserGroups = new ScarletJsonCache<>("gmem", new TypeToken<List<LimitedUserGroups>>(){});
        this.cachedAvatars = new ScarletJsonCache<>("avtr", Avatar.class);
        this.cachedPrints = new ScarletJsonCache<>("prnt", Print.class);
        this.cachedProps = new ScarletJsonCache<>("prop", Prop.class);
        this.cachedInventoryItems = new ScarletJsonCache<>("inv", InventoryItem.class);
        this.cachedModelFiles = new ScarletJsonCache<>("file", ModelFile.class);
        this.cachedFileAnalyses = new ScarletJsonCache<>("file.analysis", FileAnalysis.class);
        this.cachedPublicProfiles = new ScarletJsonCache<>("pubprof", io.github.vrchatapi.model.PublicProfile.class);
    }

    final Scarlet scarlet;
    /**
     * Global gate for all VRChat API traffic — smooths aggregate request bursts and,
     * on a 429, backs the whole client off (see {@link VrcRateLimiter}). Sits in the
     * okhttp interceptor chain below. Sustained rate/burst are deliberately generous
     * so it only bites under heavy combined load; the real protection is the 429
     * backoff.
     */
    final net.sybyline.scarlet.util.VrcRateLimiter rateLimiter = new net.sybyline.scarlet.util.VrcRateLimiter(10.0, 20.0);
    final ScarletAuditRequestPolicy auditRequests = new ScarletAuditRequestPolicy(this.rateLimiter, this::maybeAlertHardRateLimit);
    final ScarletSettings.RegistryStringEncrypted username, password, totpsecret;
    final ScarletVRChatCookieJar cookies;
    final ApiClient client;
    volatile boolean secureStoreReady;
    final boolean legacyTrustCompatEnabled;
    volatile boolean legacyTrustCompatPrompted;
    /** Mirrors the last value passed to client.setUsername() for use in the UTF-8 Auth interceptor. */
    volatile String pendingUsername = null;
    /** Mirrors the last value passed to client.setPassword() for use in the UTF-8 Auth interceptor. */
    volatile String pendingPassword = null;
    volatile String groupId;
    String groupOwnerId;
    Group group;
    GroupMember groupLimitedMember;
    final Map<String, GroupRole> groupRoles;
    VrcAllGroupPermissions allGroupPermissions;
    CurrentUser currentUser;
    volatile String currentUserId;
    private static final Object SNAPSHOT_LIFECYCLE_LOCK = new Object();
    private ScarletSnapshotFetch snapshotFetch;
    private volatile boolean snapshotsStopped;
    final ScarletJsonCache<User> cachedUsers;
    final ScarletJsonCache<World> cachedWorlds;
    final ScarletJsonCache<Group> cachedGroups;
    final ScarletJsonCache<List<LimitedUserGroups>> cachedUserGroups;

    final ScarletJsonCache<Avatar> cachedAvatars;
    final ScarletJsonCache<Print> cachedPrints;
    final ScarletJsonCache<Prop> cachedProps;
    final ScarletJsonCache<InventoryItem> cachedInventoryItems;
    final ScarletJsonCache<ModelFile> cachedModelFiles;
    final ScarletJsonCache<FileAnalysis> cachedFileAnalyses;
    final ScarletJsonCache<io.github.vrchatapi.model.PublicProfile> cachedPublicProfiles;
    long localDriftMillis = 0L,
         latencyMillis = 0L;

    public ApiClient getClient()
    {
        return this.client;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String encodeVrchatBasicAuthComponent(String value)
    {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes)
        {
            int b = raw & 0xFF;
            if (isVrchatBasicAuthUnescaped(b))
            {
                encoded.append((char)b);
            }
            else
            {
                encoded.append('%');
                encoded.append(HEX[b >>> 4]);
                encoded.append(HEX[b & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isVrchatBasicAuthUnescaped(int b)
    {
        return (b >= 'A' && b <= 'Z')
            || (b >= 'a' && b <= 'z')
            || (b >= '0' && b <= '9')
            || b == '-' || b == '_' || b == '.' || b == '!'
            || b == '~' || b == '*' || b == '\'' || b == '(' || b == ')';
    }

    private static String summarizeApiException(ApiException apiex)
    {
        int code = apiex.getCode();
        String body = apiex.getResponseBody();
        if (body == null || body.trim().isEmpty())
            return "HTTP " + code;
        body = body.replace('\r', ' ').replace('\n', ' ').trim();
        return "HTTP " + code + " " + MiscUtils.maybeEllipsis(512, body);
    }

    /** Whether Scarlet currently holds a valid VRChat session (logged in). */
    public boolean isSessionValid()
    {
        return this.currentUserId != null;
    }

    /**
     * Actively probes whether the VRChat session still works by fetching the current
     * user. {@link #isSessionValid()} only reflects cached login state — it cannot
     * notice a cookie that expired mid-run, which would otherwise silently stop audit
     * polling and moderation until someone looked.
     *
     * @return {@code TRUE} if the session works, {@code FALSE} if VRChat rejected it
     *         as unauthorized, or {@code null} if the probe was inconclusive (network
     *         trouble is not an auth verdict).
     */
    public Boolean probeSession()
    {
        long headerGeneration = ExtendedUserAgent.currentAuthenticationGeneration();
        try
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            CurrentUser user = this.getCurrentUser(auth);
            if (user == null)
                return null;
            this.currentUserId = (this.currentUser = user).getId();
            ExtendedUserAgent.setCurrentUserIdIfUnchanged(user.getId(), headerGeneration);
            return Boolean.TRUE;
        }
        catch (ApiException apiex)
        {
            if (apiex.getCode() == 401)
                ExtendedUserAgent.setCurrentUserIdIfUnchanged(null, headerGeneration);
            return apiex.getCode() == 401 ? Boolean.FALSE : null;
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    /**
     * Attempts an unattended re-login using the stored credentials and TOTP secret
     * (the same path startup uses). Returns true only when the recovered session
     * demonstrably works.
     */
    public synchronized boolean tryRecoverSession()
    {
        try
        {
            this.login();
            return Boolean.TRUE.equals(this.probeSession());
        }
        catch (Throwable t)
        {
            LOG.warn("Unattended session recovery failed: {}", t.toString());
            return false;
        }
    }

    private volatile long lastRateLimitAlertMillis = 0L;
    /** Only 429 backoffs at least this long are alert-worthy; short ones are routine. */
    static final long RATE_LIMIT_ALERT_MIN_BACKOFF_MILLIS = 60_000L;
    /** Minimum spacing between rate-limit alerts so a burst of 429s pings staff once. */
    static final long RATE_LIMIT_ALERT_THROTTLE_MILLIS = 600_000L;

    void maybeAlertHardRateLimit(long backoffMs)
    {
        if (backoffMs < RATE_LIMIT_ALERT_MIN_BACKOFF_MILLIS)
            return;
        long now = System.currentTimeMillis();
        if (now - this.lastRateLimitAlertMillis < RATE_LIMIT_ALERT_THROTTLE_MILLIS)
            return;
        this.lastRateLimitAlertMillis = now;
        LOG.warn("VRChat is rate limiting hard: backing off for {} seconds", backoffMs / 1000L);
        try
        {
            this.scarlet.splash.queueFeedbackPopup(null, 8_000L,
                I18n.tr("health.rateLimited"),
                I18n.tr("health.rateLimitedDetail", backoffMs / 1000L),
                java.awt.Color.ORANGE, java.awt.Color.ORANGE);
            if (this.scarlet.discord != null)
                this.scarlet.discord.emitOpsAlert("VRChat rate limiting",
                    "VRChat returned 429 with a long backoff: pausing API calls for "
                    + (backoffMs / 1000L) + " seconds. Moderation actions and audit polling will be delayed.",
                    0xE67E22);
        }
        catch (Exception ex)
        {
            LOG.debug("Rate-limit alert emission failed", ex);
        }
    }

    /** Display name of the logged-in VRChat account, or null if not logged in. */
    public String currentUserDisplayName()
    {
        return this.currentUser == null ? null : this.currentUser.getDisplayName();
    }

    /** Conservative floor for missing/zero hints; longer server cooldowns are preserved. */
    static long parseRetryAfterMillis(Response response, long defaultMs)
    {
        return ScarletAuditRequestPolicy.retryAfterMillis(response, defaultMs, System.currentTimeMillis());
    }

    private Response intercept(Interceptor.Chain chain) throws IOException
    {
        Request request = chain.request();
        Response originalResponse = chain.proceed(request);
        Object tag = request.tag();
        return tag instanceof ApiCallback
            ? originalResponse.newBuilder().body(new ProgressResponseBody(originalResponse.body(), (ApiCallback<?>)tag)).build()
            : originalResponse;
    }

    private void applyLegacyTlsCompat(OkHttpClient.Builder builder)
    {
        try
        {
            X509TrustManager systemTrust = defaultTrustManager();
            X509TrustManager compatTrust = compatTrustManager();
            X509TrustManager composite = new CompositeX509TrustManager(systemTrust, compatTrust);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { composite }, null);
            builder.sslSocketFactory(sslContext.getSocketFactory(), composite);
            LOG.warn("Using Hatson321/Scarlet Logger's bundled VRChat TLS compatibility certificates");
        }
        catch (GeneralSecurityException | IOException ex)
        {
            LOG.error("Failed to initialize bundled VRChat TLS compatibility certificates", ex);
        }
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException
    {
        return defaultTrustManager(null);
    }

    private static X509TrustManager defaultTrustManager(String provider) throws GeneralSecurityException
    {
        TrustManagerFactory tmf = trustManagerFactory(provider);
        tmf.init((KeyStore)null);
        return pickTrustManager(tmf.getTrustManagers());
    }

    private static X509TrustManager compatTrustManager() throws GeneralSecurityException, IOException
    {
        return compatTrustManager(null);
    }

    private static X509TrustManager compatTrustManager(String provider) throws GeneralSecurityException, IOException
    {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try
        {
            ks.load(null, null);
        }
        catch (IOException ioex)
        {
            throw ioex;
        }
        catch (Exception ex)
        {
            throw new GeneralSecurityException("Failed to initialize compatibility keystore", ex);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < LEGACY_TLS_COMPAT_CERT_RESOURCES.length; i++)
        {
            String resource = LEGACY_TLS_COMPAT_CERT_RESOURCES[i];
            try (InputStream in = ScarletVRChat.class.getResourceAsStream(resource))
            {
                if (in == null)
                    throw new IOException("Missing compatibility certificate resource " + resource);
                X509Certificate cert = (X509Certificate)cf.generateCertificate(in);
                ks.setCertificateEntry("scarlet-compat-" + i, cert);
            }
        }
        TrustManagerFactory tmf = trustManagerFactory(provider);
        tmf.init(ks);
        return pickTrustManager(tmf.getTrustManagers());
    }

    private static TrustManagerFactory trustManagerFactory(String provider) throws GeneralSecurityException
    {
        if (provider == null)
            return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        return TrustManagerFactory.getInstance("PKIX", provider);
    }

    private static X509TrustManager pickTrustManager(TrustManager[] trustManagers) throws GeneralSecurityException
    {
        for (TrustManager trustManager : trustManagers)
            if (trustManager instanceof X509TrustManager)
                return (X509TrustManager)trustManager;
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    private boolean maybeEnableLegacyTrustCompatibility(Throwable t)
    {
        if (this.legacyTrustCompatEnabled || !isLegacyTrustFailure(t) || this.legacyTrustCompatPrompted)
            return false;
        this.legacyTrustCompatPrompted = true;
        LOG.warn("Detected TLS trust-store failure while talking to VRChat; offering compatibility certificates", t);
        boolean enable = this.scarlet.settings.requireConfirmYesNo(
            "Scarlet detected that this Java runtime cannot validate VRChat's HTTPS certificate chain.\n\n"
            + "This usually happens on older Windows and Java installs with outdated root certificates.\n\n"
            + "Would you like Hatson321/Scarlet Logger to enable its built-in VRChat compatibility certificates on next launch?\n\n"
            + "This only affects Hatson321/Scarlet Logger's VRChat HTTPS client.\n"
            + "It does NOT modify your Windows certificate store or Java installation.",
            "Enable VRChat TLS compatibility?");
        if (!enable)
            return false;
        this.scarlet.settings.setObject(LEGACY_TLS_COMPAT_SETTING, Boolean.class, Boolean.TRUE);
        if (!GraphicsEnvironment.isHeadless())
        {
            JOptionPane.showMessageDialog(null,
                "Hatson321/Scarlet Logger saved the VRChat TLS compatibility setting.\nPlease restart Hatson321/Scarlet Logger and try logging in again.",
                "Restart Hatson321/Scarlet Logger",
                JOptionPane.INFORMATION_MESSAGE);
        }
        else
        {
            LOG.warn("Hatson321/Scarlet Logger saved the VRChat TLS compatibility setting. Restart Hatson321/Scarlet Logger and try logging in again.");
        }
        return true;
    }

    private static boolean isLegacyTrustFailure(Throwable t)
    {
        for (Throwable cur = t; cur != null; cur = cur.getCause())
        {
            String msg = cur.getMessage();
            if (msg == null)
                continue;
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("pkix path building failed")
                || lower.contains("unable to find valid certification path")
                || lower.contains("unable to find valid certification path to requested target")
                || lower.contains("certificate_unknown")
                || lower.contains("sun.security.validator.validatorexception"))
                return true;
            if (cur instanceof SSLHandshakeException && lower.contains("trust"))
                return true;
        }
        return false;
    }

    private static final class CompositeX509TrustManager implements X509TrustManager
    {
        private final X509TrustManager primary;
        private final X509TrustManager fallback;
        private final X509Certificate[] issuers;
        CompositeX509TrustManager(X509TrustManager primary, X509TrustManager fallback)
        {
            this.primary = primary;
            this.fallback = fallback;
            X509Certificate[] a = primary.getAcceptedIssuers(),
                              b = fallback.getAcceptedIssuers();
            this.issuers = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, this.issuers, a.length, b.length);
        }
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            this.primary.checkClientTrusted(chain, authType);
        }
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            try
            {
                this.primary.checkServerTrusted(chain, authType);
            }
            catch (java.security.cert.CertificateException ex)
            {
                this.fallback.checkServerTrusted(chain, authType);
            }
        }
        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return this.issuers.clone();
        }
    }

    public long getLocalDriftMillis()
    {
        return this.localDriftMillis;
    }

    public void login()
    {
        this.ensureSecureStoreReady();
        this.login(false);
        for (String alt : this.cookies.alts())
            this.loginAlt(alt, false);
    }

    private synchronized void ensureSecureStoreReady()
    {
        if (this.secureStoreReady)
            return;
        this.scarlet.splash.splashSubtext("Opening secure credential store");
        LOG.info("Opening secure credential store");
        this.cookies.load();
        this.secureStoreReady = true;
        LOG.info("Secure credential store ready");
    }

    private void login(boolean isRefresh)
    {
        // Keep cached account state for the existing recovery scheduler, but do not
        // identify avatar queries with a prior account while re-authenticating.
        long headerGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        String authenticatedPrimaryUserId = null;
        boolean loginFinalized = false;
try
{
        try
        {
            long timePre = System.currentTimeMillis(),
                 timeServer = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli(),
                 timePost = System.currentTimeMillis(),
                 drift = (timePre + timePost) / 2 - timeServer,
                 latency = Math.max(timePost - timePre, 0L) / 2,
                 driftAbs = Math.abs(drift);
            if (driftAbs >= 1_000L)
            {
                LOG.warn("Local system time is "+driftAbs+(drift > 0 ? "ms ahead of VRChat servers" : "ms behind VRChat servers"));
            }
            this.localDriftMillis = drift;
            this.latencyMillis = latency;
        }
        catch (ApiException apiex)
        {
            if (this.maybeEnableLegacyTrustCompatibility(apiex))
                return;
            LOG.error("Exception calculating drift", apiex);
        }
        {
            String username = this.scarlet.settings.getString("vrc_username"),
                   password = this.scarlet.settings.getString("vrc_password");
            if (username != null)
            {
                this.username.set(username);
                this.scarlet.settings.getJson().remove("vrc_username");
            }
            if (password != null)
            {
                this.password.set(password);
                this.scarlet.settings.getJson().remove("vrc_password");
            }
        }
        try
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            try
            {
                if (auth.verifyAuthToken().getOk().booleanValue())
                {
                    LOG.info("Logged in (cached-verified)");
                    try
                    {
                        authenticatedPrimaryUserId = this.currentUserId = (this.currentUser = this.getCurrentUser(auth)).getId();
                    }
                    catch (ApiException apiex)
                    {
                        LOG.info("Exception getting current user even though cached auth should be valid", apiex);
                    }
                    return;
                }
                else
                {
                    if (!isRefresh) LOG.info("Auth declared invalid");
                }
            }
            catch (ApiException apiex)
            {
                if (this.maybeEnableLegacyTrustCompatibility(apiex))
                    return;
                if (!isRefresh) LOG.info("Auth discovered invalid", apiex);
            }
            JsonObject data;
            try
            {
                data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                if (data.has("id"))
                {
                    LOG.info("Logged in (cached)");
                    authenticatedPrimaryUserId = this.currentUserId = (this.currentUser = JSON.getGson().fromJson(data, CurrentUser.class)).getId();
                    return;
                }
            }
            catch (Exception ex)
            {
                if (this.maybeEnableLegacyTrustCompatibility(ex))
                    return;
                if (!isRefresh) LOG.info("Cached auth not valid", ex);
                do
                {
                    boolean hadStoredCredentials = false;
                    try
                    {
                        String username = this.username.getOrNull(),
                               password = this.password.getOrNull();
                        hadStoredCredentials = username != null || password != null;
                        // Debug offline start: don't prompt for a sign-in that isn't stored — leave
                        // the account logged out so the UI can be exercised with no VRChat/Discord setup.
                        if ((username == null || password == null) && net.sybyline.scarlet.Debug.OFFLINE)
                        {
                            LOG.info("Debug offline mode: skipping VRChat sign-in prompt");
                            return;
                        }
                        if (username == null)
                        {
                            username = this.scarlet.settings.getStringOrRequireInput("vrc_username", "VRChat Username", false);
                            this.scarlet.settings.getJson().remove("vrc_username");
                            this.username.set(username);
                        }
                        if (password == null)
                        {
                            password = this.scarlet.settings.getStringOrRequireInput("vrc_password", "VRChat Password", true);
                            this.scarlet.settings.getJson().remove("vrc_password");
                            this.password.set(password);
                        }
                        if (username == null || password == null)
                        {
                            LOG.info("Canceling credentials");
                            return;
                        }
                        this.client.setUsername(username);
                        this.client.setPassword(password);
                        this.pendingUsername = username;
                        this.pendingPassword = password;
                        data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                        if (data.has("id"))
                        {
                            LOG.info("Logged in (credentials)");
                            authenticatedPrimaryUserId = this.currentUserId = (this.currentUser = JSON.getGson().fromJson(data, CurrentUser.class)).getId();
                            return;
                        }
                    }
                catch (ApiException apiex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(apiex))
                        return;
                    this.username.set(null);
                    this.password.set(null);
                    LOG.error("Invalid credentials: " + summarizeApiException(apiex));
                    // Only offer the reset dialog when stored credentials silently
                    // failed — not when the user just typed them in this iteration
                    // (in which case the loop will simply re-prompt). Using the
                    // synchronous form so the do-while waits for the user's answer
                    // before looping back, preventing a tight spin/hang on Windows.
                    if (hadStoredCredentials)
                    {
                        boolean reset = this.scarlet.settings.requireConfirmYesNo(
                            "The stored credentials were rejected by VRChat.\n\n"
                            + "Would you like to reset all stored credentials?\n"
                            + "This safely removes them from the encrypted store\n"
                            + "so you can enter them fresh on the next attempt.\n\n"
                            + "(You do NOT need to touch the Registry or AppData.)",
                            "Invalid credentials - reset?");
                        if (reset)
                        {
                            this.clearCredentials();
                            // The retry is a new primary authentication attempt after the reset.
                            headerGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
                        }
                    }
                    data = null;
                }
                finally
                {
                    this.client.setUsername(null);
                    this.client.setPassword(null);
                    this.pendingUsername = null;
                    this.pendingPassword = null;
                }
                }
                while (data == null);
            }
            
            List<String> twoFactorMethods = data.get("requiresTwoFactorAuth")
                    .getAsJsonArray()
                    .asList()
                    .stream()
                    .map(JsonElement::getAsJsonPrimitive)
                    .map(JsonPrimitive::getAsString)
                    .collect(Collectors.toList());
            if (twoFactorMethods.contains("totp"))
            {
                String secret = this.scarlet.settings.getString("vrc_secret");
                if (secret == null)
                    secret = this.totpsecret.getOrNull();
                else
                {
                    this.totpsecret.set(secret);
                    this.scarlet.settings.getJson().remove("vrc_secret");
                }
                if (secret != null && (secret = secret.replaceAll("[^A-Za-z2-7=]", "")).length() == 32)
                {
                    boolean authed = false;
                    for (int tries = 2; !authed && tries --> 0; MiscUtils.sleep(3_000L)) try
                    {
                        // use VRChatAPI time to work around potential local system time drift
                        long now = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli();
                        String code = TimeBasedOneTimePasswordUtil.generateNumberString(secret, now, TimeBasedOneTimePasswordUtil.DEFAULT_TIME_STEP_SECONDS, TimeBasedOneTimePasswordUtil.DEFAULT_OTP_LENGTH);
                        authed = auth.verify2FA(new TwoFactorAuthCode().code(code)).getVerified().booleanValue();
                    }
                    catch (GeneralSecurityException gsex)
                    {
                        LOG.error("Exception generating totp secret", gsex);
                    }
                    catch (ApiException apiex)
                    {
                        LOG.error("Exception using totp secret", apiex);
                    }
                }
                
                for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                {
                    if (needsTotp = !auth.verify2FA(new TwoFactorAuthCode().code(this.scarlet.settings.requireInput("Totp code", true))).getVerified().booleanValue())
                        LOG.error("Invalid totp code");
                }
                catch (ApiException apiex)
                {
                    LOG.error("Exception using totp", apiex);
                }
                
                LOG.info("Logged in (2fa-totp)");
            }
            else if (twoFactorMethods.contains("emailOtp"))
            {
                for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                {
                    if (needsTotp = !auth.verify2FAEmailCode(new TwoFactorEmailCode().code(this.scarlet.settings.requireInput("EmailOtp code", true))).getVerified().booleanValue())
                        LOG.error("Invalid emailOtp code");
                }
                catch (ApiException apiex)
                {
                    LOG.error("Exception using emailOtp", apiex);
                }
                
                LOG.info("Logged in (2fa-emailOtp)");
            }
            else
            {
                String message = "Unsupported 2fa methods: "+twoFactorMethods;
                LOG.error(message);
                throw new UnsupportedOperationException(message);
            }

            try
            {
                authenticatedPrimaryUserId = this.currentUserId = (this.currentUser = this.getCurrentUser(auth)).getId();
            }
            catch (ApiException apiex)
            {
                LOG.info("Exception getting current user even though current auth should be valid", apiex);
            }
            
            return;
        }
        finally
        {
            this.save();
            this.updateGroupInfo();
            loginFinalized = true;
        }
}
finally
{
    ExtendedUserAgent.completePrimaryAuthentication(loginFinalized ? authenticatedPrimaryUserId : null, headerGeneration);
}
    }

    class AltCred extends ScarletVRChatCookieJar.AltCredContext
    {
        final boolean isNew;
        ScarletSettings.RegistryStringEncrypted altUsername, altPassword, altSecret;
        public AltCred(String altUserId)
        {
            ScarletVRChat.this.cookies.super(altUserId);
            this.isNew = altUserId == null;
            this.init(altUserId);
        }
        boolean init(String altUserId)
        {
            if (altUserId == null)
                return false;
            if (!this.isNew)
                return false;
            if (this.altUsername == null)
                this.altUsername = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":username", true);
            if (this.altPassword == null)
                this.altPassword = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":password", true);
            if (this.altSecret == null)
                this.altSecret = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":totpsecret", true);
            return true;
        }
        void updateUP(String altUserId, String username, String password)
        {
            if (!this.init(altUserId))
                return;
            this.altUsername.set(username);
            this.altPassword.set(password);
        }
        void updateS(String altUserId, String totpsecret)
        {
            if (!this.init(altUserId))
                return;
            this.altSecret.set(totpsecret);
        }
        String getU() { return this.altUsername == null ? null : this.altUsername.getOrNull(); }
        String getP() { return this.altPassword == null ? null : this.altPassword.getOrNull(); }
        String getS() { return this.altSecret == null ? null : this.altSecret.getOrNull(); }
    }
    private synchronized String loginAlt(String context, boolean isRefresh)
    {
        if (context != null && this.cookies.isRemoved(context))
            return null;
        try (AltCred alt = new AltCred(context))
        {
            try
            {
                AuthenticationApi auth = new AuthenticationApi(this.client);
                if (!alt.isNew) try
                {
                    if (auth.verifyAuthToken().getOk().booleanValue())
                    {
                        LOG.info("Logged in (cached-verified); alt: "+context);
                        String altUserId = null;
                        try
                        {
                            altUserId = this.getCurrentUser(auth).getId();
                        }
                        catch (ApiException apiex)
                        {
                            LOG.info("Exception getting current user even though cached auth should be valid; alt: "+context+"/"+altUserId, apiex);
                        }
                        return altUserId;
                    }
                    else
                    {
                        if (!isRefresh) LOG.info("Auth declared invalid; alt: "+context);
                    }
                }
                catch (ApiException apiex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(apiex))
                        return null;
                    if (!isRefresh) LOG.info("Auth discovered invalid; alt: "+context, apiex);
                }
                JsonObject data;
                String username = null, password = null;
                try
                {
                    data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                    if (data.has("id"))
                    {
                        String altUserId = data.getAsJsonPrimitive("id").getAsString();
                        LOG.info("Logged in (cached); alt: "+altUserId);
                        if (alt.isNew)
                            alt.transferTo(altUserId);
                        return altUserId;
                    }
                }
                catch (Exception ex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(ex))
                        return null;
                    if (!isRefresh) LOG.info("Cached auth not valid: "+context, ex);
                    boolean hadStoredAltCredentials = false;
                    do try
                    {
                        username = alt.altUsername == null ? null : alt.altUsername.getOrNull();
                        password = alt.altPassword == null ? null : alt.altPassword.getOrNull();
                        hadStoredAltCredentials = username != null || password != null;
                        if (username == null)
                        {
                            username = this.scarlet.settings.requireInput("Alternate VRChat Username", false);
                            if (alt.altUsername != null)
                                alt.altUsername.set(username);
                        }
                        if (password == null)
                        {
                            password = this.scarlet.settings.requireInput("Alternate VRChat Password", true);
                            if (alt.altPassword != null)
                                alt.altPassword.set(password);
                        }
                        this.client.setUsername(username);
                        this.client.setPassword(password);
                        this.pendingUsername = username;
                        this.pendingPassword = password;
                        data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                        if (data.has("id"))
                        {
                            String altUserId = data.getAsJsonPrimitive("id").getAsString();
                            LOG.info("Logged in (credentials); alt: "+altUserId);
                            alt.updateUP(altUserId, username, password);
                            if (alt.isNew)
                                alt.transferTo(altUserId);
                            return altUserId;
                        }
                      }
                      catch (ApiException apiex)
                      {
                          if (this.maybeEnableLegacyTrustCompatibility(apiex))
                              return null;
                          if (alt.altUsername != null)
                              alt.altUsername.set(null);
                          if (alt.altPassword != null)
                            alt.altPassword.set(null);
                        data = null;
                        if ((username == null || username.isEmpty()) && (password == null || password.isEmpty()))
                        {
                            LOG.info("Canceling credentials; alt: "+context);
                            return null;
                        }
                        LOG.error("Invalid credentials; alt: "+context+": "+summarizeApiException(apiex));
                        // Only offer reset when stored alt credentials silently failed.
                        if (hadStoredAltCredentials)
                        {
                            boolean reset = this.scarlet.settings.requireConfirmYesNo(
                                "The stored alternate credentials for \""+context+"\" were rejected by VRChat.\n\n"
                                + "Would you like to reset ALL stored credentials?\n"
                                + "This safely removes them from the encrypted store\n"
                                + "so you can enter them fresh on the next attempt.\n\n"
                                + "(You do NOT need to touch the Registry or AppData.)",
                                "Invalid credentials - reset?");
                            if (reset)
                                this.clearCredentials();
                        }
                    }
                    finally
                    {
                        this.client.setUsername(null);
                        this.client.setPassword(null);
                        this.pendingUsername = null;
                        this.pendingPassword = null;
                    }
                    while (data == null);
                }
                
                List<String> twoFactorMethods = data.get("requiresTwoFactorAuth")
                        .getAsJsonArray()
                        .asList()
                        .stream()
                        .map(JsonElement::getAsJsonPrimitive)
                        .map(JsonPrimitive::getAsString)
                        .collect(Collectors.toList());
                String code = null;
                if (twoFactorMethods.contains("totp"))
                {
                    String secret = alt.altSecret == null ? null : alt.altSecret.getOrNull();
                    if (secret != null && (secret = secret.replaceAll("[^A-Za-z2-7=]", "")).length() == 32)
                    {
                        boolean authed = false;
                        for (int tries = 2; !authed && tries --> 0; MiscUtils.sleep(3_000L)) try
                        {
                            // use VRChatAPI time to work around potential local system time drift
                            long now = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli();
                            code = TimeBasedOneTimePasswordUtil.generateNumberString(secret, now, TimeBasedOneTimePasswordUtil.DEFAULT_TIME_STEP_SECONDS, TimeBasedOneTimePasswordUtil.DEFAULT_OTP_LENGTH);
                            authed = auth.verify2FA(new TwoFactorAuthCode().code(code)).getVerified().booleanValue();
                        }
                        catch (GeneralSecurityException gsex)
                        {
                            LOG.error("Exception generating totp secret; alt: "+context, gsex);
                        }
                        catch (ApiException apiex)
                        {
                            LOG.error("Exception using totp secret; alt: "+context, apiex);
                        }
                    }
                    
                    for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                    {
                        if (needsTotp = !auth.verify2FA(new TwoFactorAuthCode().code(code = this.scarlet.settings.requireInput("Totp code", true))).getVerified().booleanValue())
                            LOG.error("Invalid totp code; alt: "+context);
                    }
                    catch (ApiException apiex)
                    {
                        if (code == null || code.isEmpty())
                        {
                            LOG.info("Canceling using totp; alt: "+context);
                            return null;
                        }
                        LOG.error("Exception using totp; alt: "+context, apiex);
                    }
                    
                    LOG.info("Logged in (2fa-totp); alt: "+context);
                }
                else if (twoFactorMethods.contains("emailOtp"))
                {
                    for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                    {
                        if (needsTotp = !auth.verify2FAEmailCode(new TwoFactorEmailCode().code(code = this.scarlet.settings.requireInput("EmailOtp code", true))).getVerified().booleanValue())
                            LOG.error("Invalid emailOtp code; alt: "+context);
                    }
                    catch (ApiException apiex)
                    {
                        if (code == null || code.isEmpty())
                        {
                            LOG.info("Canceling using emailOtp; alt: "+context);
                            return null;
                        }
                        LOG.error("Exception using emailOtp; alt: "+context, apiex);
                    }
                    
                    LOG.info("Logged in (2fa-emailOtp); alt: "+context);
                }
                else
                {
                    String message = "Unsupported 2fa methods: "+twoFactorMethods+"; alt: "+context;
                    LOG.error(message);
                    throw new UnsupportedOperationException(message);
                }
    
                String altUserId = null;
                try
                {
                    altUserId = this.getCurrentUser(auth).getId();
                    alt.updateUP(altUserId, username, password);
                    if (alt.isNew)
                        alt.transferTo(altUserId);
                }
                catch (ApiException apiex)
                {
                    LOG.info("Exception getting current user even though current auth should be valid; alt: "+context+"/"+altUserId, apiex);
                }
    
                return altUserId;
            }
            finally
            {
                this.save();
            }
        }
    }
    void updateGroupInfo()
    {
        // Check if we have a valid currentUserId before proceeding
        if (this.currentUserId == null)
        {
            LOG.warn("Cannot update group info: currentUserId is null (login may have failed)");
            return;
        }
        this.resolveActiveGroupId();
        if (MiscUtils.blank(this.groupId))
        {
            LOG.error("Cannot update group info: no valid VRChat group id is configured or could be auto-resolved for user `{}`", this.currentUserId);
            return;
        }
        Group group;
        try
        {
            group = this.getGroup(this.groupId, Boolean.TRUE);
        }
        catch (RuntimeException ex)
        {
            LOG.error("Cannot update group info because VRChat returned an unexpected group payload", ex);
            return;
        }
        if (group != null)
        {
            this.groupOwnerId = group.getOwnerId();
            this.group = group;
            this.groupLimitedMember = this.getGroupMembership(this.groupId, this.currentUserId);
            if (this.groupLimitedMember == null)
            {
                if (this.scarlet.settings.requireConfirmYesNo("The main VRChat account does not appear\nto be part of the group, delete credentials?", "Wrong account?"))
                {
                    long headerGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
                    try
                    {
                        this.cookies.clear();
                        this.cookies.save();
                    }
                    finally
                    {
                        ExtendedUserAgent.completePrimaryAuthentication(null, headerGeneration);
                    }
                    throw new IllegalStateException();
                }
            }
            if (group.getRoles() == null)
            {
                group.setRoles(this.getGroupRoles(this.groupId));
                this.groupRoles.clear();
                group.getRoles().forEach(role -> this.groupRoles.put(role.getId(), role));
            }
        }
        try
        {
            Map<String, String> localVarHeaderParams = new HashMap<>();
            localVarHeaderParams.put("Accept", "application/json");
            okhttp3.Call localVarCall = this.client.buildCall(null, "/users/"+this.currentUserId+"/groups/permissions", "GET", new ArrayList<>(), new ArrayList<>(), null, localVarHeaderParams, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            JsonObject json = this.client.<JsonObject>execute(localVarCall, JsonObject.class).getData();
            this.allGroupPermissions = new VrcAllGroupPermissions(json);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to get group permissions: {}", ex.getMessage());
        }
    }

    private void resolveActiveGroupId()
    {
        if (!MiscUtils.blank(this.groupId))
            return;
        String resolvedGroupId = this.getRepresentedGroupId(this.currentUserId);
        if (MiscUtils.blank(resolvedGroupId))
        {
            List<LimitedUserGroups> userGroups = this.getUserGroups(this.currentUserId);
            if (userGroups != null)
            {
                resolvedGroupId = userGroups.stream()
                    .filter(Objects::nonNull)
                    .filter(group -> Boolean.TRUE.equals(group.getIsRepresenting()))
                    .map(LimitedUserGroups::getGroupId)
                    .filter(groupId -> !MiscUtils.blank(groupId))
                    .findFirst()
                    .orElseGet(() -> userGroups.size() == 1 ? userGroups.get(0).getGroupId() : null);
            }
        }
        if (!MiscUtils.blank(resolvedGroupId))
        {
            this.groupId = MiscUtils.extractTypedUuid("grp", "", resolvedGroupId);
            ExtendedUserAgent.setCurrentGroupId(this.groupId);
            this.scarlet.settings.setString("vrchat_group_id", this.groupId);
            LOG.warn("Recovered missing VRChat group id from the logged-in user's group context: `{}`", this.groupId);
        }
    }

    private String getRepresentedGroupId(String userId)
    {
        if (MiscUtils.blank(userId))
            return null;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            JsonObject represented = this.client.<JsonObject>execute(
                this.client.buildCall(
                    null,
                    "/users/"+userId+"/groups/represented",
                    "GET",
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    headers,
                    new HashMap<>(),
                    new HashMap<>(),
                    new String[]{"authCookie"},
                    null
                ),
                JsonObject.class
            ).getData();
            if (represented == null)
                return null;
            JsonElement groupId = represented.get("groupId");
            return groupId == null || groupId.isJsonNull() ? null : groupId.getAsString();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.warn("Unable to resolve represented VRChat group for `{}`: {}", userId, apiex.getMessage());
            return null;
        }
    }
    public boolean checkGroupHasAdminTag(GroupAdminTag adminTag)
    {
        if (adminTag == null)
            return false;
        Group group = this.group;
        if (group == null)
            return false;
        List<String> tags = group.getTags();
        if (tags == null || tags.isEmpty())
            return false;
        return tags.contains(adminTag.value);
    }
    /**
     * The SDK models LimitedUserInstance.date_joined as OffsetDateTime, but instance
     * responses can contain a calendar date. UTC midnight is only an SDK-compatible
     * representation of that date, not a claim about the exact account-creation time.
     * Keep this local to this model/field: closure timestamps still require a time.
     */
    private static void normalizeInstanceUserJoinDates(TypeToken<?> type, JsonElement element)
    {
        if (element == null || !element.isJsonObject())
            return;
        Class<?> rawType = type.getRawType();
        JsonObject object = element.getAsJsonObject();
        if (rawType == LimitedUserInstance.class)
        {
            normalizeInstanceUserJoinDate(object);
        }
        else if (rawType == Instance.class)
        {
            // Normalize here too: if generated Instance validation rejects an
            // unrelated new field, the fallback parser uses its own nested adapters.
            JsonElement users = object.get("users");
            if (users != null && users.isJsonArray())
                for (JsonElement user : users.getAsJsonArray())
                    if (user != null && user.isJsonObject())
                        normalizeInstanceUserJoinDate(user.getAsJsonObject());
        }
    }

    private static void normalizeInstanceUserJoinDate(JsonObject user)
    {
        JsonElement joined = user.get("date_joined");
        if (joined == null || !joined.isJsonPrimitive() || !joined.getAsJsonPrimitive().isString())
            return;
        String value = joined.getAsString();
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        {
            // ISO LocalDate parsing is strict: impossible dates must not become
            // a different day or be silently removed from the response.
            LocalDate date = LocalDate.parse(value);
            user.addProperty("date_joined", date.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
        }
    }

    /**
     * Compatibility shim for VRChat's current auth-user payload. The 1.21.0 SDK
     * models presence.currentAvatarTags as a String although the API returns an array.
     * <u><i><b>REMOVE AFTER THE API SDK MODELS THE PRESENCE FIELD AS AN ARRAY</b></i></u>
     */
    private static void normalizeCurrentUserPresenceAvatarTags(TypeToken<?> type, JsonElement element)
    {
        if (element == null || !element.isJsonObject())
            return;

        Class<?> rawType = type.getRawType();
        JsonObject presence = null;
        if ("io.github.vrchatapi.model.CurrentUser".equals(rawType.getName()))
        {
            JsonElement presenceElement = element.getAsJsonObject().get("presence");
            if (presenceElement != null && presenceElement.isJsonObject())
                presence = presenceElement.getAsJsonObject();
        }
        else if ("io.github.vrchatapi.model.CurrentUserPresence".equals(rawType.getName()))
        {
            presence = element.getAsJsonObject();
        }

        normalizePresenceAvatarTagsObject(presence);
    }

    private static void normalizePresenceAvatarTagsObject(JsonObject presence)
    {
        if (presence == null)
            return;

        JsonElement tags = presence.get("currentAvatarTags");
        if (tags == null || tags.isJsonNull() || !tags.isJsonArray())
            return;

        StringBuilder joined = new StringBuilder();
        for (JsonElement tag : tags.getAsJsonArray())
        {
            if (tag == null || tag.isJsonNull() || !tag.isJsonPrimitive())
                continue;
            if (joined.length() > 0)
                joined.append(',');
            joined.append(tag.getAsString());
        }
        presence.addProperty("currentAvatarTags", joined.toString());
        LOG.debug("Normalized VRChat presence.currentAvatarTags array for vrchatapi-java compatibility");
    }

    @Deprecated
    CurrentUser getCurrentUser(AuthenticationApi auth) throws ApiException
    {
        JsonObject json = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
        JsonObject presence = json == null ? null : json.getAsJsonObject("presence");
        normalizePresenceAvatarTagsObject(presence);
        return JSON.getGson().fromJson(json, CurrentUser.class);
    }

    public boolean logout()
    {
        return this.logout(false);
    }

    private boolean logout(boolean isRefresh)
    {
        long headerGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        try
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            if (!isRefresh) LOG.info("Log out: "+auth.logout().getSuccess().getMessage());
            return true;
        }
        catch (ApiException apiex)
        {
            LOG.error("Error logging out", apiex);
            return false;
        }
        finally
        {
            ExtendedUserAgent.completePrimaryAuthentication(null, headerGeneration);
        }
    }

    private boolean logoutAlt(String context, boolean isRefresh)
    {
        if (context != null && this.cookies.isRemoved(context))
            return false;
        try (ScarletVRChatCookieJar.AltCredContext alt = this.cookies.new AltCredContext(context))
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            if (!isRefresh) LOG.info("Log out: "+auth.logout().getSuccess().getMessage()+": "+context);
            return true;
        }
        catch (ApiException apiex)
        {
            LOG.error("Error logging out: "+context, apiex);
            return false;
        }
    }

    public void refresh()
    {
        LOG.info("Refreshing auth");
        this.logout(true);
        this.login(true);
        for (String alt : this.cookies.alts())
        {
            this.logoutAlt(alt, true);
            this.loginAlt(alt, true);
        }
    }

    Map<String, String> getConfigLanguageOptions()
    {
        MiscellaneousApi misc = new MiscellaneousApi(this.client);
        try
        {
            return misc.getConfig().getConstants().getLANGUAGE().getSPOKENLANGUAGEOPTIONS();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
        catch (Throwable t)
        {
            LOG.warn("VRChat config language options unavailable; continuing without spoken-language command choices", t);
            return null;
        }
    }

    public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to)
    {
        return this.auditQuery(from, to, null, null, null);
    }
    public List<GroupAuditLogEntryUnknown> auditQueryHistorical(OffsetDateTime from, OffsetDateTime to,
            String actorIds, String eventTypes, String targetIds)
    {
        try (ScarletAuditRequestPolicy.History scope = this.auditRequests.historicalRead())
        {
            List<GroupAuditLogEntryUnknown> result = this.auditQuery(from, to, actorIds, eventTypes, targetIds);
            if (scope.expired())
            {
                LOG.warn("Historical audit query exceeded its time budget; no complete summary result returned");
                return null;
            }
            return result;
        }
    }
    public List<GroupAuditLogEntryUnknown> auditQueryTargeting(String targetIds, int daysBack)
    {
        OffsetDateTime to = OffsetDateTime.now(),
                       from = to.minusDays(daysBack);
        return this.auditQuery(from, to, null, null, targetIds);
    }
    public List<GroupAuditLogEntryUnknown> auditQueryActored(String actorIds, int daysBack)
    {
        OffsetDateTime to = OffsetDateTime.now(),
                       from = to.minusDays(daysBack);
        return this.auditQuery(from, to, actorIds, null, null);
    }
    public List<GroupAuditLogEntryUnknown> auditQuery(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupAuditLogEntryUnknown> audits = new ArrayList<>();
            int offset = 0, batchSize = 100;
            PaginatedGroupAuditLogEntryList pgalel;
            pgalel = groups.getGroupAuditLogs(this.groupId, batchSize, offset, from, to, actorIds, eventTypes, targetIds);
            while (Boolean.TRUE.equals(pgalel.getHasNext()))
            {
                if (pgalel.getResults() != null)
                    audits.addAll(ScarletAuditData.entries(pgalel));
                offset += batchSize;
                pgalel = groups.getGroupAuditLogs(this.groupId, batchSize, offset, from, to, actorIds, eventTypes, targetIds);
            }
            if (pgalel.getResults() != null)
                audits.addAll(ScarletAuditData.entries(pgalel));
            audits.sort(OLDEST_TO_NEWEST);
            return audits;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (this.isAuditEndpointNotImplemented(apiex))
            {
                List<GroupAuditLogEntryUnknown> fallback = this.auditQueryFallback(from, to, actorIds, eventTypes, targetIds);
                if (fallback != null)
                    return fallback;
            }
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
    }
    public Integer auditQueryCount(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupAuditLogs(this.groupId, 0, 0, from, to, actorIds, eventTypes, targetIds).getTotalCount();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (this.isAuditEndpointNotImplemented(apiex))
            {
                Integer fallback = this.auditQueryCountFallback(from, to, actorIds, eventTypes, targetIds);
                if (fallback != null)
                    return fallback;
            }
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
    }

    private boolean isAuditEndpointNotImplemented(ApiException apiex)
    {
        if (apiex == null || apiex.getCode() != 404)
            return false;
        String message = apiex.getMessage();
        return message != null && message.toLowerCase().contains("not implemented by our system");
    }

    private List<GroupAuditLogEntryUnknown> auditQueryFallback(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        for (AuditFallbackMode mode : AuditFallbackMode.values())
        {
            try
            {
                LOG.warn("VRChat rejected the generated audit-log request for group `{}`; retrying with Hatson321/Scarlet Logger audit fallback mode `{}`", this.groupId, mode.name());
                List<GroupAuditLogEntryUnknown> audits = ScarletAuditQueryFallback.collect((batchSize, offset) -> {
                    return this.getGroupAuditLogsRaw(batchSize, offset, from, to, actorIds, eventTypes, targetIds, mode);
                }, from, to, actorIds, eventTypes, targetIds);
                LOG.warn("Hatson321/Scarlet Logger audit fallback mode `{}` succeeded for group `{}` with {} entrie(s)", mode.name(), this.groupId, Integer.valueOf(audits.size()));
                return audits;
            }
            catch (ApiException apiex)
            {
                if (!this.isAuditEndpointNotImplemented(apiex))
                {
                    LOG.error("Hatson321/Scarlet Logger audit fallback mode `{}` failed", mode.name(), apiex);
                    return null;
                }
                LOG.warn("Hatson321/Scarlet Logger audit fallback mode `{}` still hit VRChat's `not implemented` response", mode.name());
            }
            catch (Exception ex)
            {
                LOG.error("Hatson321/Scarlet Logger audit fallback mode `{}` failed", mode.name(), ex);
                return null;
            }
        }
        return null;
    }

    private Integer auditQueryCountFallback(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        // A weakened request's server totalCount includes entries excluded by the
        // original filters. Count only a complete, locally filtered collection.
        List<GroupAuditLogEntryUnknown> entries = this.auditQueryFallback(from, to, actorIds, eventTypes, targetIds);
        return entries == null ? null : Integer.valueOf(entries.size());
    }

    private PaginatedGroupAuditLogEntryList getGroupAuditLogsRaw(int n, int offset, OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds, AuditFallbackMode mode) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");

        List<io.github.vrchatapi.Pair> query = new ArrayList<>();
        query.addAll(this.client.parameterToPair("n", Integer.valueOf(n)));
        query.addAll(this.client.parameterToPair("offset", Integer.valueOf(offset)));

        if (mode.includeDates && from != null)
            query.addAll(this.client.parameterToPair("startDate", from));
        if (mode.includeDates && to != null)
            query.addAll(this.client.parameterToPair("endDate", to));
        if (mode.includeFilters && !MiscUtils.blank(actorIds))
            query.addAll(this.client.parameterToPair("actorIds", actorIds));
        if (mode.includeFilters && !MiscUtils.blank(eventTypes))
            query.addAll(this.client.parameterToPair("eventTypes", eventTypes));
        if (mode.includeFilters && !MiscUtils.blank(targetIds))
            query.addAll(this.client.parameterToPair("targetIds", targetIds));

        okhttp3.Call call = this.client.buildCall(
            null,
            "/groups/"+this.groupId+"/auditLogs",
            "GET",
            query,
            new ArrayList<>(),
            null,
            headers,
            new HashMap<>(),
            new HashMap<>(),
            new String[]{"authCookie"},
            null
        );
        return this.client.<PaginatedGroupAuditLogEntryList>execute(call, PaginatedGroupAuditLogEntryList.class).getData();
    }

    private enum AuditFallbackMode
    {
        FULL_FILTERS(true, true),
        NO_OPTIONAL_FILTERS(true, false),
        NO_DATES_OR_FILTERS(false, false);

        final boolean includeDates;
        final boolean includeFilters;

        AuditFallbackMode(boolean includeDates, boolean includeFilters)
        {
            this.includeDates = includeDates;
            this.includeFilters = includeFilters;
        }
    }
    public List<LimitedUserSearch> searchUsers(String name, Integer n, Integer offset)
    {
        UsersApi users = new UsersApi(this.client);
        try
        {
            return users.searchUsers(name, null, null, null, n, offset, null);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            return null;
        }
    }
    public String searchUserId(String name)
    {
        List<LimitedUserSearch> search = this.searchUsers(name, 100, 0);
        return search == null ? null : search.stream().filter(user -> name.equals(user.getDisplayName())).findFirst().map(LimitedUserSearch::getId).orElse(null);
    }

    public String getUserDisplayName(String userId)
    {
        if (userId == null)
            return null;
        User user = this.getUser(userId, Long.MIN_VALUE);
        return user == null ? userId : user.getDisplayName();
    }
    public User getUser(String userId)
    {
        return this.getUser(userId, Long.MAX_VALUE);
    }
    public User getUser(String userId, long minEpoch)
    {
        User user = this.cachedUsers.get(userId, minEpoch);
        if (user != null)
            return user;
        if (this.cachedUsers.is404(userId))
            return null;
        UsersApi users = new UsersApi(this.client);
        try
        {
            // The SDK's UserResponse also contains private self-account fields. Retain
            // only the public User projection in Scarlet's existing profile cache.
            user = this.client.<User>execute(users.getUserCall(userId, null), User.class).getData();
            this.cachedUsers.put(userId, user);
            return user;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUsers.add404(userId);
            else
                LOG.error("Error during get user: "+apiex.getMessage());
            return null;
        }
    }
    /**
     * Always fetches the user straight from the API (bypassing the cache read) and refreshes
     * the cache with the result. Use when up-to-the-moment profile data is required, e.g. to
     * detect a bio change the user just made.
     */
    public User getUserFresh(String userId)
    {
        UsersApi users = new UsersApi(this.client);
        try
        {
            User user = this.client.<User>execute(users.getUserCall(userId, null), User.class).getData();
            this.cachedUsers.put(userId, user);
            this.cachedUsers.remove404(userId);
            return user;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUsers.add404(userId);
            else
                LOG.error("Error during get user (fresh): "+apiex.getMessage());
            return null;
        }
    }

    public io.github.vrchatapi.model.PublicProfile getPublicProfile(String userId)
    {
        return this.getPublicProfile(userId, Long.MAX_VALUE);
    }
    /**
     * Fetches a user's public profile (cached). API 1.21.0 moved bio, badges and the user's
     * image fields off the User object and onto PublicProfile, so moderation embeds and bio
     * lookups resolve through here. Requested with asSelf=false, withGroupsAndWorlds=false.
     */
    public io.github.vrchatapi.model.PublicProfile getPublicProfile(String userId, long minEpoch)
    {
        if (userId == null)
            return null;
        io.github.vrchatapi.model.PublicProfile profile = this.cachedPublicProfiles.get(userId, minEpoch);
        if (profile != null)
            return profile;
        if (this.cachedPublicProfiles.is404(userId))
            return null;
        UsersApi users = new UsersApi(this.client);
        try
        {
            profile = users.getPublicProfile(userId, Boolean.FALSE, Boolean.FALSE);
            this.cachedPublicProfiles.put(userId, profile);
            return profile;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            String msg = apiex.getMessage();
            if (msg != null && msg.contains("HTTP response code: 404"))
                this.cachedPublicProfiles.add404(userId);
            else
                LOG.error("Error getting public profile: "+msg);
            return null;
        }
    }
    /**
     * Resolves the best profile image URL for a user, falling back to the robot placeholder.
     * API 1.21.0 moved currentAvatarImageUrl / iconUrl / userIcon off User onto PublicProfile,
     * so a bare User no longer carries a usable image; we fetch the (cached) public profile and
     * try those fields in order.
     */
    /**
     * Resolves the best profile image URL for a user, or null when the profile has no usable
     * image. API 1.21.0 order: full current-avatar image (carries the profile-pic override when
     * set) -> its thumbnail -> iconUrl -> userIcon.
     */
    public String getUserImageUrlOrNull(String userId)
    {
        io.github.vrchatapi.model.PublicProfile profile = this.getPublicProfile(userId, Long.MAX_VALUE);
        if (profile == null)
            return null;
        String img = MiscUtils.nonBlankOrNull(profile.getCurrentAvatarImageUrl(), profile.getCurrentAvatarThumbnailImageUrl(), profile.getIconUrl());
        return img != null ? img : MiscUtils.nonBlankOrNull(profile.getUserIcon());
    }
    /** As {@link #getUserImageUrlOrNull}, but falls back to the robot placeholder (for embed images that must not be empty). */
    public String getUserImageUrl(String userId)
    {
        String img = this.getUserImageUrlOrNull(userId);
        return img != null ? img : MiscUtils.ROBOT_IMAGE_URL;
    }
    /** API 1.21.0 moved bio off the User object; read it from the (cached) public profile. */
    public String getUserBio(String userId)
    {
        io.github.vrchatapi.model.PublicProfile profile = this.getPublicProfile(userId, Long.MAX_VALUE);
        return profile == null ? null : profile.getBio();
    }

    public List<LimitedWorld> searchWorlds(String name, Integer n, Integer offset)
    {
        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            return worlds.searchWorlds(null, SortOption.RELEVANCE, null, null, n, OrderOption.DESCENDING, offset, null, null, null, null, null, null, null, null, null, null);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            return null;
        }
    }
    public World getWorld(String worldId)
    {
        return this.getWorld(worldId, Long.MAX_VALUE);
    }
    public World getWorld(String worldId, long minEpoch)
    {
        World world = this.cachedWorlds.get(worldId, minEpoch);
        if (world != null)
            return world;
        if (this.cachedWorlds.is404(worldId))
            return null;
        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            world = worlds.getWorld(worldId);
            this.cachedWorlds.put(worldId, world);
            return world;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedWorlds.add404(worldId);
            else
                LOG.error("Error during get world: "+apiex.getMessage());
            return null;
        }
    }
    /**
     * Sends the logged-in (Scarlet) VRChat account an invite to the given instance,
     * which pops up in the running VRChat client as a clickable invite. Returns true
     * on success. Useful when VRChat is already running and relaunching it would be
     * disruptive. Requires the account to be able to see/join the instance.
     */
    public boolean selfInvite(String worldId, String instanceId)
    {
        return this.selfInvite(worldId + ":" + instanceId);
    }
    public boolean selfInvite(String location)
    {
        // The instance ID carries tags like ~group(...)~region(us). The generated
        // inviteMyselfTo() percent-encodes them (( -> %28), which VRChat's Cloudflare
        // WAF rejects as a "malformed url" (waf_code 26497). Build the path by hand
        // like getInventoryItemEx does, so okhttp leaves the parens literal — which
        // is the form VRChat accepts.
        if (location == null || location.indexOf(':') < 0)
            return false;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            okhttp3.Call call = this.client.buildCall(null, "/invite/myself/to/" + location, "POST",
                new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            this.client.execute(call);
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during self-invite to "+location+": "+apiex.getMessage());
            return false;
        }
    }

    /**
     * All friends of the logged-in (Scarlet) account, online and offline, paginated
     * transparently. Returns an empty list on error. Used by the friend-picker so
     * invites can be sent by choosing a friend instead of pasting a user ID.
     */
    public List<io.github.vrchatapi.model.LimitedUserFriend> getAllFriends()
    {
        io.github.vrchatapi.api.FriendsApi friends = new io.github.vrchatapi.api.FriendsApi(this.client);
        List<io.github.vrchatapi.model.LimitedUserFriend> all = new ArrayList<>();
        int batchSize = 100;
        for (boolean offline : new boolean[] { false, true })
        {
            for (int offset = 0; ; offset += batchSize)
            {
                List<io.github.vrchatapi.model.LimitedUserFriend> page;
                try
                {
                    page = friends.getFriends(offset, batchSize, offline);
                }
                catch (ApiException apiex)
                {
                    this.scarlet.checkVrcRefresh(apiex);
                    LOG.error("Error listing friends (offset "+offset+", offline "+offline+"): "+apiex.getMessage());
                    break;
                }
                if (page == null || page.isEmpty())
                    break;
                all.addAll(page);
                if (page.size() < batchSize)
                    break;
                MiscUtils.sleep(250L);
            }
        }
        return all;
    }

    /**
     * Whether the logged-in (Scarlet) account is friends with the given user.
     * VRChat only lets you invite users you are friends with, so this gates
     * {@link #inviteUser}. Returns false on any API error.
     */
    public boolean isFriendWith(String userId)
    {
        io.github.vrchatapi.api.FriendsApi friends = new io.github.vrchatapi.api.FriendsApi(this.client);
        try
        {
            io.github.vrchatapi.model.FriendStatus status = friends.getFriendStatus(userId);
            return status != null && Boolean.TRUE.equals(status.getIsFriend());
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error checking friend status for "+userId+": "+apiex.getMessage());
            return false;
        }
    }

    /** Result of an {@link ScarletVRChat#inviteUser} attempt, so callers can explain failures. */
    public enum InviteResult { SENT, NOT_FRIENDS, FAILED }

    /**
     * Sends {@code userId} an invite to {@code location} from the logged-in
     * (Scarlet) account. VRChat requires the two accounts to be friends, so this
     * checks friendship first and returns {@link InviteResult#NOT_FRIENDS} when
     * they aren't, {@link InviteResult#SENT} on success, or {@link InviteResult#FAILED}.
     */
    public InviteResult inviteUser(String userId, String location)
    {
        if (userId == null || location == null)
            return InviteResult.FAILED;
        if (!this.isFriendWith(userId))
            return InviteResult.NOT_FRIENDS;
        io.github.vrchatapi.api.InviteApi invite = new io.github.vrchatapi.api.InviteApi(this.client);
        try
        {
            invite.inviteUser(userId, new io.github.vrchatapi.model.InviteRequest().instanceId(location));
            return InviteResult.SENT;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error inviting "+userId+" to "+location+": "+apiex.getMessage());
            return InviteResult.FAILED;
        }
    }
    /**
     * Fetches the instance's secure join token via the dedicated /shortName endpoint,
     * which generates the same secureName the client uses on a manual join. Restricted
     * (group+, age-gated, invite, friends) instances are NOT accepted into a deep link
     * with only the plain shortName from a general instance fetch, so this endpoint is
     * what lets the auto-launch actually join them instead of dropping into the error
     * world. Path is built by hand for the same Cloudflare WAF reason as getInstance
     * (literal ~group(...) parens).
     */
    public String getInstanceSecureName(String worldId, String instanceId)
    {
        try
        {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Accept", "application/json");
            okhttp3.Call call = this.client.buildCall(null, "/instances/" + worldId + ":" + instanceId + "/shortName", "GET",
                new java.util.ArrayList<>(), new java.util.ArrayList<>(), null, headers, new java.util.HashMap<>(), new java.util.HashMap<>(), new String[]{"authCookie"}, null);
            ApiResponse<io.github.vrchatapi.model.InstanceShortNameResponse> resp = this.client.execute(call, io.github.vrchatapi.model.InstanceShortNameResponse.class);
            io.github.vrchatapi.model.InstanceShortNameResponse data = resp.getData();
            if (data != null)
            {
                if (data.getSecureName() != null && !data.getSecureName().trim().isEmpty())
                    return data.getSecureName();
                if (data.getShortName() != null && !data.getShortName().trim().isEmpty())
                    return data.getShortName();
            }
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting instance secure name for "+worldId+":"+instanceId+": "+apiex.getMessage());
        }
        return null;
    }

    public Instance getInstance(String worldId, String instanceId)
    {
        try
        {
            // Build the path by hand so instance-ID tags like ~group(...)~region(...)
            // keep their literal parentheses. The generated getInstance() percent-
            // encodes them (( -> %28), which VRChat's Cloudflare WAF rejects as a
            // "malformed url" — the same problem that broke self-invite.
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            okhttp3.Call call = this.client.buildCall(null, "/instances/" + worldId + ":" + instanceId, "GET",
                new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            ApiResponse<Instance> resp = this.client.execute(call, Instance.class);
            return resp.getData();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during get instance: "+apiex.getMessage());
        }

        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            return worlds.getWorldInstance(worldId, instanceId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during get world instance: "+apiex.getMessage());
        }
        return null;
    }
    /** Fresh primary-session occupancy only; no cache, model defaults or fallback endpoint. */
    JsonElement readIdleInstance(String location, java.util.function.BooleanSupplier allowed)
    {
        return this.idleInstanceRequest(location, false, allowed);
    }

    private long lastClientExitEvidence;

    /** Shutdown proof uses one fresh primary-account endpoint, not the legacy world fallback. */
    Instance readClientExitInstance(String location, java.util.function.BooleanSupplier allowed)
    {
        JsonElement raw = this.idleInstanceRequest(location, false, allowed, true);
        if (raw == null) return null;
        try { return JSON.getGson().fromJson(raw, Instance.class); }
        catch (RuntimeException ex) { LOG.debug("Client exit instance response could not be decoded"); return null; }
    }

    /** Optional unattended closure never requests a hard close and never retries a mutation. */
    boolean softCloseIdleInstance(String location, java.util.function.BooleanSupplier allowed)
    {
        return this.idleInstanceRequest(location, true, allowed) != null;
    }

    private JsonElement idleInstanceRequest(String location, boolean close, java.util.function.BooleanSupplier allowed)
    {
        return this.idleInstanceRequest(location, close, allowed, false);
    }

    private JsonElement idleInstanceRequest(String location, boolean close, java.util.function.BooleanSupplier allowed, boolean exitEvidence)
    {
        final String group = this.groupId, user = this.currentUserId;
        final long generation = ExtendedUserAgent.currentAuthenticationGeneration();
        java.util.function.BooleanSupplier valid = () -> allowed.getAsBoolean()
            && !Thread.currentThread().isInterrupted()
            && Objects.equals(group, this.groupId) && Objects.equals(user, this.currentUserId)
            && ExtendedUserAgent.isPrimaryAuthenticationStable(generation)
            && this.pendingUsername == null && this.pendingPassword == null
            && ScarletVRChatCookieJar._context.get() == null && ScarletVRChatCookieJar._context2.get() == null;
        if (user == null || ScarletIdleInstanceClose.groupLocation(location, group) == null || !valid.getAsBoolean())
            return null;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            if (exitEvidence) headers.put("Cache-Control", "no-cache, no-store");
            Request request = this.client.buildCall(null, "/instances/" + location, close ? "DELETE" : "GET",
                new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(),
                new String[]{"authCookie"}, null).request();
            if (close) request = request.newBuilder().url(request.url().newBuilder()
                .addQueryParameter("hardClose", "false").build()).build();
            OkHttpClient http = idleCloseHttpClient(this.client.getHttpClient(), this.cookies.copy(), valid);
            if (!valid.getAsBoolean()) return null;
            try (Response response = http.newCall(request).execute())
            {
                if (!response.isSuccessful() || !valid.getAsBoolean()) return null;
                if (close) return new JsonPrimitive(true);
                if (response.body() == null) return null;
                response.body().source().request(2L * 1024L * 1024L + 1L);
                if (response.body().source().buffer().size() > 2L * 1024L * 1024L) return null;
                JsonElement result = parseBanResponse(response.body().string());
                if (exitEvidence && valid.getAsBoolean()) synchronized (this)
                {
                    long now = System.nanoTime();
                    if (this.lastClientExitEvidence == 0L || now - this.lastClientExitEvidence >= java.util.concurrent.TimeUnit.SECONDS.toNanos(60))
                    {
                        this.lastClientExitEvidence = now;
                        LOG.info("Client exit instance evidence: {}", ScarletExitInstanceEvidence.describe(result, response));
                    }
                }
                return valid.getAsBoolean() ? result : null;
            }
        }
        catch (ApiException | IOException | RuntimeException ex)
        {
            // Optional enforcement fails closed. Never log credentials or response bodies.
            LOG.debug("Idle instance {} unavailable ({})", close ? "soft close" : "occupancy", ex.getClass().getSimpleName());
            return null;
        }
    }

    static OkHttpClient idleCloseHttpClient(OkHttpClient base, List<okhttp3.Cookie> primaryCookies,
        java.util.function.BooleanSupplier allowed)
    {
        java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        return snapshotHttpClient(base, primaryCookies, allowed).newBuilder()
            .cache(null).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
            // Runs after existing application interceptors, including shared rate-limit waits.
            .addInterceptor(chain -> {
                checkIdleDispatch(chain, allowed);
                return chain.proceed(chain.request().newBuilder().removeHeader("Authorization").removeHeader("Cookie").build());
            })
            // Last check after connection establishment; reject automatic HTTP follow-ups too.
            .addNetworkInterceptor(chain -> {
                checkIdleDispatch(chain, allowed);
                if (!dispatched.compareAndSet(false, true)) throw new IOException("Idle instance request already dispatched");
                return chain.proceed(chain.request());
            }).build();
    }

    private static void checkIdleDispatch(Interceptor.Chain chain, java.util.function.BooleanSupplier allowed) throws IOException
    {
        if (chain.call().isCanceled() || Thread.currentThread().isInterrupted() || !allowed.getAsBoolean())
            throw new IOException("Idle instance request cancelled");
    }

    public Instance closeInstance(String worldId, String instanceId, Boolean hardClose, OffsetDateTime closedAt)
    {
        InstancesApi instances = new InstancesApi(this.client);
        try
        {
            return instances.closeInstance(worldId, instanceId, hardClose, closedAt);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during close instance: "+apiex.getMessage());
            return null;
        }
    }

    public Group getGroup(String groupId)
    {
        return this.getGroup(groupId, null, Long.MAX_VALUE);
    }
    public Group getGroup(String groupId, long minEpoch)
    {
        return this.getGroup(groupId, null, minEpoch);
    }
    public Group getGroup(String groupId, Boolean includeRoles)
    {
        return this.getGroup(groupId, includeRoles, Long.MAX_VALUE);
    }
    public Group getGroup(String groupId, Boolean includeRoles, long minEpoch)
    {
        Group group = this.cachedGroups.get(groupId, minEpoch);
        if (group != null)
            return group;
        if (this.cachedGroups.is404(groupId))
            return null;
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            group = groups.getGroup(groupId, includeRoles, null);
            this.cachedGroups.put(groupId, group);
            return group;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedGroups.add404(groupId);
            else
                LOG.error("Error during get group: "+apiex.getMessage());
            return null;
        }
        catch (RuntimeException rex)
        {
            LOG.warn("VRChat returned an unexpected payload shape for group `{}`; attempting raw response normalization", groupId, rex);
            Group recovered = this.getGroupFromUnexpectedPayload(groups, groupId, includeRoles);
            if (recovered != null)
            {
                this.cachedGroups.put(groupId, recovered);
                return recovered;
            }
            LOG.error("Error during get group: unexpected VRChat response while resolving group `{}`", groupId, rex);
            return null;
        }
    }
    private Group getGroupFromUnexpectedPayload(GroupsApi groups, String groupId, Boolean includeRoles)
    {
        try
        {
            JsonElement element = this.client.<JsonElement>execute(groups.getGroupCall(groupId, includeRoles, null, null), JsonElement.class).getData();
            JsonObject normalized = this.normalizeGroupPayload(groupId, element);
            if (normalized == null)
                return null;
            return JSON.getGson().fromJson(normalized, Group.class);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to recover group `{}` from unexpected VRChat payload", groupId, ex);
            return null;
        }
    }
    private JsonObject normalizeGroupPayload(String groupId, JsonElement element)
    {
        if (element == null || element.isJsonNull())
            return null;
        if (element.isJsonObject())
            return element.getAsJsonObject();
        if (element.isJsonArray())
        {
            JsonArray array = element.getAsJsonArray();
            if (array.size() == 1 && array.get(0).isJsonObject())
            {
                LOG.warn("Recovered group `{}` from a single-element array payload", groupId);
                return array.get(0).getAsJsonObject();
            }
            for (JsonElement child : array)
                if (child != null && child.isJsonObject())
                {
                    JsonObject object = child.getAsJsonObject();
                    JsonPrimitive id = object.getAsJsonPrimitive("id");
                    if (id != null && groupId.equals(id.getAsString()))
                    {
                        LOG.warn("Recovered group `{}` from an array payload containing multiple objects", groupId);
                        return object;
                    }
                }
            LOG.error("Unexpected array payload while resolving group `{}`: {}", groupId, element);
            return null;
        }
        LOG.error("Unexpected non-object payload while resolving group `{}`: {}", groupId, element);
        return null;
    }

    public List<GroupInstance> getGroupInstances(String groupId)
    {
        if (this.cachedGroups.is404(groupId))
            return null;
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupInstances(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedGroups.add404(groupId);
            else
                LOG.error("Error during get group instances: "+apiex.getMessage());
            return null;
        }
    }

    /** Startup follow reads are optional, bounded and isolated from mutable login contexts. */
    private JsonElement startupFollowRead(String expectedGroup, String path, List<io.github.vrchatapi.Pair> query,
        java.util.function.BooleanSupplier allowed) throws IOException
    {
        SnapshotIdentity identity = new SnapshotIdentity(this);
        java.util.function.BooleanSupplier valid = () -> allowed.getAsBoolean() && identity.valid()
            && Objects.equals(expectedGroup, this.groupId) && this.currentUserId != null
            && !Thread.currentThread().isInterrupted()
            && ScarletVRChatCookieJar._context.get() == null && ScarletVRChatCookieJar._context2.get() == null;
        if (expectedGroup == null || !net.sybyline.scarlet.util.VrcIds.id_group.matcher(expectedGroup).matches()
            || this.cookies == null || !valid.getAsBoolean())
            throw new ScarletStartupFailure(ScarletStartupFailure.Kind.CANCELLED);
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            Request request = this.client.buildCall(null, path, "GET", query, new ArrayList<>(), null,
                headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null).request()
                .newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                .tag(SnapshotIdentity.class, identity).build();
            // Reuse the guarded, five-second, no-retry transport; all audit/global pacing remains active.
            OkHttpClient http = idleCloseHttpClient(this.client.getHttpClient(), this.cookies.copy(), valid);
            if (!valid.getAsBoolean()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.CANCELLED);
            try (Response response = http.newCall(request).execute())
            {
                if (!response.isSuccessful()) throw ScarletStartupFailure.http(response.code());
                if (!valid.getAsBoolean()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.CANCELLED);
                if (response.body() == null) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
                response.body().source().request(2L * 1024L * 1024L + 1L);
                if (response.body().source().buffer().size() > 2L * 1024L * 1024L)
                    throw new ScarletStartupFailure(ScarletStartupFailure.Kind.RESPONSE_TOO_LARGE);
                JsonElement result;
                try { result = parseBanResponse(response.body().string()); }
                catch (RuntimeException ex) { throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE); }
                if (!valid.getAsBoolean()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.CANCELLED);
                if (result == null) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
                return result;
            }
        }
        catch (ApiException | RuntimeException ex)
        {
            // Never include cookies, secure join tokens or response bodies in this optional diagnostic.
            throw new ScarletStartupFailure(ScarletStartupFailure.Kind.REQUEST_FAILED);
        }
    }

    List<String> getStartupGroupInstances(String group, java.util.function.BooleanSupplier allowed) throws IOException
    {
        JsonElement body = this.startupFollowRead(group, "/groups/" + group + "/instances", new ArrayList<>(), allowed);
        if (!body.isJsonArray()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
        List<String> locations = new ArrayList<>();
        for (JsonElement item : body.getAsJsonArray())
        {
            if (!item.isJsonObject()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
            JsonElement location = item.getAsJsonObject().get("location");
            if (location == null || !location.isJsonPrimitive() || !location.getAsJsonPrimitive().isString())
                throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
            locations.add(location.getAsString());
        }
        return locations;
    }

    ScarletStartupInstanceJoin.AuditPage getStartupInstanceCreationPage(String group, OffsetDateTime from,
        OffsetDateTime to, int offset, java.util.function.BooleanSupplier allowed) throws IOException
    {
        if (offset < 0 || offset >= 500 || offset % 100 != 0 || from == null || to == null)
            throw new ScarletStartupFailure(ScarletStartupFailure.Kind.REQUEST_FAILED);
        List<io.github.vrchatapi.Pair> query = new ArrayList<>();
        query.addAll(this.client.parameterToPair("n", 100));
        query.addAll(this.client.parameterToPair("offset", offset));
        query.addAll(this.client.parameterToPair("startDate", from));
        query.addAll(this.client.parameterToPair("endDate", to));
        query.addAll(this.client.parameterToPair("eventTypes", "group.instance.create"));
        JsonElement body = this.startupFollowRead(group, "/groups/" + group + "/auditLogs", query, allowed);
        if (!body.isJsonObject()) throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
        JsonObject object = body.getAsJsonObject();
        JsonElement next = object.get("hasNext"), results = object.get("results");
        if (next == null || !next.isJsonPrimitive() || !next.getAsJsonPrimitive().isBoolean()
            || results == null || !results.isJsonArray() || results.getAsJsonArray().size() > 100)
            throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE);
        try
        {
            PaginatedGroupAuditLogEntryList page = JSON.getGson().fromJson(body, PaginatedGroupAuditLogEntryList.class);
            return new ScarletStartupInstanceJoin.AuditPage(ScarletAuditData.entries(page), next.getAsBoolean());
        }
        catch (RuntimeException ex) { throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_RESPONSE); }
    }

    String getStartupInstanceSecureName(String group, String location, java.util.function.BooleanSupplier allowed) throws IOException
    {
        Location parsed;
        try { parsed = ScarletStartupInstanceJoin.parseLocation(location, group); }
        catch (IllegalArgumentException ex) { throw new ScarletStartupFailure(ScarletStartupFailure.Kind.INVALID_LOCATION); }
        JsonElement body = this.startupFollowRead(group, "/instances/" + parsed.world + ":" + parsed.instance + "/shortName", new ArrayList<>(), allowed);
        if (!body.isJsonObject()) return null;
        for (String field : new String[]{"secureName", "shortName"})
        {
            JsonElement name = body.getAsJsonObject().get(field);
            if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()
                && !name.getAsString().trim().isEmpty()) return name.getAsString();
        }
        return null;
    }

    public List<LimitedUserGroups> getUserGroups(String userId)
    {
        return this.getUserGroups(userId, Long.MAX_VALUE);
    }
    /**
     * Fetches the user's public group memberships straight from VRChat, bypassing the
     * cache, and refreshes the cache with the result. Used to confirm a watched-group
     * match against live membership so a user who has since left the group isn't
     * repeatedly flagged (their cached membership would otherwise never expire).
     * Returns null on error, leaving the existing cache untouched.
     */
    public List<LimitedUserGroups> getUserGroupsFresh(String userId)
    {
        UsersApi users = new UsersApi(this.client);
        try
        {
            List<LimitedUserGroups> userGroups = users.getUserGroups(userId);
            this.cachedUserGroups.put(userId, userGroups);
            return userGroups;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUserGroups.add404(userId);
            else
                LOG.error("Error during fresh get user groups: "+apiex.getMessage());
            return null;
        }
    }
    public List<LimitedUserGroups> getUserGroups(String userId, long minEpoch)
    {
        List<LimitedUserGroups> userGroups = this.cachedUserGroups.get(userId, minEpoch);
        if (userGroups != null)
            return userGroups;
        if (this.cachedUserGroups.is404(userId))
            return null;
        UsersApi users = new UsersApi(this.client);
        try
        {
            userGroups = users.getUserGroups(userId);
            this.cachedUserGroups.put(userId, userGroups);
            return userGroups;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUserGroups.add404(userId);
            else
                LOG.error("Error during get user groups: "+apiex.getMessage());
            return null;
        }
    }

    static final Type _LimitedUserGroups_Array = new TypeToken<List<LimitedUserGroups>>(){}.getType();
    public List<LimitedUserGroups> getMutualsGroups(String userId)
    {
        if (this.cachedUserGroups.is404(userId))
            return null;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            return this.client.<List<LimitedUserGroups>>execute(this.client.buildCall(null, "/users/"+userId+"/mutuals/groups", "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null), _LimitedUserGroups_Array).getData();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUserGroups.add404(userId);
            else
                LOG.error("Error during get user mutual groups: "+apiex.getMessage());
            return null;
        }
    }

    public GroupMemberStatus getGroupMembershipStatus(String groupId, String targetUserId)
    {
        GroupMember member = this.getGroupMembership(groupId, targetUserId);
        return member == null ? null : member.getMembershipStatus();
    }

    /** A suspended timer must not query a newly selected group's membership. */
    GroupMemberStatus getTimedBanMembershipStatus(String expectedGroupId, String targetUserId)
    {
        if (expectedGroupId == null || !expectedGroupId.equals(this.groupId)) return null;
        return this.getGroupMembershipStatus(expectedGroupId, targetUserId);
    }
    public GroupMember getGroupMembership(String groupId, String targetUserId)
    {
        // Validate required parameters
        if (groupId == null || groupId.isEmpty())
        {
            LOG.error("getGroupMembership called with null or empty groupId");
            return null;
        }
        if (targetUserId == null || targetUserId.isEmpty())
        {
            LOG.error("getGroupMembership called with null or empty targetUserId");
            return null;
        }
        
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupMember(groupId, targetUserId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            // VRChat's GET /groups/{groupId}/members/{userId} signals a *non-member* with
            // HTTP 403 "You're not a member" (NOT 404). A 404 means "Can't find group!".
            // Treat both as "no membership record" so callers see a non-member (null)
            // instead of a thrown error. Genuine permission/auth 403s (no "not a member"
            // body) and all other codes still propagate.
            int code = apiex.getCode();
            String msg = String.valueOf(apiex.getMessage());
            if (code == 403 && msg.contains("not a member"))
            {
                LOG.debug("User {} is not a member of group {} (403 not-a-member)", targetUserId, groupId);
                return null;
            }
            if (code == 404)
            {
                LOG.warn("Group {} not found while checking membership for {} (404 can't-find-group)", groupId, targetUserId);
                return null;
            }
            LOG.error("Error getting group member: "+apiex.getMessage());
            throw new RuntimeException("Failed to get group membership: " + apiex.getMessage(), apiex);
        }
    }
    public GroupMember updateGroupMembershipNotes(String groupId, String targetUserId, String managerNotes)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.updateGroupMember(groupId, targetUserId, new UpdateGroupMemberRequest().managerNotes(managerNotes));
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<String> addGroupRole(String groupId, String targetUserId, String groupRoleId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.addGroupMemberRole(groupId, targetUserId, groupRoleId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<String> removeGroupRole(String groupId, String targetUserId, String groupRoleId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.removeGroupMemberRole(groupId, targetUserId, groupRoleId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<GroupRole> getGroupRoles(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupRoles(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group roles: "+apiex.getMessage());
            return null;
        }
    }

    public List<String> getGroupAuditLogEntryTypes(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupAuditLogEntryTypes(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group audit log entry types: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupMember> searchGroupMembers(String groupId, String query, int limit)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupMember> members = new ArrayList<>();
            int offset = 0, batchSize = Math.max(1, Math.min(100, limit));
            while (members.size() < limit)
            {
                int n = Math.min(batchSize, limit - members.size());
                GroupMemberSearchResponse response = groups.searchGroupMembers(query, groupId, n, offset);
                List<GroupMember> page = response == null ? null : response.getResults();
                if (page == null || page.isEmpty())
                    break;
                members.addAll(page);
                offset += page.size();
                Integer total = response.getTotal();
                if (page.size() < n || (total != null && members.size() >= total.intValue()))
                    break;
                MiscUtils.sleep(250L);
            }
            return members;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error searching group members: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupMember> getGroupBans(String groupId, int limit)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupMember> bans = new ArrayList<>();
            int offset = 0, batchSize = Math.max(1, Math.min(100, limit));
            while (bans.size() < limit)
            {
                int n = Math.min(batchSize, limit - bans.size());
                List<GroupMember> page = groups.getGroupBans(groupId, n, offset);
                if (page == null || page.isEmpty())
                    break;
                bans.addAll(page);
                offset += page.size();
                if (page.size() < n)
                    break;
                MiscUtils.sleep(250L);
            }
            return bans;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group bans: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupMember> getGroupInvites(String groupId, int limit)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupMember> invites = new ArrayList<>();
            int offset = 0, batchSize = Math.max(1, Math.min(100, limit));
            while (invites.size() < limit)
            {
                int n = Math.min(batchSize, limit - invites.size());
                List<GroupMember> page = groups.getGroupInvites(groupId, n, offset);
                if (page == null || page.isEmpty())
                    break;
                invites.addAll(page);
                offset += page.size();
                if (page.size() < n)
                    break;
                MiscUtils.sleep(250L);
            }
            return invites;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group invites: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupMember> getGroupJoinRequests(String groupId, boolean blocked, int limit)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupMember> requests = new ArrayList<>();
            int offset = 0, batchSize = Math.max(1, Math.min(100, limit));
            while (requests.size() < limit)
            {
                int n = Math.min(batchSize, limit - requests.size());
                List<GroupMember> page = groups.getGroupRequests(groupId, n, offset, Boolean.valueOf(blocked));
                if (page == null || page.isEmpty())
                    break;
                requests.addAll(page);
                offset += page.size();
                if (page.size() < n)
                    break;
                MiscUtils.sleep(250L);
            }
            return requests;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group join requests: "+apiex.getMessage());
            return null;
        }
    }

    public GroupAnnouncement getGroupAnnouncement(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupAnnouncements(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getCode() != 404)
                LOG.error("Error getting group announcement: "+apiex.getMessage());
            return null;
        }
    }

    public GroupAnnouncement createGroupAnnouncement(String groupId, CreateGroupAnnouncementRequest request)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.createGroupAnnouncement(groupId, request);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error creating group announcement: "+apiex.getMessage());
            return null;
        }
    }

    public boolean deleteGroupAnnouncement(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.deleteGroupAnnouncement(groupId);
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error deleting group announcement: "+apiex.getMessage());
            return false;
        }
    }

    public List<GroupPost> getGroupPosts(String groupId, boolean publicOnly, int limit)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupPost> posts = new ArrayList<>();
            int offset = 0, batchSize = Math.max(1, Math.min(100, limit));
            while (posts.size() < limit)
            {
                int n = Math.min(batchSize, limit - posts.size());
                GroupPostsResponse response = groups.getGroupPosts(groupId, n, offset, Boolean.valueOf(publicOnly));
                List<GroupPost> page = response == null ? null : response.getPosts();
                if (page == null || page.isEmpty())
                    break;
                posts.addAll(page);
                offset += page.size();
                if (page.size() < n)
                    break;
                MiscUtils.sleep(250L);
            }
            return posts;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group posts: "+apiex.getMessage());
            return null;
        }
    }

    public GroupPost createGroupPost(String groupId, CreateGroupPostRequest request)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.addGroupPost(groupId, request);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error creating group post: "+apiex.getMessage());
            return null;
        }
    }

    public boolean deleteGroupPost(String groupId, String postId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.deleteGroupPost(groupId, postId);
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error deleting group post: "+apiex.getMessage());
            return false;
        }
    }

    public GroupTransferable getGroupTransferability(String groupId, String targetUserId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupTransferability(groupId, targetUserId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group transferability: "+apiex.getMessage());
            return null;
        }
    }

    public boolean initiateOrAcceptGroupTransfer(String groupId, String targetUserId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            Success success = groups.initiateOrAcceptGroupTransfer(groupId, new TransferGroupRequest().transferTargetId(targetUserId));
            return success != null;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error initiating or accepting group transfer: "+apiex.getMessage());
            return false;
        }
    }

    public boolean cancelGroupTransfer(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            Success success = groups.cancelGroupTransfer(groupId);
            return success != null;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error cancelling group transfer: "+apiex.getMessage());
            return false;
        }
    }

    public List<GroupGalleryImage> getGroupGalleryImages(String groupId, String galleryId, Boolean approved)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupGalleryImage> images = new ArrayList<>();
            int offset = 0, batchSize = 100;
            // With v omitted the endpoint returns an array. SDK 1.22 exposes Object,
            // which would otherwise produce maps instead of GroupGalleryImage models.
            Type imageListType = new TypeToken<List<GroupGalleryImage>>(){}.getType();
            List<GroupGalleryImage> ggil = this.client.<List<GroupGalleryImage>>execute(
                groups.getGroupGalleryImagesCall(groupId, galleryId, batchSize, offset, null, approved, null),
                imageListType).getData();
            while (ggil != null && !ggil.isEmpty())
            {
                images.addAll(ggil);
                offset += ggil.size();
                MiscUtils.sleep(250L);
                ggil = this.client.<List<GroupGalleryImage>>execute(
                    groups.getGroupGalleryImagesCall(groupId, galleryId, batchSize, offset, null, approved, null),
                    imageListType).getData();
            }
            return images;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group gallery images: "+apiex.getMessage());
            return null;
        }
    }

    public boolean banFromGroup(String targetUserId)
    {
        return this.banFromGroupDetailed(targetUserId) == GroupModerationResult.SUCCEEDED;
    }

    public enum GroupModerationResult
    {
        SUCCEEDED, REJECTED, UNKNOWN
    }

    static GroupModerationResult groupModerationFailure(int status)
    {
        switch (status)
        {
        case 400: case 401: case 403: case 404: case 405: case 409: case 422: case 429:
            return GroupModerationResult.REJECTED;
        default:
            // A timeout, transport failure or server error does not establish that
            // the server did not apply the request before its response was lost.
            return GroupModerationResult.UNKNOWN;
        }
    }

    public GroupModerationResult banFromGroupDetailed(String targetUserId)
    {
        // Desktop-origin operations act as the locally authenticated account, not a Discord requester.
        GroupBanResult result = this.banFromGroupAs(this.currentUserId, targetUserId, false);
        return result.authorization.allowed() ? result.outcome : GroupModerationResult.REJECTED;
    }

    public static final class GroupBanResult
    {
        public final ScarletBanAuthorization.Decision authorization;
        public final GroupModerationResult outcome;
        public final OffsetDateTime serverBanAt;
        public final String serverActorId;

        GroupBanResult(ScarletBanAuthorization.Decision authorization, GroupModerationResult outcome)
        {
            this(authorization, outcome, null, null);
        }

        GroupBanResult(ScarletBanAuthorization.Decision authorization, GroupModerationResult outcome,
            OffsetDateTime serverBanAt, String serverActorId)
        {
            this.authorization = authorization;
            this.outcome = outcome;
            this.serverBanAt = serverBanAt;
            this.serverActorId = serverActorId;
        }
    }

    static final class GroupBanSubmission
    {
        final GroupModerationResult outcome;
        final OffsetDateTime bannedAt;
        GroupBanSubmission(GroupModerationResult outcome, OffsetDateTime bannedAt)
        {
            this.outcome = outcome;
            this.bannedAt = bannedAt;
        }
    }

    public ScarletBanAuthorization.Decision checkBanAuthorization(String actorId, String targetId, boolean allowPermissionOverride)
    {
        return this.checkBanAuthorization(this.groupId, this.currentUserId, actorId, targetId, allowPermissionOverride);
    }

    private ScarletBanAuthorization.Decision checkBanAuthorization(String expectedGroupId, String expectedBotId,
            String actorId, String targetId, boolean allowPermissionOverride)
    {
        if (expectedGroupId == null || expectedBotId == null || actorId == null || targetId == null)
            return ScarletBanAuthorization.Decision.unverified();
        try
        {
            // Never use cached models or the null-on-403/404 convenience membership wrapper here.
            // Strict raw parsing prevents generated defaults from making malformed responses look roleless.
            Group group = ScarletBanSnapshot.group(this.readBanGroup(expectedGroupId), expectedGroupId);
            GroupMember actor = ScarletBanSnapshot.member(this.readBanMember(expectedGroupId, actorId), expectedGroupId, actorId);
            GroupMember bot = actorId.equals(expectedBotId) ? actor
                : ScarletBanSnapshot.member(this.readBanMember(expectedGroupId, expectedBotId), expectedGroupId, expectedBotId);
            JsonElement targetJson = this.readBanMember(expectedGroupId, targetId);
            GroupMember target = ScarletBanSnapshot.member(targetJson, expectedGroupId, targetId);
            if (!Objects.equals(expectedGroupId, this.groupId) || !Objects.equals(expectedBotId, this.currentUserId))
                return ScarletBanAuthorization.Decision.unverified();
            return ScarletBanAuthorization.evaluate(group, actorId, targetId, expectedBotId, actor, target, bot,
                allowPermissionOverride, targetJson != null && targetJson.isJsonNull());
        }
        catch (ApiException ex)
        {
            LOG.warn("VRChat ban authorization could not be verified (HTTP {}); no ban sent", ex.getCode());
            return ScarletBanAuthorization.Decision.unverified();
        }
        catch (RuntimeException ex)
        {
            LOG.warn("VRChat ban authorization received unverifiable state; no ban sent");
            return ScarletBanAuthorization.Decision.unverified();
        }
    }

    JsonElement readBanGroup(String selectedGroupId) throws ApiException
    {
        return readBanResponse(new GroupsApi(this.client).getGroupCall(selectedGroupId, true, null, null));
    }

    JsonElement readBanMember(String selectedGroupId, String userId) throws ApiException
    {
        return readBanResponse(new GroupsApi(this.client).getGroupMemberCall(selectedGroupId, userId, null));
    }

    static JsonElement readBanResponse(okhttp3.Call call) throws ApiException
    {
        // Bypass the compatibility model adapter: it collapses explicit JSON null into Java null.
        // Only a successful, explicit JSON null can establish that a target is not a member.
        try (Response response = call.execute())
        {
            if (!response.isSuccessful()) throw new ApiException(response.code(), "Ban authorization lookup failed");
            if (response.body() == null) throw new IllegalArgumentException("Missing authorization response");
            response.body().source().request(2L * 1024L * 1024L + 1L);
            if (response.body().source().buffer().size() > 2L * 1024L * 1024L)
                throw new IllegalArgumentException("Oversized authorization response");
            return parseBanResponse(response.body().string());
        }
        catch (IOException ex)
        {
            throw new ApiException(ex);
        }
    }

    static JsonElement parseBanResponse(String body)
    {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("Missing authorization response");
        try (JsonReader reader = new JsonReader(new java.io.StringReader(body)))
        {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing authorization data");
            return value;
        }
        catch (IOException ex)
        {
            throw new IllegalArgumentException("Malformed authorization response", ex);
        }
    }

    public GroupBanResult banFromGroupAs(String actorId, String targetId, boolean allowPermissionOverride)
    {
        return this.banFromGroupAs(this.groupId, actorId, targetId, allowPermissionOverride);
    }

    /** Bind a persisted timed-ban intent to the same group throughout authorization and submission. */
    public GroupBanResult banFromGroupAs(String selectedGroupId, String actorId, String targetId, boolean allowPermissionOverride)
    {
        if (selectedGroupId == null || !selectedGroupId.equals(this.groupId))
            return new GroupBanResult(ScarletBanAuthorization.Decision.unverified(), null);
        String selectedBotId = this.currentUserId;
        ScarletBanAuthorization.Decision decision = this.checkBanAuthorization(selectedGroupId, selectedBotId,
            actorId, targetId, allowPermissionOverride);
        if (!decision.allowed()) return new GroupBanResult(decision, null);
        // This fresh target-specific check is intentionally repeated after any UI/queue precheck.
        GroupBanSubmission submitted = this.submitGroupBanWithIdentity(selectedGroupId, targetId);
        return new GroupBanResult(decision, submitted.outcome, submitted.bannedAt, selectedBotId);
    }

    GroupModerationResult submitGroupBan(String selectedGroupId, String targetUserId)
    {
        return this.submitGroupBanWithIdentity(selectedGroupId, targetUserId).outcome;
    }

    GroupBanSubmission submitGroupBanWithIdentity(String selectedGroupId, String targetUserId)
    {
        if (selectedGroupId == null || !selectedGroupId.equals(this.groupId))
            return new GroupBanSubmission(GroupModerationResult.REJECTED, null);
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            GroupMember member = groups.banGroupMember(selectedGroupId, new BanGroupMemberRequest().userId(targetUserId));
            // Only this response establishes the ban created by this request. A later GET
            // could already describe a replacement ban imposed by another moderator.
            OffsetDateTime bannedAt = member != null && selectedGroupId.equals(member.getGroupId())
                && targetUserId.equals(member.getUserId()) && member.getMembershipStatus() == GroupMemberStatus.BANNED
                    ? member.getBannedAt() : null;
            return new GroupBanSubmission(GroupModerationResult.SUCCEEDED, bannedAt);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during ban from group: "+apiex.getMessage());
            return new GroupBanSubmission(groupModerationFailure(apiex.getCode()), null);
        }
        catch (RuntimeException ex)
        {
            LOG.warn("VRChat group ban outcome could not be confirmed; preserve the pending action for reconciliation");
            return new GroupBanSubmission(GroupModerationResult.UNKNOWN, null);
        }
    }

    public boolean unbanFromGroup(String targetUserId)
    {
        return this.unbanFromGroupDetailed(targetUserId) == GroupModerationResult.SUCCEEDED;
    }

    public GroupModerationResult unbanFromGroupDetailed(String targetUserId)
    {
        return this.unbanFromGroupDetailed(this.groupId, targetUserId);
    }

    public GroupModerationResult unbanFromGroupDetailed(String expectedGroupId, String targetUserId)
    {
        if (expectedGroupId == null || !expectedGroupId.equals(this.groupId))
            return GroupModerationResult.REJECTED;
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.unbanGroupMember(expectedGroupId, targetUserId);
            return GroupModerationResult.SUCCEEDED;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during unban from group: "+apiex.getMessage());
            return groupModerationFailure(apiex.getCode());
        }
    }

    public boolean inviteToGroup(String targetUserId, Boolean confirmOverrideBlock)
    {
        return this.inviteToGroup(this.groupId, targetUserId, confirmOverrideBlock);
    }

    public boolean inviteToGroup(String groupId, String targetUserId, Boolean confirmOverrideBlock)
    {
        return this.inviteToGroupDetailed(groupId, targetUserId, confirmOverrideBlock).sent;
    }

    public GroupInviteResult inviteToGroupDetailed(String groupId, String targetUserId, Boolean confirmOverrideBlock)
    {
        if (groupId == null || groupId.isEmpty())
            return GroupInviteResult.notConfigured(groupId, targetUserId);
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.createGroupInvite(groupId, new CreateGroupInviteRequest().userId(targetUserId).confirmOverrideBlock(confirmOverrideBlock));
            return GroupInviteResult.sent(groupId, targetUserId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            GroupInviteResult result = GroupInviteResult.failed(groupId, targetUserId, apiex);
            if (result.expectedFailure)
                LOG.warn("Group invite to {} for {} was not sent: {}", groupId, targetUserId, result.logSummary);
            else
                LOG.error("Error during invite to group: "+apiex.getMessage());
            return result;
        }
    }

    public static final class GroupInviteResult
    {
        public static enum Status
        {
            SENT,
            INVITER_NOT_MEMBER,
            GROUP_NOT_FOUND,
            TARGET_ALREADY_IN_GROUP_OR_INVITED,
            FORBIDDEN,
            FAILED
        }

        static GroupInviteResult sent(String groupId, String targetUserId)
        {
            return new GroupInviteResult(true, Status.SENT, groupId, targetUserId, 0, null, false, "sent");
        }

        static GroupInviteResult notConfigured(String groupId, String targetUserId)
        {
            return new GroupInviteResult(false, Status.GROUP_NOT_FOUND, groupId, targetUserId, 0, null, true, "no group configured");
        }

        static GroupInviteResult failed(String groupId, String targetUserId, ApiException apiex)
        {
            int code = apiex.getCode();
            String body = apiex.getResponseBody();
            String haystack = (String.valueOf(apiex.getMessage()) + "\n" + String.valueOf(body)).toLowerCase(java.util.Locale.ROOT);
            Status status = Status.FAILED;
            boolean expected = false;
            String summary = summarizeApiException(apiex);

            if (code == 403 && haystack.contains("not a member"))
            {
                status = Status.INVITER_NOT_MEMBER;
                expected = true;
                summary = "Scarlet's logged-in VRChat account is not a member of the configured auto-invite group";
            }
            else if (code == 404)
            {
                status = Status.GROUP_NOT_FOUND;
                expected = true;
                summary = "configured auto-invite group was not found";
            }
            else if ((code == 400 || code == 403 || code == 409)
                  && (haystack.contains("already") || haystack.contains("invite") || haystack.contains("member")))
            {
                status = Status.TARGET_ALREADY_IN_GROUP_OR_INVITED;
                expected = true;
                summary = "target may already be in the group or have a pending invite";
            }
            else if (code == 401 || code == 403)
            {
                status = Status.FORBIDDEN;
                expected = true;
                summary = "VRChat rejected the invite; Scarlet's VRChat account may lack invite permission";
            }

            return new GroupInviteResult(false, status, groupId, targetUserId, code, body, expected, summary);
        }

        private GroupInviteResult(boolean sent, Status status, String groupId, String targetUserId, int httpCode, String responseBody, boolean expectedFailure, String logSummary)
        {
            this.sent = sent;
            this.status = status;
            this.groupId = groupId;
            this.targetUserId = targetUserId;
            this.httpCode = httpCode;
            this.responseBody = responseBody;
            this.expectedFailure = expectedFailure;
            this.logSummary = logSummary;
        }

        public final boolean sent;
        public final Status status;
        public final String groupId;
        public final String targetUserId;
        public final int httpCode;
        public final String responseBody;
        public final boolean expectedFailure;
        public final String logSummary;
    }

    public boolean respondToGroupJoinRequest(String targetUserId, GroupJoinRequestAction action, Boolean block)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.respondGroupJoinRequest(this.groupId, targetUserId, new RespondGroupJoinRequest().action(action).block(block));
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during response to group join request: "+apiex.getMessage());
            return false;
        }
    }

    public boolean checkSelfUserHasVRChatPermission(GroupPermissions vrchatPermission)
    {
        if (vrchatPermission == null || this.groupId == null || this.groupId.isEmpty())
            return false;

        if (this.allGroupPermissions.has(this.groupId, vrchatPermission))
            return true;

        if (this.currentUserId != null && this.currentUserId.equals(this.groupOwnerId))
        {
            LOG.debug("VRChat permission `{}` granted by group owner fallback for `{}` in group `{}`", vrchatPermission.getValue(), this.currentUserId, this.groupId);
            return true;
        }

        GroupMember selfMembership = this.groupLimitedMember;
        if (selfMembership == null && this.currentUserId != null)
        {
            selfMembership = this.getGroupMembership(this.groupId, this.currentUserId);
            if (selfMembership != null)
                this.groupLimitedMember = selfMembership;
        }
        if (selfMembership != null && this.checkUserHasVRChatPermission(selfMembership, vrchatPermission))
        {
            LOG.debug("VRChat permission `{}` granted by local role fallback for `{}` in group `{}`", vrchatPermission.getValue(), this.currentUserId, this.groupId);
            return true;
        }

        if (!this.allGroupPermissions.hasGroup(this.groupId))
            LOG.warn("VRChat permission cache did not contain group `{}` for `{}`; local role fallback also did not confirm `{}`", this.groupId, this.currentUserId, vrchatPermission.getValue());

        return false;
    }
    public boolean checkUserHasVRChatPermission(GroupPermissions vrchatPermission, String userId)
    {
        if (userId == null)
            return false;
        GroupMember glm = this.getGroupMembership(this.groupId, userId);
        return this.checkUserHasVRChatPermission(glm, vrchatPermission);
    }
    public boolean checkUserHasVRChatPermission(GroupMember glm, GroupPermissions vrchatPermission)
    {
        if (glm == null)
            return false;
        if (this.group == null)
            return false;
        List<GroupRole> grl = this.group.getRoles();
        return grl != null && grl.stream().filter(Objects::nonNull).filter($ -> glm.getRoleIds().contains($.getId())).map(GroupRole::getPermissions).filter(Objects::nonNull).anyMatch($ -> $.contains(GroupPermissions.group_all) || $.contains(vrchatPermission));
    }

    public Avatar getAvatar(String avatarId)
    {
        return this.getAvatar(avatarId, Long.MAX_VALUE);
    }
    public Avatar getAvatar(String avatarId, long minEpoch)
    {
        Avatar avatar = this.cachedAvatars.get(avatarId, minEpoch);
        if (avatar != null)
            return avatar;
        if (this.cachedAvatars.is404(avatarId))
            return null;
        AvatarsApi avatars = new AvatarsApi(this.client);
        try
        {
            avatar = avatars.getAvatar(avatarId);
            this.cachedAvatars.put(avatarId, avatar);
            return avatar;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedAvatars.add404(avatarId);
            else
                LOG.error("Error during get avatar: ", apiex);//LOG.error("Error during get avatar: "+apiex.getMessage());
            return null;
        }
    }

    public Print getPrint(String printId)
    {
        return this.getPrint(printId, Long.MAX_VALUE);
    }
    public Print getPrint(String printId, long minEpoch)
    {
        Print print = this.cachedPrints.get(printId, minEpoch);
        if (print != null)
            return print;
        if (this.cachedPrints.is404(printId))
            return null;
        PrintsApi prints = new PrintsApi(this.client);
        try
        {
            print = prints.getPrint(printId);
            this.cachedPrints.put(printId, print);
            return print;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedPrints.add404(printId);
            else
                LOG.error("Error during get print: "+apiex.getMessage());
            return null;
        }
    }

    public Prop getProp(String propId)
    {
        return this.getProp(propId, Long.MAX_VALUE);
    }
    public Prop getProp(String propId, long minEpoch)
    {
        Prop prop = this.cachedProps.get(propId, minEpoch);
        if (prop != null)
            return prop;
        if (this.cachedProps.is404(propId))
            return null;
        PropsApi props = new PropsApi(this.client);
        try
        {
            prop = props.getProp(propId);
            this.cachedProps.put(propId, prop);
            return prop;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedProps.add404(propId);
            else
                LOG.error("Error during get prop: "+apiex.getMessage());
            return null;
        }
    }

    public InventoryItem getInventoryItem(String userId, String invId)
    {
        return this.getInventoryItem(userId, invId, Long.MAX_VALUE);
    }
    public InventoryItem getInventoryItem(String userId, String invId, long minEpoch)
    {
        InventoryItem item = this.cachedInventoryItems.get(invId, minEpoch);
        if (item != null)
            return item;
        if (this.cachedInventoryItems.is404(invId))
            return null;
//        InventoryApi inventory = new InventoryApi(this.client);
        try
        {
//            item = inventory.getInventoryItem(userId, invId);
            item = this.getInventoryItemEx(userId, invId);
            this.cachedInventoryItems.put(invId, item);
            return item;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedInventoryItems.add404(invId);
            else
                LOG.error("Error during get inventory item: "+apiex.getMessage());
            return null;
        }
    }
    public InventoryItem getInventoryItemEx(String userId, String invId) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/user/"+userId+"/inventory/"+invId, "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<InventoryItem> localVarResp = this.client.execute(localVarCall, InventoryItem.class);
        return localVarResp.getData();
    }

    public ModelFile getModelFile(String fileId)
    {
        return this.getModelFile(fileId, Long.MAX_VALUE);
    }
    public ModelFile getModelFile(String fileId, long minEpoch)
    {
        ModelFile file = this.cachedModelFiles.get(fileId, minEpoch);
        if (file != null)
            return file;
        if (this.cachedModelFiles.is404(fileId))
            return null;
        FilesApi files = new FilesApi(this.client);
        try
        {
            file = files.getFile(fileId);
            this.cachedModelFiles.put(fileId, file);
            return file;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedModelFiles.add404(fileId);
            else
                LOG.error("Error during get file: "+apiex.getMessage());
            return null;
        }
    }

    public FileAnalysis getFileAnalysis(VersionedFile versionedFile)
    {
        return this.getFileAnalysis(versionedFile, Long.MAX_VALUE);
    }
    public FileAnalysis getFileAnalysis(VersionedFile versionedFile, long minEpoch)
    {
        String stringified = versionedFile.toString('#'); // avoid ':' and '/' for file path semantic reasons
        FileAnalysis analysis = this.cachedFileAnalyses.get(stringified, minEpoch);
        if (analysis != null)
            return analysis;
        if (this.cachedFileAnalyses.is404(stringified))
        {
            LOG.warn("file 404: "+versionedFile);
            return null;
        }
        FilesApi files = new FilesApi(this.client);
        try
        {
            if ("security".equals(versionedFile.qualifier))
                analysis = files.getFileAnalysisSecurity(versionedFile.id, versionedFile.version);
            else if ("standard".equals(versionedFile.qualifier))
                analysis = files.getFileAnalysisStandard(versionedFile.id, versionedFile.version);
            else
                analysis = files.getFileAnalysis(versionedFile.id, versionedFile.version);
            this.cachedFileAnalyses.put(stringified, analysis);
            return analysis;
        }
        catch (ApiException apiex)
        {
            LOG.warn("file exception: "+versionedFile, apiex);
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedFileAnalyses.add404(stringified);
            else
                LOG.error("Error during get file analysis: "+apiex.getMessage());
            return null;
        }
    }

    public Instance createInstanceEx(JsonObject createInstanceRequest) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/instances", "POST", new ArrayList<>(), new ArrayList<>(), createInstanceRequest, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<Instance> localVarResp = this.client.execute(localVarCall, Instance.class);
        return localVarResp.getData();
    }

    public Instance createInstance(CreateInstanceRequest createInstanceRequest) throws ApiException
    {
        return new InstancesApi(this.client).createInstance(createInstanceRequest);
    }

    public CalendarEvent createCalendarEvent(String groupId, CreateCalendarEventRequest createCalendarEventRequest) throws ApiException
    {
        return new CalendarApi(this.client).createGroupCalendarEvent(groupId, createCalendarEventRequest);
    }

    public String getStickerFileId(String userId, String stickerId) throws ApiException
    {
        if (stickerId.startsWith("file_"))
            return stickerId;
        if (!stickerId.startsWith("inv_"))
            return null;
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/user/"+userId+"/inventory/"+stickerId, "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<JsonObject> localVarResp = this.client.execute(localVarCall, JsonObject.class);
        return VersionedFile.parse(localVarResp.getData().getAsJsonPrimitive("imageUrl").getAsString()).id;
    }

    static final TypeToken<List<LimitedUserGroups>> LIST_LUGROUPS = new TypeToken<List<LimitedUserGroups>>(){};
    public List<LimitedUserGroups> snapshot(ScarletData.AuditEntryMetadata entryMeta)
    {
        if (entryMeta == null) return null;
        final String targetId, auditId;
        final GroupAuditLogEntryUnknown entry;
        synchronized (entryMeta)
        {
            // Clear old captures even when this attempt is redacted, cancelled or rejected.
            entryMeta.snapshotTargetUser = null;
            entryMeta.snapshotTargetUserGroups = null;
            entryMeta.snapshotTargetUserRepresentedGroup = null;
            entry = entryMeta.entry;
            if (entry == null || entryMeta.entryRedacted) return null;
            targetId = entry.getTargetId();
            auditId = entry.getId();
        }
        if (targetId == null || !net.sybyline.scarlet.util.VrcIds.id_user.matcher(targetId).matches()
            || ScarletVRChatCookieJar._context.get() != null || ScarletVRChatCookieJar._context2.get() != null
            || this.cookies == null || this.currentUserId == null) return null;

        // Snapshots run only in the normal primary account context. Do not copy an
        // alternate/temp login context or a live mutable credential store to workers.
        SnapshotIdentity identity = new SnapshotIdentity(this);
        List<okhttp3.Cookie> primaryCookies = this.cookies.copy();
        ScarletSnapshotFetch fetcher;
        synchronized (SNAPSHOT_LIFECYCLE_LOCK)
        {
            if (this.snapshotsStopped) return null;
            if (this.snapshotFetch == null) this.snapshotFetch = new ScarletSnapshotFetch();
            fetcher = this.snapshotFetch;
        }
        java.util.function.BooleanSupplier allowed = () -> identity.valid()
            && !Thread.currentThread().isInterrupted();
        ScarletSnapshotFetch.Read[] reads = new ScarletSnapshotFetch.Read[3];
        try
        {
            OkHttpClient http = snapshotHttpClient(this.client.getHttpClient(), primaryCookies, identity::valid);
            String[] suffixes = {"", "/groups", "/groups/represented"};
            for (int i = 0; i < reads.length; i++)
            {
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept", "application/json");
                Request request = this.client.buildCall(null, "/users/" + targetId + suffixes[i], "GET",
                    new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(),
                    new String[]{"authCookie"}, null).request().newBuilder()
                    .removeHeader("Authorization").removeHeader("Cookie").tag(SnapshotIdentity.class, identity).build();
                final okhttp3.Call call = http.newCall(request);
                reads[i] = new ScarletSnapshotFetch.Read() {
                    public JsonElement read() throws Exception {
                        if (!identity.valid() || call.isCanceled()) return null;
                        return ScarletVRChat.this.client.<JsonElement>execute(call, JsonElement.class).getData();
                    }
                    public void cancel() { call.cancel(); }
                };
            }
            ScarletSnapshotFetch.Result result = fetcher.fetch(targetId, allowed, reads);
            if (result == null) return null;
            synchronized (entryMeta)
            {
                // One caller-owned commit after all workers end. Late responses cannot
                // restore fields after an account/group change, replacement or redaction.
                if (!identity.valid() || Thread.currentThread().isInterrupted() || entryMeta.entryRedacted
                    || entryMeta.entry != entry || !Objects.equals(auditId, entry.getId())
                    || !Objects.equals(targetId, entry.getTargetId())) return null;
                List<LimitedUserGroups> groups = result.groups == null ? null : JSON.getGson().fromJson(result.groups, LIST_LUGROUPS);
                entryMeta.snapshotTargetUser = result.user;
                entryMeta.snapshotTargetUserGroups = result.groups;
                entryMeta.snapshotTargetUserRepresentedGroup = result.representedGroup;
                return groups;
            }
        }
        catch (Exception ex)
        {
            // Do not log response bodies or credentials from optional lookup failures.
            LOG.debug("Public moderation snapshot unavailable ({})", ex.getClass().getSimpleName());
            return null;
        }
        finally
        {
            for (ScarletSnapshotFetch.Read read : reads) if (read != null) read.cancel();
        }
    }

    static OkHttpClient snapshotHttpClient(OkHttpClient base, List<okhttp3.Cookie> primaryCookies,
        java.util.function.BooleanSupplier allowed)
    {
        List<okhttp3.Cookie> frozen = new ArrayList<>(primaryCookies);
        return base.newBuilder()
            .connectTimeout(5L, TimeUnit.SECONDS).readTimeout(5L, TimeUnit.SECONDS)
            .writeTimeout(5L, TimeUnit.SECONDS).callTimeout(5L, TimeUnit.SECONDS)
            .cookieJar(new okhttp3.CookieJar() {
                public List<okhttp3.Cookie> loadForRequest(okhttp3.HttpUrl url) {
                    if (!allowed.getAsBoolean()) return Collections.emptyList();
                    long now = System.currentTimeMillis();
                    return frozen.stream().filter(cookie -> cookie.expiresAt() > now && cookie.matches(url))
                        .collect(Collectors.toList());
                }
                public void saveFromResponse(okhttp3.HttpUrl url, List<okhttp3.Cookie> cookies) {
                    // Read-only enrichment cannot change the current login session.
                }
            }).build();
    }

    private static final class SnapshotIdentity
    {
        final ScarletVRChat owner;
        final String userId, groupId;
        final long generation;
        SnapshotIdentity(ScarletVRChat owner)
        {
            this.owner = owner;
            this.generation = ExtendedUserAgent.currentAuthenticationGeneration();
            this.userId = owner.currentUserId;
            this.groupId = owner.groupId;
        }
        boolean valid()
        {
            return !this.owner.snapshotsStopped && Objects.equals(this.userId, this.owner.currentUserId)
                && Objects.equals(this.groupId, this.owner.groupId)
                && ExtendedUserAgent.isPrimaryAuthenticationStable(this.generation)
                && this.owner.pendingUsername == null && this.owner.pendingPassword == null;
        }
    }

    static void checkSnapshotRequest(Interceptor.Chain chain) throws IOException
    {
        SnapshotIdentity identity = chain.request().tag(SnapshotIdentity.class);
        if (identity != null && (chain.call().isCanceled() || Thread.currentThread().isInterrupted() || !identity.valid()))
            throw new IOException("Public snapshot cancelled");
    }

    /** Must finish before offline export saves the session and closes credential stores. */
    public boolean shutdownSnapshots()
    {
        ScarletSnapshotFetch fetcher;
        synchronized (SNAPSHOT_LIFECYCLE_LOCK)
        {
            this.snapshotsStopped = true;
            fetcher = this.snapshotFetch;
        }
        return fetcher == null || fetcher.shutdown();
    }

    public void addAlternateCredentials()
    {
        this.scarlet.execModal.submit(() ->
        {
            String altUserId = this.loginAlt(null, false);
            if (altUserId == null)
                this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Canceed", "alt credentials", Color.PINK);
            else
                this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Added: "+this.getUserDisplayName(altUserId), altUserId);
        });
    }

    public void removeAlternateCredentials()
    {
        class AltDisplay
        {
            AltDisplay(String id)
            {
                this.id = id;
                this.name = ScarletVRChat.this.getUserDisplayName(id);
                this.display = this.name+" ("+this.id+")";
            }
            final String id, name, display;
            @Override
            public String toString()
            {
                return this.display;
            }
        }
        AltDisplay[] alts = this.cookies.alts().stream().map(id -> new AltDisplay(id)).toArray(AltDisplay[]::new);
        this.scarlet.settings.requireSelectAsync("Select the credentials to remove", "Remove alternate credentials", alts, null, selected ->
        {
            if (selected == null)
                return;
            synchronized (this)
            {
                boolean loggedOut = false;
                try
                {
                    loggedOut = this.logoutAlt(selected.id, false);
                }
                catch (RuntimeException ex)
                {
                    LOG.warn("Alternate logout could not be confirmed; continuing local credential removal");
                }
                try
                {
                    this.cookies.removeAlt(selected.id);
                    this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Removed local credentials for "+selected.name,
                        loggedOut ? "Remote session logout confirmed." : "Remote logout was not confirmed; revoke that session in VRChat if needed.",
                        loggedOut ? Color.WHITE : Color.YELLOW);
                }
                catch (RuntimeException ex)
                {
                    LOG.error("Could not finish persisting alternate credential removal", ex);
                    this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Credential removal incomplete",
                        "A storage error prevented confirmation. Check storage and retry removal.", Color.RED);
                }
            }
        });
    }

    public void listAlternateCredentials()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            StringBuilder sb = new StringBuilder("Alternate credentials:");
            for (String alt : this.cookies.alts())
                sb.append('\n').append('\t').append(this.getUserDisplayName(alt)).append(':').append(alt);
            LOG.info(sb.toString());
            return;
        }
        JPanel panel = new JPanel(new GridBagLayout());
        {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridheight = 1;
            constraints.gridwidth = 1;
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.insets = new Insets(1, 1, 1, 1);
            constraints.weightx = 0.0D;
            constraints.weighty = 0.0D;
            for (String alt : this.cookies.alts())
            {
                constraints.gridx = 0;
                constraints.anchor = GridBagConstraints.EAST;
                panel.add(new JLabel(this.getUserDisplayName(alt)+":", JLabel.RIGHT), constraints);
                constraints.gridx = 1;
                constraints.anchor = GridBagConstraints.WEST;
                panel.add(new JLabel(alt, JLabel.LEFT), constraints);
                constraints.gridy++;
            }
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setSize(new Dimension(500, 300));
        scroll.setPreferredSize(new Dimension(500, 300));
        scroll.setMaximumSize(new Dimension(500, 300));
        this.scarlet.execModal.execute(() -> JOptionPane.showMessageDialog(null, scroll, I18n.tr("ui.altCredsTitle"), JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * Clears the main VRChat account's stored username, password, TOTP secret,
     * and session cookies from encrypted storage and memory. Alternate accounts
     * remain configured; remove each through Remove alternate credentials.
     * The user will be prompted to re-enter main-account credentials on the next login.
     * This is safe to call at any time and does not require the user to
     * manually touch the Registry or AppData folders.
     */
    public void clearCredentials()
    {
        long headerGeneration = ExtendedUserAgent.beginPrimaryAuthentication();
        try
        {
            this.username.clear();
            this.password.clear();
            this.totpsecret.clear();
            this.cookies.clear();
            this.cookies.save();
        }
        finally
        {
            ExtendedUserAgent.completePrimaryAuthentication(null, headerGeneration);
        }
        LOG.info("Main account credentials cleared by user request; alternate accounts are unchanged");
        this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Main account credentials cleared",
            "Log in again when prompted. Alternate accounts are unchanged.", Color.CYAN);
    }

    public void modalNeedPerms(GroupPermissions perms)
    {
        this.scarlet.settings.requireConfirmYesNoAsync(
            "The bot VRChat account `"+this.currentUserId+"` is missing the necessary permission `"+perms.getValue()+"`",
            "Missing permissions",
            () -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.Home.groupSettings(this.groupId))),
            null);
    }
    public String messageNeedPerms(GroupPermissions perms)
    {
        return String.format("The [bot VRChat account](<%s>) is missing the [necessary permission `%s`](<%s>)", VrcWeb.Home.user(this.currentUserId), perms.getValue(), VrcWeb.Home.groupSettings(this.groupId));
    }
    public boolean checkLogNeedPerms(GroupPermissions perms)
    {
        if (this.checkSelfUserHasVRChatPermission(perms))
            return true;
        LOG.error(String.format("The bot VRChat account `%s` is missing the necessary permission `%s`", this.currentUserId, perms.getValue()));
        return false;
    }

    public boolean hasValidGroupId()
    {
        return !MiscUtils.blank(this.groupId);
    }

    public void modalNeedGroupId()
    {
        this.scarlet.settings.requireConfirmYesNoAsync(
            "Hatson321/Scarlet Logger could not determine a valid VRChat group ID for the logged-in account `"+this.currentUserId+"`.\n\n"
          + "Please set the correct `vrchat_group_id` in Hatson321/Scarlet Logger's settings, or make sure the account is representing the intended group before launching Hatson321/Scarlet Logger.",
            "Missing group ID",
            () -> MiscUtils.AWTDesktop.browse(URI.create("https://vrchat.com/home/groups")),
            null);
    }

    public void save()
    {
        this.cookies.save();
    }

    @Override
    public void close() throws IOException
    {
        if (!this.shutdownSnapshots()) throw new IOException("Snapshot lookups did not stop safely");
        // Do not let any late/new session probe identify requests after shutdown.
        ExtendedUserAgent.beginPrimaryAuthentication();
        this.cookies.close();
    }

}
