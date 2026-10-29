package net.sybyline.scarlet;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.sybyline.scarlet.util.FileBackups;

/** Durable intent receipts. A restarted or ambiguous intent is never automatically replayed. */
class ScarletModerationQueue
{
    enum State { QUEUED, CLAIMED, SUCCEEDED, REJECTED, REVIEW }

    static final class Receipt
    {
        String id;
        ScarletDiscord.Action action;
        State state;
        long acceptedAt;
        String reason;
    }

    static final class Store
    {
        int version = 1;
        List<Receipt> receipts = new ArrayList<>();
    }

    private final File file;
    private Store store = new Store();
    private boolean failed;

    ScarletModerationQueue(File file)
    {
        this.file = file;
        if (!ScarletData.existingRecord(file)) return;
        try
        {
            Store loaded;
            try (Reader input = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
                JsonReader reader = new JsonReader(input))
            {
                reader.setStrictness(com.google.gson.Strictness.STRICT);
                JsonElement json = new com.google.gson.Gson().getAdapter(JsonElement.class).read(reader);
                if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("version")
                    || !json.getAsJsonObject().has("receipts") || reader.peek() != JsonToken.END_DOCUMENT)
                    throw new IOException("Invalid moderation queue document");
                loaded = Scarlet.GSON.fromJson(json, Store.class);
            } // Close the read handle before an atomic migration replaces this file on Windows.
            if (loaded == null || loaded.version != 1 || loaded.receipts == null)
                throw new IOException("Unsupported moderation queue document");
            Set<String> ids = new HashSet<>();
            boolean changed = false;
            for (Receipt receipt : loaded.receipts)
            {
                if (receipt == null || receipt.id == null || receipt.id.isEmpty() || !ids.add(receipt.id)
                    || receipt.action == null || receipt.state == null)
                    throw new IOException("Invalid moderation queue receipt");
                if (receipt.state == State.QUEUED || receipt.state == State.CLAIMED)
                {
                    receipt.reason = receipt.state == State.CLAIMED ? "Interrupted dispatch; outcome requires review"
                        : "Queued before restart; verify current intent before resubmitting";
                    receipt.state = State.REVIEW;
                    changed = true;
                }
            }
            this.store = loaded;
            if (changed) this.persist(loaded);
        }
        catch (Exception ex)
        {
            this.failed = true;
            throw new IllegalStateException("Moderation queue could not be loaded safely; no queued action may run", ex);
        }
    }

    synchronized void accept(List<ScarletDiscord.Action> actions)
    {
        this.requireUsable();
        if (actions == null || actions.isEmpty()) throw new IllegalArgumentException("No moderation actions supplied");
        Store next = this.copy();
        for (ScarletDiscord.Action action : actions)
        {
            if (action == null || !("ban".equals(action.action) || "unban".equals(action.action))
                || blank(action.actorId) || blank(action.targetId) || blank(action.requesterDiscordId)
                || blank(action.discordGuildId) || blank(action.vrchatGroupId)
                || ScarletSimulation.isTrainingId(action.targetId))
                throw new IllegalArgumentException("Unbound moderation action cannot be queued");
            Receipt receipt = new Receipt();
            receipt.id = UUID.randomUUID().toString();
            receipt.action = copyAction(action);
            receipt.state = State.QUEUED;
            receipt.acceptedAt = System.currentTimeMillis();
            next.receipts.add(receipt);
        }
        this.commit(next); // The caller may acknowledge only after this returns.
    }

    synchronized void importLegacy(List<ScarletDiscord.Action> actions)
    {
        this.requireUsable();
        if (actions == null || actions.isEmpty()) return;
        Store next = this.copy();
        Set<String> ids = new HashSet<>();
        for (Receipt receipt : next.receipts) ids.add(receipt.id);
        int index = 0;
        for (ScarletDiscord.Action action : actions)
        {
            if (action == null) throw new IllegalArgumentException("Invalid legacy action");
            String id = "legacy-" + UUID.nameUUIDFromBytes((index++ + ":" + Scarlet.GSON.toJson(action)).getBytes(StandardCharsets.UTF_8));
            if (!ids.add(id)) continue;
            Receipt receipt = new Receipt();
            receipt.id = id;
            receipt.action = copyAction(action);
            receipt.state = State.REVIEW;
            receipt.reason = "Legacy queue has no reliable dispatch receipt; verify before submitting a new request";
            next.receipts.add(receipt);
        }
        if (next.receipts.size() != this.store.receipts.size()) this.commit(next);
    }

    synchronized Receipt claim()
    {
        this.requireUsable();
        Store next = this.copy();
        for (Receipt receipt : next.receipts)
            if (receipt.state == State.QUEUED)
            {
                receipt.state = State.CLAIMED;
                this.commit(next); // Remote execution is forbidden until the claim is durable.
                return copyReceipt(receipt);
            }
        return null;
    }

    synchronized void finish(String id, ScarletVRChat.GroupModerationResult outcome)
    {
        this.requireUsable();
        Store next = this.copy();
        for (Receipt receipt : next.receipts)
            if (receipt.id.equals(id) && receipt.state == State.CLAIMED)
            {
                receipt.state = outcome == ScarletVRChat.GroupModerationResult.SUCCEEDED ? State.SUCCEEDED
                    : outcome == ScarletVRChat.GroupModerationResult.REJECTED ? State.REJECTED : State.REVIEW;
                receipt.reason = receipt.state == State.REVIEW ? "Dispatch outcome uncertain; inspect group and audit history before a new request" : null;
                // If this write fails, the durable CLAIMED record still forbids replay.
                this.commit(next);
                return;
            }
        throw new IllegalStateException("No matching claimed moderation receipt");
    }

    synchronized List<ScarletDiscord.Action> pending()
    {
        List<ScarletDiscord.Action> result = new ArrayList<>();
        for (Receipt receipt : this.store.receipts)
            if (receipt.state == State.QUEUED) result.add(copyAction(receipt.action));
        return result;
    }

    synchronized List<Receipt> receipts()
    {
        return this.copy().receipts;
    }

    synchronized int reviewCount()
    {
        int count = 0;
        for (Receipt receipt : this.store.receipts)
            if (receipt.state == State.REVIEW || receipt.state == State.CLAIMED) count++;
        return count;
    }

    private Store copy() { return Scarlet.GSON.fromJson(Scarlet.GSON.toJson(this.store), Store.class); }
    private static Receipt copyReceipt(Receipt receipt) { return Scarlet.GSON.fromJson(Scarlet.GSON.toJson(receipt), Receipt.class); }
    private static ScarletDiscord.Action copyAction(ScarletDiscord.Action action)
    {
        // Bulk actions do not need a join location. Never persist a private join link here.
        return new ScarletDiscord.Action(action.action, action.actorId, action.targetId, null,
            action.requesterDiscordId, action.discordGuildId, action.vrchatGroupId);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private void requireUsable()
    {
        if (this.failed) throw new IllegalStateException("Moderation queue storage requires review");
    }
    private void commit(Store next)
    {
        try { this.persist(next); }
        catch (IOException ex) { throw new IllegalStateException("Moderation queue could not be saved; no new dispatch is allowed", ex); }
        this.store = next;
    }
    void persist(Store next) throws IOException
    {
        // Keep receipts until an operator reviews them. Never restore a backup automatically.
        FileBackups.write(this.file, writer -> Scarlet.GSON_PRETTY.toJson(next, Store.class, writer));
    }
}
