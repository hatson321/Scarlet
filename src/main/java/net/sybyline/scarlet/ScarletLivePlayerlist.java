package net.sybyline.scarlet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/**
 * An observed-player message in each configured Live Player List destination,
 * independent of Instance Monitor. This is local-client observation, not an
 * authoritative membership API. Ordinary destinations omit secret staff.
 * Only message addresses are persisted, never player/ avatar details.
 * A dedicated serial worker batches updates without trailing-edge starvation.
 */
public class ScarletLivePlayerlist
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/LivePlayerlist");
    static final long BATCH_MS = 2_000L;
    static final int MAX_FAILURES = 5;
    static final int MAX_DESCRIPTION = 4000;
    static final String STORE_FILE = "live-playerlists.json";

    public static final class PlayerSnapshot
    {
        public final String userId, displayName, avatarName;
        public final OffsetDateTime joinedAt;
        public PlayerSnapshot(String userId, String displayName, OffsetDateTime joinedAt, String avatarName)
        {
            this.userId = userId;
            this.displayName = displayName;
            this.joinedAt = joinedAt;
            this.avatarName = avatarName;
        }
    }

    static final class Route
    {
        final String guild, channel;
        final boolean secret;
        Route(String guild, String channel, boolean secret)
        { this.guild = guild; this.channel = channel; this.secret = secret; }
        String key(String location) { return this.guild + "/" + this.channel + "/" + location; }
    }

    /** Minimal durable address, without user identities or roster content. */
    static final class SavedMessage
    {
        String location, guild, channel, message, nonce;
        boolean secret, closing, ambiguous, legacy;
        SavedMessage() { }
        SavedMessage(State state)
        {
            this.location = state.location;
            this.guild = state.route.guild;
            this.channel = state.route.channel;
            this.secret = state.route.secret;
            this.message = state.message;
            this.nonce = state.nonce;
            this.ambiguous = state.ambiguous || (state.message == null && state.nonce != null);
            this.closing = state.closing;
            this.legacy = state.legacy;
        }
    }

    static final class SavedMessages
    {
        List<SavedMessage> messages = new ArrayList<>();
        transient boolean upgradeImport;
    }

    interface Scheduler
    {
        void execute(Runnable action);
        void schedule(Runnable action, long delayMs);
        void stop();
    }

    /** Offline test seam: fake transport and scheduler never connect to Discord. */
    interface Environment
    {
        List<Route> routes();
        boolean secret(String userId);
        boolean staff(String userId);
        default boolean watched(String userId) { return false; }
        SavedMessages load();
        boolean save(SavedMessages messages);
        CompletableFuture<String> send(Route route, String messageId, String nonce, MessageEmbed embed);
        CompletableFuture<String> recover(Route route, String nonce);
        default boolean uncertain(Throwable error)
        {
            while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
            if (error instanceof ErrorResponseException)
            {
                ErrorResponseException response = (ErrorResponseException)error;
                return !definitiveRejection(response.getErrorResponse(), response.getResponse() == null ? -1 : response.getResponse().code);
            }
            return !(error instanceof BeforeDispatchFailure)
                && !(error instanceof net.dv8tion.jda.api.exceptions.InsufficientPermissionException)
                && !(error instanceof IllegalArgumentException);
        }
        default boolean missing(Throwable error)
        {
            while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
            return error instanceof ErrorResponseException &&
                ((ErrorResponseException)error).getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE;
        }
    }

    static boolean definitiveRejection(ErrorResponse error, int httpStatus)
    {
        // A server error can occur after a POST committed. Never assume that
        // the mere presence of an ErrorResponseException proves non-delivery.
        if (httpStatus < 400 || httpStatus >= 500 || error == null) return false;
        switch (error)
        {
        case MISSING_ACCESS: case MISSING_PERMISSIONS: case UNAUTHORIZED:
        case UNKNOWN_CHANNEL: case UNKNOWN_GUILD: case UNKNOWN_MESSAGE:
        case INVALID_FORM_BODY: case INVALID_REQUEST_BODY: case EMPTY_MESSAGE:
        case EMBED_DISABLED: case INVALID_CHANNEL_TYPE:
        case SLOWMODE_RATE_LIMIT: case CHANNEL_WRITE_RATE_LIMIT: case GUILD_WRITE_RATE_LIMIT:
            return true;
        default: return false;
        }
    }

    static final class BeforeDispatchFailure extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;
        BeforeDispatchFailure(String message) { super(message); }
    }

    static final class ObservedPlayer
    {
        PlayerSnapshot player;
        boolean left;
        ObservedPlayer(PlayerSnapshot player) { this.player = player; }
    }

    static final class State
    {
        final String location;
        Route route;
        String message, nonce;
        final Map<String, ObservedPlayer> players = new LinkedHashMap<>();
        boolean closing, inFlight, scheduled, ambiguous, legacy, dirty = true;
        int failures;
        long revision;
        State(String location, Route route) { this.location = location; this.route = route; }
        String key() { return this.route.key(this.location) + (this.legacy ? "/legacy" : ""); }
    }

    private final Environment env;
    private final Scheduler scheduler;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private String location;
    private List<PlayerSnapshot> roster = Collections.emptyList();
    private boolean stopping, storeDirty, storeBlocked, observationKnown;
    private volatile boolean stopped;

    public ScarletLivePlayerlist(Scarlet scarlet)
    {
        this(new Environment()
        {
            public List<Route> routes()
            {
                if (!(scarlet.discord instanceof ScarletDiscordJDA)) return Collections.emptyList();
                ScarletDiscordJDA discord = (ScarletDiscordJDA)scarlet.discord;
                String ordinary = discord.auditExType2channelSf.get(GroupAuditTypeEx.LIVE_PLAYERLIST.id);
                String secret = discord.auditExType2secretChannelSf.get(GroupAuditTypeEx.LIVE_PLAYERLIST.id);
                List<Route> result = new ArrayList<>();
                if (ordinary != null) result.add(new Route(discord.guildSf, ordinary, false));
                // Assigning both audiences to one channel must never expose a secret copy.
                if (secret != null && !secret.equals(ordinary)) result.add(new Route(discord.guildSf, secret, true));
                return result;
            }
            public boolean secret(String userId)
            { return scarlet.secretStaffList != null && scarlet.secretStaffList.isSecretStaffId(userId); }
            public boolean staff(String userId)
            { return scarlet.staffList != null && scarlet.staffList.isStaffId(userId); }
            public boolean watched(String userId)
            { return scarlet.watchedUsers != null && scarlet.watchedUsers.getWatchedEntity(userId) != null; }
            public SavedMessages load()
            {
                if (!new java.io.File(scarlet.data.dir, STORE_FILE).isFile())
                    return legacyMessages(scarlet.data.liveInstances.get());
                com.google.gson.JsonObject object = scarlet.data.readObj(STORE_FILE, com.google.gson.JsonObject.class);
                if (object == null || !object.has("messages") || !object.get("messages").isJsonArray()) return null;
                try { return io.github.vrchatapi.JSON.getGson().fromJson(object, SavedMessages.class); }
                catch (RuntimeException ex) { LOG.error("Live Player List address storage has an invalid structure", ex); return null; }
            }
            public boolean save(SavedMessages messages)
            { return scarlet.data.writeObj(STORE_FILE, SavedMessages.class, messages); }
            private GuildMessageChannel channel(Route route)
            {
                if (!(scarlet.discord instanceof ScarletDiscordJDA)) throw new BeforeDispatchFailure("Discord is not ready");
                ScarletDiscordJDA discord = (ScarletDiscordJDA)scarlet.discord;
                Guild guild = discord.jda() == null ? null : discord.jda().getGuildById(route.guild);
                GuildMessageChannel channel = guild == null ? null : guild.getTextChannelById(route.channel);
                if (channel == null && guild != null) channel = guild.getThreadChannelById(route.channel);
                if (channel == null) throw new BeforeDispatchFailure("Live Player List destination is unavailable");
                return channel;
            }
            public CompletableFuture<String> send(Route route, String messageId, String nonce, MessageEmbed embed)
            {
                GuildMessageChannel channel = this.channel(route);
                if (messageId == null)
                {
                    net.dv8tion.jda.api.requests.restaction.MessageCreateAction action = channel.sendMessageEmbeds(embed).setNonce(nonce);
                    if (embed.getUrl() != null) action.addComponents(ActionRow.of(Button.link(embed.getUrl(), "Join Instance")));
                    return action.timeout(15, TimeUnit.SECONDS).submit().thenApply(m -> m.getId());
                }
                return channel.editMessageEmbedsById(messageId, embed).setComponents(embed.getUrl() == null ? Collections.emptyList()
                        : Collections.singletonList(ActionRow.of(Button.link(embed.getUrl(), "Join Instance"))))
                    .timeout(15, TimeUnit.SECONDS).submit().thenApply(m -> m.getId());
            }
            public CompletableFuture<String> recover(Route route, String nonce)
            {
                GuildMessageChannel channel = this.channel(route);
                return channel.getHistory().retrievePast(100).timeout(15, TimeUnit.SECONDS).submit().thenApply(messages ->
                    messages.stream().filter(message -> nonce.equals(message.getNonce()) &&
                        message.getAuthor().getId().equals(channel.getJDA().getSelfUser().getId()))
                        .map(message -> message.getId()).findFirst().orElse(null));
            }
        }, worker());
    }

    ScarletLivePlayerlist(Environment env, Scheduler scheduler)
    {
        this.env = env;
        this.scheduler = scheduler;
        this.enqueue(() ->
        {
            SavedMessages saved;
            try { saved = env.load(); }
            catch (Exception ex)
            { this.storeBlocked = true; LOG.error("Live Player List address storage could not be loaded; storage left untouched", ex); return; }
            if (saved == null || saved.messages == null)
            {
                this.storeBlocked = true;
                LOG.error("Live Player List address storage is unreadable; automatic posting and replacement writes are disabled to preserve recovery data");
                return;
            }
            if (saved.messages != null)
                for (SavedMessage message : saved.messages)
                {
                    if (message == null || message.location == null || message.guild == null || message.channel == null || (message.message == null && message.nonce == null))
                    { this.storeBlocked = true; LOG.error("Invalid Live Player List address record; storage left untouched"); return; }
                    State state = new State(message.location, new Route(message.guild, message.channel, message.secret));
                    state.message = message.message;
                    state.nonce = message.nonce;
                    state.ambiguous = message.ambiguous || message.message == null;
                    state.legacy = message.legacy;
                    state.closing = message.closing || message.legacy;
                    if (this.states.containsKey(state.key()))
                    { this.storeBlocked = true; LOG.error("Duplicate Live Player List address record; storage left untouched"); return; }
                    this.states.put(state.key(), state);
                }
            if (saved.upgradeImport) this.persist();
            long cleanupDelay = BATCH_MS;
            for (State state : this.states.values())
                if (state.legacy) { this.request(state, cleanupDelay); cleanupDelay += BATCH_MS; }
            this.periodic();
        });
    }

    /** Import only recorded old player-list IDs, never scan or edit other messages. */
    static SavedMessages legacyMessages(ScarletData.LiveInstancesMetadata metadata)
    {
        SavedMessages saved = new SavedMessages();
        saved.upgradeImport = true;
        if (metadata == null) return saved;
        synchronized (metadata)
        {
            if (metadata.location2instanceEmbedMessage == null) return saved;
            for (Map.Entry<String, ScarletData.InstanceEmbedMessage> entry : metadata.location2instanceEmbedMessage.entrySet())
            {
                ScarletData.InstanceEmbedMessage old = entry.getValue();
                if (entry.getKey() == null || old == null || old.guildSnowflake == null || old.threadSnowflake == null || old.playerlistMessageSnowflake == null) continue;
                SavedMessage address = new SavedMessage();
                address.location = entry.getKey();
                address.guild = old.guildSnowflake;
                address.channel = old.threadSnowflake;
                address.message = old.playerlistMessageSnowflake;
                address.closing = address.legacy = true;
                saved.messages.add(address);
            }
        }
        return saved;
    }

    private static Scheduler worker()
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "Scarlet-LivePlayerList");
            thread.setDaemon(true);
            return thread;
        });
        return new Scheduler()
        {
            public void execute(Runnable action) { executor.execute(action); }
            public void schedule(Runnable action, long delayMs) { executor.schedule(action, delayMs, TimeUnit.MILLISECONDS); }
            public void stop() { executor.shutdownNow(); }
        };
    }

    private void enqueue(Runnable action)
    {
        if (this.stopped) return;
        try { this.scheduler.execute(() ->
        {
            try { action.run(); }
            catch (Exception ex) { LOG.error("Live Player List worker failed", ex); }
        }); }
        catch (java.util.concurrent.RejectedExecutionException ex)
        { if (!this.stopped) LOG.warn("Live Player List worker is unavailable"); }
    }

    private void periodic()
    {
        this.scheduler.schedule(() ->
        {
            if (this.stopping || this.stopped) return;
            // Refresh privacy/routing and the observation timestamp. Exhausted
            // failures are NOT reset here, avoiding an endless network retry loop.
            try { this.refresh(false); }
            catch (Exception ex) { LOG.error("Live Player List periodic reconciliation failed", ex); }
            finally { if (!this.stopping && !this.stopped) this.periodic(); }
        }, 30_000L);
    }

    /** Current roster from the local log reader, after its initial preamble. */
    public void reconcile(String location, List<PlayerSnapshot> roster)
    {
        List<PlayerSnapshot> snapshot = roster == null ? Collections.emptyList() : new ArrayList<>(roster);
        this.enqueue(() ->
        {
            if (this.stopping) return;
            this.observationKnown = true;
            this.location = location;
            this.roster = snapshot;
            this.refresh(false);
        });
    }

    /** Re-evaluate routing after a settings change or Discord reconnect. */
    public void reconcile()
    { this.enqueue(() -> { if (!this.stopping) this.refresh(true); }); }

    public void onLocalSessionEnded(String location)
    {
        this.enqueue(() ->
        {
            this.observationKnown = true;
            if (Objects.equals(this.location, location))
            { this.location = null; this.roster = Collections.emptyList(); }
            this.refresh(false);
        });
    }

    private void refresh(boolean retryExhausted)
    {
        if (!this.observationKnown && !this.stopping) return;
        Map<String, Route> desired = new LinkedHashMap<>();
        if (this.location != null && !this.stopping)
            for (Route route : this.env.routes()) desired.put(route.key(this.location), route);
        for (Map.Entry<String, State> entry : new ArrayList<>(this.states.entrySet()))
        {
            State state = entry.getValue();
            Route route = desired.remove(entry.getKey());
            state.closing = route == null;
            if (route != null)
            {
                state.route = route;
                this.updateRoster(state);
            }
            state.dirty = true;
            state.revision++;
            if (retryExhausted) state.failures = 0;
            this.request(state, state.closing ? 0L : BATCH_MS);
        }
        for (Route route : desired.values())
        {
            State state = new State(this.location, route);
            this.updateRoster(state);
            this.states.put(state.key(), state);
            this.request(state, BATCH_MS);
        }
        this.checkDrained();
    }

    private void updateRoster(State state)
    {
        for (ObservedPlayer player : state.players.values()) player.left = true;
        for (PlayerSnapshot player : this.roster)
        {
            if (player == null || player.userId == null) continue;
            ObservedPlayer observed = state.players.computeIfAbsent(player.userId, id -> new ObservedPlayer(player));
            observed.player = player;
            observed.left = false;
        }
    }

    private void request(State state, long delayMs)
    {
        if (state.inFlight || state.scheduled || state.failures >= MAX_FAILURES || this.stopped || this.storeBlocked) return;
        state.scheduled = true;
        this.scheduler.schedule(() ->
        {
            state.scheduled = false;
            try { this.flush(state); }
            catch (Exception ex)
            {
                // Transport invocation has its own ambiguity handling below.
                // An exception here is pre-dispatch preparation, not evidence
                // that an unconfirmed Discord message was created.
                if (!state.inFlight && state.message == null && !state.ambiguous)
                { state.nonce = null; this.persist(); }
                this.failed(state, ex);
            }
        }, delayMs);
    }

    private boolean persist()
    {
        if (this.storeBlocked) return false;
        SavedMessages saved = new SavedMessages();
        for (State state : this.states.values())
            if (state.message != null || state.nonce != null) saved.messages.add(new SavedMessage(state));
        try { this.storeDirty = !this.env.save(saved); }
        catch (Exception ex)
        { this.storeDirty = true; LOG.error("Live Player List address save failed; acknowledged IDs remain in memory", ex); }
        return !this.storeDirty;
    }

    private void flush(State state)
    {
        if (this.stopped || state.inFlight || this.states.get(state.key()) != state) return;
        if (!Objects.equals(this.location, state.location) || this.env.routes().stream().noneMatch(route -> route.key(state.location).equals(state.route.key(state.location))))
            state.closing = true;
        if (this.storeDirty && !this.persist())
        { this.failed(state, new IllegalStateException("Message address could not be saved")); return; }
        if (state.ambiguous)
        {
            state.inFlight = true;
            try { this.env.recover(state.route, state.nonce).whenComplete((id, error) -> this.enqueue(() ->
            {
                state.inFlight = false;
                if (error != null || id == null)
                {
                    this.failed(state, error == null ? new IllegalStateException("Unconfirmed send: no matching own message in recent history; refusing to repost") : error);
                    return;
                }
                state.message = id;
                state.ambiguous = false;
                state.failures = 0;
                if (!this.persist()) this.failed(state, new IllegalStateException("Recovered address could not be saved"));
                else this.request(state, 0L);
            })); }
            catch (Exception ex) { state.inFlight = false; this.failed(state, ex); }
            return;
        }
        if (state.closing && state.message == null)
        {
            this.states.remove(state.key());
            if (!this.persist())
            {
                this.states.put(state.key(), state);
                this.failed(state, new IllegalStateException("Retired address could not be saved"));
            }
            this.checkDrained();
            return;
        }
        if (state.message == null)
        {
            if (state.nonce == null) state.nonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25);
            // Persist the intent BEFORE dispatch; a crash can only recover this
            // nonce, never blindly create another copy of an unconfirmed post.
            if (!this.persist()) { this.failed(state, new IllegalStateException("Create intent could not be saved")); return; }
        }
        // Never retain a cached secret classification across routing changes.
        List<Route> routes = this.env.routes();
        boolean secret = !state.closing && routes.stream().anyMatch(route ->
            route.secret && route.key(state.location).equals(state.route.key(state.location))) &&
            routes.stream().noneMatch(route -> !route.secret && Objects.equals(route.channel, state.route.channel));
        MessageEmbed embed = render(state, secret, this.env::secret, this.env::staff, this.env::watched);
        long revision = state.revision;
        boolean closing = state.closing;
        state.inFlight = true;
        state.dirty = false;
        try
        {
            this.env.send(state.route, state.message, state.nonce, embed).whenComplete((id, error) -> this.enqueue(() ->
            {
                state.inFlight = false;
                if (error != null)
                {
                    if (state.message != null && this.env.missing(error)) { state.message = null; state.nonce = null; }
                    else if (state.message == null)
                    {
                        state.ambiguous = this.env.uncertain(error);
                        if (!state.ambiguous) { state.nonce = null; this.persist(); }
                    }
                    this.failed(state, error);
                    return;
                }
                state.message = id;
                state.failures = 0;
                if (closing && state.closing && revision == state.revision)
                    this.states.remove(state.key());
                if (!this.persist())
                {
                    // Retain an acknowledged ID in memory; saving it again must
                    // not create a duplicate message in this process.
                    if (!this.states.containsKey(state.key())) this.states.put(state.key(), state);
                    this.failed(state, new IllegalStateException("Message address could not be saved"));
                    return;
                }
                if (state.dirty || revision != state.revision) this.request(state, state.closing ? 0L : BATCH_MS);
                this.checkDrained();
            }));
        }
        catch (Exception ex)
        {
            state.inFlight = false;
            if (state.message == null)
            {
                state.ambiguous = this.env.uncertain(ex);
                if (!state.ambiguous) { state.nonce = null; this.persist(); }
            }
            this.failed(state, ex);
        }
    }

    private void failed(State state, Throwable error)
    {
        state.dirty = true;
        state.failures++;
        if (state.failures >= MAX_FAILURES)
            LOG.error("Live Player List delivery stopped after {} attempts; check channel permissions/connectivity and reapply its channel setting to retry", MAX_FAILURES, error);
        else
        {
            LOG.warn("Live Player List delivery failed; retry {}/{} is scheduled", state.failures, MAX_FAILURES);
            this.request(state, BATCH_MS << (state.failures - 1));
        }
        this.checkDrained();
    }

    private void checkDrained()
    {
        if (this.stopping && (this.storeBlocked || this.states.values().stream().noneMatch(state -> state.inFlight || state.scheduled || state.failures < MAX_FAILURES)))
            this.drained.complete(null);
    }

    /** Finalise while Discord is connected, without an unbounded shutdown wait. */
    public void shutdown()
    {
        if (this.stopped) return;
        this.enqueue(() -> { this.stopping = true; this.location = null; this.refresh(true); this.persist(); });
        try { this.drained.get(6, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (Exception ex) { LOG.warn("Live Player List shutdown timed out; saved message addresses will be reconciled on restart"); }
        finally { this.stopped = true; this.scheduler.stop(); }
    }

    static MessageEmbed render(State state, boolean secretAudience, Predicate<String> secret, Predicate<String> staff, Predicate<String> watched)
    {
        List<ObservedPlayer> visible = new ArrayList<>();
        for (ObservedPlayer player : state.players.values())
            if (secretAudience || !secret.test(player.player.userId)) visible.add(player);
        visible.sort((a, b) -> Boolean.compare(a.left, b.left));
        long present = visible.stream().filter(player -> !player.left).count();
        StringBuilder description = new StringBuilder("**").append(present).append(" observed present**\n\n");
        int included = 0;
        for (ObservedPlayer observed : visible)
        {
            PlayerSnapshot player = observed.player;
            StringBuilder row = new StringBuilder();
            if (observed.left) row.append("~~");
            if (staff.test(player.userId)) row.append("⭐ ");
            if (watched.test(player.userId)) row.append("⚠ ");
            String name = text(player.displayName, 96);
            if (player.userId.matches("usr_[A-Za-z0-9-]+"))
                row.append('[').append(name).append("](https://vrchat.com/home/user/").append(player.userId).append(')');
            else row.append(name);
            if (player.joinedAt != null) row.append(" · <t:").append(player.joinedAt.toEpochSecond()).append(":R>");
            if (player.avatarName != null && !player.avatarName.isEmpty()) row.append(" · *").append(text(player.avatarName, 96)).append('*');
            if (observed.left) row.append("~~ (left)");
            row.append('\n');
            if (description.length() + row.length() + 90 > MAX_DESCRIPTION) break;
            description.append(row);
            included++;
        }
        if (visible.isEmpty()) description.append("*No reportable players observed.*");
        if (included < visible.size()) description.append("\n*").append(visible.size() - included).append(" additional observed rows omitted to fit Discord.*");
        return new EmbedBuilder().setTitle("Live Player List", state.closing ? null : launchUrl(state.location))
            .setColor(GroupAuditTypeEx.LIVE_PLAYERLIST.color).setDescription(description)
            .setFooter(state.closing ? "Observation ended · not an instance-close confirmation"
                : "Local client observation · may be incomplete · last updated")
            .setTimestamp(Instant.now()).build();
    }

    private static String launchUrl(String location)
    {
        if (location == null || location.length() > 512) return null;
        int colon = location.indexOf(':');
        if (colon < 0 || !location.substring(0, colon).matches("wrld_[a-fA-F0-9-]{36}") || location.indexOf("usr_") >= 0) return null;
        try
        {
            String url = "https://vrchat.com/home/launch?worldId=" + java.net.URLEncoder.encode(location.substring(0, colon), "UTF-8")
                + "&instanceId=" + java.net.URLEncoder.encode(location.substring(colon + 1), "UTF-8");
            return url.length() <= 512 ? url : null;
        }
        catch (java.io.UnsupportedEncodingException ex) { return null; }
    }

    private static String text(String value, int max)
    {
        if (value == null || value.isEmpty()) return "Unknown";
        value = value.replace('\n', ' ').replace('\r', ' ');
        int end = Math.min(value.length(), max);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return MarkdownSanitizer.escape(value.substring(0, end)).replace("@", "＠").replace("]", "\\]").replace("[", "\\[") + (end < value.length() ? "…" : "");
    }
}
