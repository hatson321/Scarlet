# Scarlet 0.4.20

A feature release focused on **spotting the right people faster** and **reacting to new instances faster**, plus a readability pass on the player-list colours.

> Version header is provisional (0.4.20) — rename if you'd rather cut this as something else.

## ⚠️ Tagging kicks, bans and warns is fixed (broken in 0.4.19)

**This is the big one.** In 0.4.19, tagging moderation events from Discord was broken for everyone: clicking **Edit tags**, typing a search and submitting was refused. I'm sorry for shipping that.

**What went wrong:** Scarlet only lets a Discord button, menu or pop-up through if its ID is on a built-in default allow-list; anything else is refused unless a server admin grants it with `/scarlet-discord-permissions`. 0.4.19 added tag search with two new IDs — the search pop-up (`tag-search`) and its results menu (`select-tags-search`) — and neither was added to that list.

**Tagging in 0.4.20:** **Edit tags** is back to the familiar dropdowns of every tag with descriptions (split into groups of 25, Discord's per-dropdown limit; type in a dropdown to filter it). To search **all** tags at once, run **`/tag add`** inside the moderation thread: as you type, it narrows every tag down to the matches, with descriptions, and you can add up to five in one go (`/tag remove` works the same way). The 0.4.19 search pop-up is gone.

**In 0.4.20 it just works.** Both are allowed by default, so you don't need to do anything after updating. **Who can tag is now simpler:** if your Discord role can **post in the moderation thread**, you can tag that event. Your existing staff role and channel permissions decide it, with nothing to grant in Scarlet. Before, tagging needed ban-level access, so instance hosts who warn and kick got "You do not have permission to modify audit event moderation state." even on their own kicks. Also allowed: full moderators, the moderator who took the action (via a linked VRChat account), anyone with VRChat instance-moderation permission, and a new `groupex-tags-edit` override. Read-only viewers of a moderation channel still can't tag, and redacting still needs full moderation access. Keep your moderation channels hidden or read-only for non-staff.

**Still on 0.4.19? Run these once** (as a server admin, replacing `@Moderators` with your moderator role, or use `@everyone` — the tag handlers still check moderator permissions themselves):

```
/scarlet-discord-permissions set target:@Moderators type:Modal Submit name:tag-search value:Allow
/scarlet-discord-permissions set target:@Moderators type:String Select name:select-tags-search value:Allow
```

The same mistake had also locked a few older buttons unless an admin had granted them by hand. They're all allowed by default in 0.4.20:

- the **Timed ban** buttons (6h / 24h / 3d / 7d) and the **Timed ban…** custom-duration pop-up (`timed-ban`, `timed-ban-custom`) — still require ban-management permission
- the **Accept / Reject / Block** buttons on group join requests (`group-request-accept`, `group-request-reject`, `group-request-block`) — still require invite-management permission
- the **watched-remove** menu (`watched-remove`) — only ever shown privately to whoever ran the command

On 0.4.19 these can be unlocked the same way (`type:Button Press` for the buttons, `type:Modal Submit` for `timed-ban-custom`'s pop-up, `type:String Select` for `watched-remove`).

**Testing it yourself, even on a tiny server:** the training simulator (Settings → Training, then Edit → *Simulate event (training)…*) now has **Moderator warns / kicks / bans player** events. Each one creates a real moderation post, with Edit tags and the Timed ban buttons (and `/tag` works in its thread), in your training channel (`/set-training-channel`). No real moderation action ever happens. Click through it from a moderator account that is **not** a server admin or owner: admins and owners skip Scarlet's interaction permission check entirely, which is exactly how this bug went unnoticed.

**So it can't happen quietly again:** Scarlet now logs a warning at startup naming any button, menu or pop-up that isn't on the default allow-list. A correct build logs none, so a forgotten entry shows up on the first launch of a test build instead of in your servers.

Also fixed alongside it: a tag whose label is longer than 100 characters no longer stops the whole tag menu from appearing (Discord's limit; long labels are now shortened with "…"), and the tag search's "you don't have permission" reply now actually shows instead of erroring.

## Post-release compatibility and moderation-tag updates

- **VRChat avatar-tag compatibility.** Scarlet now accepts the current VRChat response where `presence.currentAvatarTags` is an array, even though the bundled `vrchatapi-java` 1.21.0 SDK still expects a string. This prevents the current-user response from failing to deserialize while leaving the correctly typed top-level avatar-tags field untouched.
- **Moderation-tag browser.** Chloethecat's browse-all-tags picker (every tag split into Discord's five 25-option menus, with the entry's existing tags preselected) is now what **Edit tags** opens by default, replacing the 0.4.19 search pop-up. The separate Browse tags button is folded into it (it still works on older posts); searching across all tags is done with `/tag add`.
- **Desktop build workflow.** Pushes to `main` that affect source, the Maven project, vendored libdave, or the workflow now build Scarlet with Java 21 and attach the desktop JAR as a 30-day GitHub Actions artifact.

These contributions are adapted from [Chloethecat's Scarlet fork](https://github.com/Chloethecat/Scarlet); the tag browser is now the default Edit tags experience.

## PowerPC support (experimental)

Scarlet now has a **PowerPC edition** for 32-bit PowerPC Linux: `scarlet-0.4.20-ppc.jar`, published next to the normal jar. It's **heavily untested**; so far it has been run on exactly one machine, a Nintendo Wii running Wii-Linux. Yes, really.

What's different in the PowerPC edition:

- It bundles a PowerPC build of the Discord voice (DAVE) library.
- These CPUs have no Java JIT, so everything runs slowly. To keep up, it skips the startup security self-tests (they'd take many minutes), and asks Discord not to send event types Scarlet never uses (typing, reactions and so on).
- The local credential store uses fewer key-derivation rounds. Its key is a random key file, so stretching it adds nothing; but **secrets saved by the PowerPC edition can only be read by the PowerPC edition**, so you can't move that data folder to a normal install and keep your saved logins.

Expect it to be slow and to have rough edges. If you try it, reports are very welcome. Everyone else: use the normal jar, which is unchanged and contains no PowerPC files.

## avtrDB is back

avtrDB is an avatar-search provider again, alongside nekosunevr, VRCDB, WorldBalancer, PAW and KitsuneDB. It had been dropped because it was rejecting requests from datacenter IPs and VPNs; its maintainer now lets Scarlet's requests through. It's used for avatar name lookups; "search by picture" keeps the rebuilt method described further down. If you use the default provider list there's nothing to do. If you set a custom list, tick avtrDB in the avatar search providers settings.

## Back online faster after downtime

After Scarlet had been offline, it caught up on the missed audit log one day per poll (once a minute) with no limit, so an install idle for months spent hours replaying old events before live kicks, bans and warns posted again. Catch-up now goes back at most **`audit_catchup_max_days`** (new setting, default 14, range 1-90; anything older is skipped with a warning in the log) and polls every 10 seconds while behind, so a two-week backlog clears in a few minutes.

## New commands show up without extra steps

Scarlet only synced its slash commands with Discord when the version number changed. It now checks on every start and sends only commands that are new or changed. If a new command like `/tag` doesn't appear right away, press Ctrl+R in Discord (or restart the app).

## Trust ranks

The player list now has a **Rank** column showing each player's VRChat trust rank — Visitor, New User, User, Known, Trusted — plus **Nuisance** for troll-flagged accounts. It's derived from the account's own tags, so it costs no extra API calls. VRChat only returns a complete tag set for some users, so treat it as a strong hint rather than a guarantee; in practice it's right for the large majority of joins.

Two rank-based alerts come with it:

- **Nuisance (troll-flagged):** on by default. It's flagged in the advisory column and sorted to the top of the list, the bundled **siren-chirp** alert plays, and the join callout speaks "Nuisance rank." The sound is one of your `BL_SFX_*` effects, rendered to WAV and shipped inside the build (extracted to a scratch temp file for playback, never the data folder) so it plays on every route including Discord voice. Turn the pieces off with `advisory_flag_nuisance_rank` (row/advisory) and `tts_announce_nuisance_rank` (alarm + callout).
- **Visitor:** opt-in, off by default. An optional row advisory (`advisory_flag_visitor_rank`) and an optional spoken callout (`tts_announce_visitor_rank`) for owners who want to watch for brand-new accounts. Visitors are usually fine, so this is quiet unless you ask for it.

## Faster follow-into-new-instance (~90s → ~30s)

When *Launch on Instance Create* is enabled, the bot used to take roughly a minute and a half to notice a freshly created instance, because it waited for the group **audit log** — which is limited both by the poll interval and by VRChat's own ingest lag before an entry even appears.

Scarlet now also watches the group's **live open-instance list** on a short cadence and cold-boots the client the moment a new instance shows up. It's built to stay well clear of anything VRChat would flag:

- one lightweight call per cycle, at a **tunable interval** (`instance_follow_fast_poll_seconds`, default **30s**, hard **15s** floor),
- only while *Launch on Instance Create* is on,
- backs off a full minute on any error or rate-limit response,
- deduplicated with the existing audit path, so an instance is never launched into twice.

Net effect: detection drops from ~90s to ~30s (tunable) without hammering the API.

## Readability: advisory colours

The player-list advisory colours were raw, over-saturated primaries that were harsh on a dark theme and rough to read over a Discord screenshare. They've been retuned to a calmer, higher-contrast set that reads the same at a glance but is much easier on the eyes — and **Nuisance** and **Visitor** ranks now each have their own distinct colour. This is UI-only; Discord embed colours are unchanged.

## Headless fix

When Scarlet finds more than one data folder and there's no interactive console (a headless service), the "which folder?" prompt no longer opens a blocking read on stdin — it logs the candidates and deterministically loads the first, so a headless bot can't stall at startup.

## Bans no longer logged as standalone kicks

Old bug, going back to the legacy `0.4.12`-era builds. When you ban someone who's currently in an instance, VRChat auto-issues an instance-kick alongside the ban. Scarlet is meant to recognize that kick as part of the ban and bundle it under the ban's thread rather than posting it as a separate kick.

The detection compared the new kick against the target's "most recent prior action" — but an inverted date comparison made that resolve to the target's *oldest* action instead of the newest. So for any user with prior moderation history, the co-occurring ban was missed and its auto-kick got logged as a normal kick. Fixed the comparison (it now correctly picks the newest prior action, which also corrects the "Most recent" line in the moderation embed).

Note for testing: there's a separate, intermittent timing factor — the ban's Discord thread is created a beat after the ban is posted, so if the auto-kick is processed in the very same audit poll it can occasionally still slip through before the thread exists. If you still see the odd ban-as-kick after this fix, that's the cause and I'll harden the timing.


## Outstanding-moderation re-ping fixed

The reminder that re-pings a moderator when they log an action without a reason was completely non-functional: it was never called from the run loop, and its "is this outstanding?" test was inverted. Both are fixed — it now runs, and correctly flags only actions with **no tags and no description** (skipping redacted entries and the auto-kick that gets bundled under a ban).

Per community request, **a message posted in the moderation thread now counts as a valid log** — the first message a moderator sends in the thread is captured as that action's reason, so the re-ping clears without needing tags. (Heads-up: this uses an in-memory thread→entry map, so it captures messages in threads created since the bot last started; a message in a pre-restart thread won't be captured. And the whole feature only posts if you've configured an "Outstanding Moderation" channel and enabled the relevant *Ping on outstanding…* toggles.)


## Report profile pictures / user icons to VRChat

Community idea (KyootFox): reporting in-game profile / sticker / emoji content. Sticker, emoji and print reporting already existed — Scarlet surfaces a pre-filled VRChat T&S report link on each one's in-instance spawn embed. The gap was **profile pictures and user icons**, which had a report-URL builder in the code but were never shown anywhere.

Moderation embeds now include **Report profile picture** and **Report user icon** links, pre-filled with the target user and your configured report email (Settings → "VRChat Help Desk report email"). Scarlet only assembles the report; a human clicks through and files it with VRChat T&S — no automated reporting under the bot account.


## Moderation/report data no longer drops out when the API is flaky

The bug behind "sometimes the report data doesn't appear, and it's inconsistent." The moderation embed assumed VRChat always returns the full target user, and dereferenced it in several places (name, image, join date, pronouns, status). When the API didn't — rate limits, a private profile, or a transient failure, the same flakiness behind the avatar/trust-rank gaps — the embed threw and the entire moderation log (with its report links) silently failed to post. Which user it hit was luck of the draw, so it looked random across people.

Now every user field is null-guarded and falls back to the audit entry's own IDs. The log and the pre-filled report links always post; any field VRChat didn't return is just omitted (with a short note), instead of taking the whole message down with it.

## "Edit tags" and "Manager notes" buttons work again

Both button handlers built their modal but never `.queue()`d it, so the modal was never sent and Discord showed "interaction failed" (the repeated "did not acknowledge" warnings in the logs). This was universal — not ARM-specific — and both now queue the reply.

## Report flow updated for VRChat's reporting changes

The `vrchat-report` button provides **both** a plain-text copy-and-paste report and an **autofilled VRChat Help Desk form using the configured report template**. The simpler notes-and-tags form link is also available. The template and copyable report share the same content and optional footer; when no template is configured, event details provide a useful fallback. Long reports or form URLs are attached in full as text files when they exceed Discord's display limits. Replies remain private to the requesting moderator. Sign in to the Help Desk and reopen the link if necessary, review the filled fields, and attach supporting evidence. VRChat recommends in-app reporting for general reports. Scarlet does not submit reports automatically.

## ARM/glibc Discord voice (DAVE) groundwork

On a glibc arm64 box (e.g. a Raspberry Pi running the desktop jar) there's no native `libdave-jvm`, so Discord voice falls back to the JNA `DAudioDaveSession` path — which mis-handshakes and makes the bot thrash (join/leave) in voice. That fallback (only ever active on such platforms) is now heavily logged at each DAVE handshake step, with the MLS-init failure surfaced at error level, so the next ARM voice attempt pinpoints exactly where E2EE dies. Also fixed two casts the original author had flagged `// smells sus`. The former Termux/Android artifact mentioned by this historical release note has since been removed; no Android build is distributed or supported.


## Quieter logs: down or dead avatar-search providers

If an avatar-search provider goes down — its domain stops resolving, it starts returning HTTP 403/404, it times out, or it refuses the connection — Scarlet used to retry it on **every single search** and dump a full stack trace each time. One permanently-dead host could bury the console on its own.

Now a failing provider logs **one** warning when it first goes down and **one** when Scarlet gives up on it, then goes quiet. The stack traces for these expected network failures are gone from the normal logs (TRACE only). Every failure now backs off, and the backoff **doubles** with each repeat up to a ~2-hour cap, so a dead provider is retried a couple of times an hour at most instead of constantly. When a provider starts responding again it's picked back up automatically, with a one-line "recovered" note.

Two concrete results from the reported logs:

- **`vrcx.avtr.zip` is removed.** Its hostname no longer resolves, so it could only ever fail — it was the biggest single source of the spam.
- **avtrDB no longer powers "search by picture"**, because at the time its search was rejecting requests from datacenter IPs and likely VPNs, so **"search by picture" was rebuilt without it** (next section). avtrDB is **back for avatar name search** now that its maintainer lets Scarlet's requests through (see below).

## Reverse-image search, rebuilt dependency-free

avtrDB was the only provider that answered "here's an avatar image, which avatar is it?" directly. At the time, its endpoint was unavailable to Scarlet because of datacenter/VPN filtering, so rather than leave the feature broken, "search by picture" is rebuilt from pieces Scarlet already trusts:

1. The avatar image's file ID is resolved to its **owner** via VRChat's own (authenticated) file API.
2. Each remaining provider is queried **by author** — the standard VRCX `authorId` lookup.
3. Only the avatar whose image references that same file ID is kept.

No new API key, no new dependency, and nothing that can quietly die on you the way `avtr.zip` did. Author-lookup state is tracked **separately** from text search, so if a provider doesn't support author lookup it backs off quietly for that mode without touching its text search. If the owner can't be resolved or no provider indexes them, it returns no match and falls back to name search — exactly as before.

> **Correction:** An earlier version of these notes said avtrDB required an API key. That was a misunderstanding by KozyBlake and Claude. The avtrDB maintainer confirmed that datacenter IPs (and likely VPNs) were being blocked, and now lets requests identified as Scarlet through even from those networks.
>
> **avtrDB is back as an avatar name-search provider** in this release, alongside the other five. It isn't used for "search by picture", which keeps the rebuilt method above. If you use the default provider list you don't need to do anything; if you set a custom list, tick avtrDB in the avatar search providers settings.

## Built on the VRCX avatar-search ecosystem

Worth stating plainly: Scarlet's whole avatar-search feature — text search, author lookup, and the rebuilt reverse-image — runs on the **VRCX avatar-search provider format**. That's the query convention ([VRCX](https://github.com/vrcx-team/VRCX)'s `?search=` / `?authorId=` / `?fileId=` shape, `n=5000`, and the tolerant JSON response) and the same community provider ecosystem VRCX popularised (nekosunevr, VRCDB, WorldBalancer, paw, KitsuneDB). The reverse-image rebuild also follows VRCX's own documented behaviour: try a direct file-ID lookup, otherwise resolve the owner and match by author.

To be precise about it: Scarlet **implements** that format — it doesn't bundle, fork, or import VRCX's code. Building to the shared convention is what keeps Scarlet interoperable with the same providers VRCX uses, and lets it benefit from (and contribute back to) that ecosystem.

## First-launch maintainer and release-process notice

scarlet is now under a new maintainer hatson321 ai is used to assist in the api updates and gets proof read by hatson321 and the other scarlet admin team members before release. tests are performed as well before release to ensure safe operational status and a fully detailed update html is provided disclosing what was changed and tested.

- Shown **once**, then remembered in `settings.json`.
- **Headless / scheduled** bots don't get a dialog they can't answer — they log the notice once and keep running.
- The same note now sits at the top of the README.

