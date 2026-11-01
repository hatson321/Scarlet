
# Changelog

## Release status — November 1, 2026

- **Published stable:** [0.6.0](https://github.com/Hatson321/Scarlet/releases/tag/0.6.0).
- **Published preview:** [0.6.1-a11](https://github.com/Hatson321/Scarlet/releases/tag/0.6.1-a11).
- **Repository `main`:** the published `0.6.0` stable application baseline, with refreshed documentation and the approved Help → Credits cleanup. Preview descriptions below do not imply those changes are in stable `main`.
- **Separate local release preparation:** `0.6.1-a12`, not a published release or the version on `main`. Plain `0.6.1` remains a draft, not the current stable download.

The dated summaries below describe published releases. Older entries are retained
as development history, with historical release-note links repaired: labels such as “unpublished candidate” and older
client-shutdown policies describe their original preparation stage, not today's
publication status or the final behavior of later previews. Release dates below
use UTC.

## 0.6.1-a11 — published preview, October 29, 2026

- Add a private, profile-scoped saved avatar-provider metadata cache: successful results last 24 hours, empty results five minutes, and a clear-cache control is available. Provider consent still applies; downloaded avatar images are not stored.
- Expand vote-to-kick alerts with an available target thumbnail, short bio, pronouns and status snapshot. Missing optional profile information does not prevent the base alert.
- Include the preceding safety fixes for confidential audit recovery, queued moderation, unban safeguards, scoped cleanup and verified credential-recovery snapshots.
- The final source and packaged-build runs each recorded 2,158 successful tests and 14 filesystem-capability exclusions. An intermittent Windows IPC shutdown timeout and false startup log-stall warning remain unresolved; Linux/PPC runtime acceptance and end-to-end live vote-to-kick delivery are not established by those results.

## 0.6.1-a9 — published preview, October 26, 2026

- Collect import-progress reporting, updated optional avatar providers, newest-eligible startup instance joining, background player-list enrichment and optional join/leave thumbnails.
- Improve Staff Mode alerts, speech/advisory handling and first-run prompts; add a saved Windows rendering-compatibility option.
- Closed/empty client exit uses a matching closure audit and healthy local logs, with a 30-second grace and exact-process checks. A sole verified same-user client can qualify even when manually launched; multiple clients block selection. Separate default-off Windows force-close options and bounded Scarlet-owned startup recovery remain opt-in.
- Published for preview testing, not as stable 0.6.1. The final Windows runs recorded 2,020 passes with six filesystem exclusions; Linux recorded 2,025 passes with one Windows-only exclusion. Optional force-close paths and Linux desktop/audio acceptance still require live checks.

## 0.6.0 — published stable, October 18, 2026

- Consolidate maintainer-branch work through the earlier A13 builds: background moderation summaries from saved local history, durable Discord audit delivery, shared API-read pacing, clearer instance-close outcomes, authority/privacy checks and safer saving/export/restore.
- Carry forward the A13 verified-client launch/restart protections and checksum-pinned source-build tooling. These cumulative fixes were not all newly introduced by the stable version designation.
- Ship one Windows/Linux desktop JAR, its matching frozen source ZIP and checksums. Runtime Java 11 or newer is required; Java 21 is recommended and not bundled.
- The release recorded 1,416 passing tests in both the source and packaged runs, with six filesystem-capability exclusions. Stable designation does not resolve the known issues or replace outstanding supervised platform/live-service checks; consult the published release notes for that version's limitations.

## Historical development entries (wording preserved; links repaired)

## 0.6.1-a9 (unpublished pre-release candidate)

This candidate consolidates the 0.6.1 alpha and local test-build work. See
[0.6.1-a9 release notes](https://github.com/Hatson321/Scarlet/releases/tag/0.6.1-a9) for current behavior and test limits.
Historical T entries below remain a record of intermediate behavior, not the final policy.

- Add console import progress and completion reporting; update optional avatar-provider support.
- Join the newest eligible active group instance at startup, support age-gated join links, and keep optional avatar/profile work off the live roster path. Add toggleable small Discord join/leave thumbnails.
- Restore Staff Mode watched-avatar/vote-to-kick eligibility without requiring a configured group; improve speech fallback, advisories and first-run prompts.
- Add a persistent Windows rendering-compatibility setting.
- Improve client tracking, shutdown diagnostics and bounded retries. Closed/empty exit now relies on a matching closure audit plus healthy local logs rather than API occupancy. A sole verified manually launched client is eligible when the exit toggle is enabled; multiple clients block it.
- Add opt-in bounded startup recovery, explicit repeated-connection-failure detection and separate Windows force-close opt-ins for startup and closed/empty exit. Normal close and exact-process safety checks remain first.
- Correct current shutdown documentation and synchronize runtime, build and launcher versions to lowercase `0.6.1-a9`. Add regression coverage for earlier alpha upgrades, numeric alpha ordering, stable-release precedence and Windows build acceptance. Do not change the published update feed or announcement.
- Make the Linux rendering-metadata regression account for Java's own font-cache initialization before taking its unchanged-data baseline; retain the complete settings/credentials filesystem comparison.

## 0.6.1-T10 (local test build)

- Startup recovery also detects three live, separately spaced region-connection failures in the Disconnecting state, each paired with VRChat's explicit network-region game-flow reset. Ordinary log activity no longer hides this specific failed-startup loop. Requires three cycles within two minutes, at least 20 seconds apart, with the latest within 45 seconds. Historical catch-up, duplicated entries and unrelated errors do not count.
- Keep the existing recovery opt-in, at least three minutes per startup, a ten-minute watch per attempt and at most two replacements. Require a new current-launch log, expected account authentication, healthy caught-up tracking and fresh loop evidence at close dispatch. Connection success clears failure evidence; world joins, account/context changes or a changed tracked log session prevent recovery.
- Reuse the existing exact Scarlet-owned process, normal-close-first transport, separately enabled Windows force fallback, verified exit before replacement, active-target authorization and fresh secure join lookup. No blanket process killing or adoption of manually launched clients for startup recovery. Closed/empty-instance shutdown is unchanged.

## 0.6.1-T9 (local test build)

- The existing closed/empty-instance exit toggle now permits shutdown of the only verified running VRChat client, including a manually launched client or one whose Scarlet launch ownership was not established. The setting remains off by default; its saved key is unchanged. Startup recovery still requires Scarlet launch ownership and cannot use this shutdown-only receipt.
- Retain the matching current-session closure audit, healthy caught-up live logs, known self with no other players, full 30-second grace, same Windows/Linux user, exact process identity and fresh single-client rechecks at dispatch. Multiple clients, inaccessible identity or ambiguous inventory prevent shutdown. No instance API occupancy request is restored.
- Multiple running clients produce a rate-limited warning in the configured ops-alert channel when the monitored instance is closed and locally empty. No action button can bypass this ambiguity. Notifications contain no join links or player identities.
- Windows shutdown inventory reads the full executable-name inventory but requests detailed identity only for VRChat candidates, avoiding unrelated process churn. Normal close remains first; forced termination still requires the separate existing opt-in and its bounded fresh checks.

## 0.6.1-T8 (local test build)

- Automatic VRChat client shutdown now uses Scarlet's healthy, caught-up output-log roster instead of making a fresh instance API request. Stale server player counts, missing instance responses and occupancy retry backoff no longer delay this shutdown path. This deliberately removes independent server occupancy confirmation; it is not a claim that local logs are infallible.
- Keep the matching group-instance close audit, current log session, verified Scarlet-owned client, sole-self roster, full 30-second healthy grace, final tracking/identity checks and bounded normal-close retries. An empty but still-open instance is not enough. Closure audit delivery can still be delayed.
- The separate opt-in Windows force-close fallback also uses fresh local tracking checks rather than the instance API, after at least 15 seconds for normal exit. Player arrivals, changed sessions, unhealthy logs or uncertain process identity still veto it. Old server-count override actions are inactive in this shutdown mode.
- Startup target authorization and idle server-instance closing retain their existing API checks. No publishing or live game/process actions during verification.

## 0.6.1-T7 (local test build)

- Add opt-in "VRChat Client: Recover stalled auto-launch". Watch only verified Scarlet-owned automatic launches, wait at least three minutes after each launch and two minutes without new log lines, and allow at most two replacement launches. Stop watching on any new world-join entry, a changed account/context, or a ten-minute observation limit per attempt. Ordinary later freezes/disconnects are not recovery triggers.
- Require a functioning, caught-up log reader (or a recently successful empty-directory scan); unreadable logs and parser failures are not evidence of a frozen client. Revalidate quiet-log evidence immediately before normal close. Never launch a second client while the original remains running.
- Reuse exact managed process ownership and verified quiet shutdown before replacement. Recheck the active target and obtain fresh secure join information after shutdown. Changed ownership, uncertain close or launch, settings/account changes and exhausted retries stop recovery. Explicit Windows close rejections permit only the separately enabled, guarded force fallback. All work runs on the existing serialized background launch worker.
- Add two separate, default-off Windows force-close opt-ins: stalled startup recovery, and closed/empty-instance shutdown. Allow 15 seconds for normal shutdown first. Forced termination uses an OS handle pinned to the verified Scarlet-owned process, never a name-based blanket kill; identity, settings and applicable startup/closed-empty evidence are checked again. Closed-empty fallback obtains a new server response and requires the same uninterrupted roster/session/grace observation; operator occupancy overrides cannot authorize force-close. One force opportunity only, with no replay after an uncertain result. Force-close may lose unsaved client state.
- Keep T6 server occupancy, log-health and process-ownership requirements. No publication or live game actions during verification.

## 0.6.1-T6 (local test build)

- Distinguish native close stages: window enumeration, no visible window, multiple visible windows, changed window/process identity, final safety veto, Windows access denial, rejected close message, and uncertain dispatch. Final guard diagnostics identify mode, group, session, operator permission, stale server proof or log-health failures. Recheck server-proof age after slow native process checks. Failed Windows posts include a numeric error only.
- Permit at most two additional automatic attempts after explicitly classified, definitely-unsent transient window/guard failures. Each retry starts a fresh full 30-second healthy grace period and requires a new server response and all process/session/roster checks. Audit replays and roster changes do not refill the retry budget. Sent, rejected, unknown or identity-changing outcomes remain terminal; no force-close fallback exists.
- Operator confirmations remain one-shot and never turn an occupancy override into an unattended retry. Preserve the existing server occupancy and log-health acceptance rules, permissions, API backoff and launch ownership checks.

## 0.6.1-T5 (local test build)

- Move potentially slow native process verification before bounded log catch-up and outside the roster lock. Reject session changes across that work; retain fresh health, full grace, final process identity and server proof requirements.
- Use a bounded, primary-account, no-cache instance read for client-exit proof, without the legacy world-instance fallback. Add rate-limited numeric count and cache-source diagnostics; existing logs cannot establish the cause of VRChat's inconsistent count.
- After at least five minutes of locally observed sole-self occupancy, report a fresh occupancy-only server disagreement to the configured operations channel. Reports expire after ten minutes and are bounded to one per ten minutes for the same observation. Any intervening player/session change invalidates the control.
- Add a separate `vrchat-client-exit-override` Other permission (owner/admin by default; explicitly grant to trusted staff). Two-step, actor-bound confirmation expires after sixty seconds. It may bypass only a fresh API occupancy disagreement to request normal exit of the same Scarlet-owned VRChat client. Closed-instance proof, healthy sole-self roster, full grace, operating mode and final ownership checks remain mandatory; no force-kill or instance mutation is performed. Results go to operations logging.

## 0.6.1-T4 (local test build)

- After a fresh server rejection involving only occupancy, retry after 15 seconds and then 30 seconds, before returning to 60-second intervals. Missing, invalid, stale or failed responses retain a 60-second backoff and consume the faster retry allowance for that session. Audit replays do not refill it; existing API-wide rate limiting remains in place.
- Allow a recently healthy log reader up to 1.5 seconds to complete an append, with the same two-second freshness limit and fresh health checks. Timestamp healthy loop completion after filesystem target discovery instead of before it. Add fixed, rate-limited tracking failure reasons to distinguish unfinished reads, stale heartbeats and invalidated sessions.
- Preserve the complete 30-second grace period, exact session/process ownership, server closure and sole-self checks, final revalidation and graceful-only exit. Genuine tracking failures still reset the countdown. Live-device timing needs retesting; no report or publication was requested.

## 0.6.1-T3 (local test build)

- Added exact, rate-limited automatic-exit server rejection diagnostics for response availability, ID/world/instance matching, closure timestamp and individual occupancy fields. Multiple failing fields are reported without exposing account IDs, instance links or raw response bodies.
- Kept the server acceptance predicate, process/session checks, log health, 30-second grace, 60-second server retry backoff and graceful-only exit behavior unchanged. Diagnostics do not make additional API requests or authorize a close.

## 0.6.1-T2 (local test build)

- Before automatic client-exit checks, allow a recently healthy log reader up to 500 ms to finish an ordinary append instead of immediately resetting the 30-second countdown. Waiting happens outside the roster lock; fresh health, exact session/process ownership, roster, closure and server checks remain required. Stale, failed or incomplete tracking still blocks exit and resets the grace period.
- Suppress group-audit permission, missing-group and Discord moderation opt-in startup prompts in Staff Mode. Group-bot permission checks remain enabled, and Staff Mode does not consume the Discord opt-in preference.

## 0.6.1-T1 (local test build)

- Added privacy-safe, rate-limited automatic VRChat exit diagnostics without changing the ownership, current-session, closed/empty, grace-period or graceful-exit safeguards.
- Bounded speech synthesis waits and pending work, handled failed/null/cancelled voices with distinct bounded fallback attempts, and saved fallback selections only after successful synthesis. A still-busy native provider is not given more queued requests; restart Scarlet if the provider remains stuck.
- Separated watched-avatar matching from optional avatar-detail hydration. Refreshed present-player advisories after catch-up/provider changes without replaying historical speech, and stopped rejoin checks from trusting an avatar retained from an earlier visit.
- Preserved avatar-provider selections independently of consent, warned on empty selections, and corrected the consent setting documentation. Existing consent, Advisory/TTS switches, silent flags, departed-player guards and Staff Mode group isolation remain enforced.
- Recognized uppercase T builds as previews, ordered them numerically after the prior A builds and before stable 0.6.1. No update feed or announcement was changed.

## 0.5.0 (prepared locally; not yet published)

See [0.5.0 release notes](https://github.com/hatson321/Scarlet/blob/0355d732200204485efe979ee36ae8245456b247/RELEASE_NOTES_0.5.0.md) for the cumulative desktop maintenance changes and remaining limitations. The build, runtime fallback, local update metadata, and launcher version marker now agree on `0.5.0`; historical release entries below keep their original numbers.

### Fixed

- Restored the configured-template autofill link on the moderation Report button alongside the copy-and-paste report and simple notes/tags link. Both template outputs now share the same content and footer setting, and an empty template falls back to the event details. Oversized reports and URLs are preserved in private text attachments. Removed the obsolete warning about filling the audit actor's requester ID, which the current form URL does not do. Help Desk sign-in, evidence requirements, and manual submission still apply.

### Removed

- Completely removed the Android/mobile companion feature from active Scarlet and future build/distribution paths. This includes the companion application, desktop mobile-delivery subsystem, phone pairing and device state, direct-LAN listener, FCM/OAuth delivery, mobile settings/UI/status hooks, Android/mobile packaging, and the previously removed maintainer-operated relay implementations. Historical release/acquisition artifacts and quarantined relay evidence remain inert and must not be distributed as future builds.

> Mobile and Android entries under older release headings below are retained as historical records of what those releases contained; they do not describe the active product.

## 0.4.20

Adds **VRChat trust ranks** to the player list with Nuisance/Visitor advisories and callouts, makes the **follow-into-new-instance** feature react in about a third of the time, and reworks the **advisory colors** so they read cleanly on a dark theme and over a Discord screenshare.

### Added

- **PowerPC support (experimental, heavily untested).** A separate PowerPC edition of the jar (`scarlet-<version>-ppc.jar`, built alongside the normal one) runs Scarlet on 32-bit PowerPC Linux. So far it has been run on exactly one machine: a Nintendo Wii running Wii-Linux. It bundles a PowerPC build of the Discord voice (DAVE) library, and because these CPUs have no Java JIT it trims work to keep up: startup security self-tests are skipped, Discord event types Scarlet never uses aren't requested, and the local credential store uses fewer key-derivation rounds (its key is a random key file, so this doesn't weaken it; secrets saved by the PowerPC edition can only be read by the PowerPC edition). Expect it to be slow and to have rough edges; reports are welcome. The normal jar is unchanged and doesn't include any PowerPC files.
- **avtrDB is back as an avatar-search provider.** It had been dropped because it was rejecting requests from datacenter IPs and VPNs; its maintainer now lets requests identified as Scarlet through, so it's the sixth text-search provider again alongside nekosunevr, VRCDB, WorldBalancer, PAW and KitsuneDB. It's used for avatar name lookups only; "search by picture" keeps the rebuilt method. Existing installs using the default provider list get it automatically; installs with a custom list can tick it in Settings → avatar search providers.
- **Moderation-tag browser.** A direct, ephemeral list of all moderation tags, split into up to five Discord-supported 25-option menus with the entry's existing tags preselected. This is now what **Edit tags** opens by default (see Fixed; the separate Browse tags button is folded into it). Adapted from [Chloethecat's Scarlet fork](https://github.com/Chloethecat/Scarlet).
- **Desktop GitHub Actions build.** Relevant pushes to `main` now build vendored libdave and Scarlet on Java 21, then retain the desktop JAR as a 30-day workflow artifact. Contributed by [Chloethecat](https://github.com/Chloethecat).
- **Trust ranks in the player list.** A new **Rank** column shows each player's VRChat trust rank — Visitor, New User, User, Known, Trusted, plus **Nuisance** for troll-flagged accounts — derived from the account's own tags with no extra API calls. The rank is coloured in **VRChat's own trust-rank colours**, and that colour stays in the Rank column only — it never changes how the rest of the row is coloured or logged (watched-group, community, advisory colours are untouched). VRChat only returns a full tag set for some users, so it's best-effort, but correct the large majority of the time.
- **Nuisance-rank alert.** A troll-flagged (Nuisance) account joining now raises an advisory (it sorts to the top of the list) **and** plays the bundled **siren-chirp** alert sound followed by a spoken “Nuisance rank” callout. On by default; the sound ships inside the build (extracted to a scratch temp file for playback, never the data folder), so there's nothing to install. Toggles: `advisory_flag_nuisance_rank`, `tts_announce_nuisance_rank`.
- **Visitor-rank advisory (opt-in).** For owners who want to keep an eye out for brand-new Visitor-rank accounts: an optional row advisory and an optional spoken callout, both **off by default** (`advisory_flag_visitor_rank`, `tts_announce_visitor_rank`). Visitors are usually fine, so this is there if you want it and silent if you don't.
- **Report button now gives a copy-paste report, not just a sign-in link.** VRChat moved general reporting in-app and restricted the Help Desk form to appeals and evidence-backed reports (and it requires signing in), so the old pre-filled Help Desk links mostly hit a login wall or get auto-closed. The `vrchat-report` button now outputs a **plain-text, copy-paste report block** (target, actor, reason, internal tags, group/audit IDs) for a signed-in moderator to paste straight into VRChat's in-app report. The Help Desk link is kept but labelled for its now-narrow use (appeals / evidence). No automated/bot-account reporting — a human still files it.
- **Report a user's profile picture / user icon to VRChat.** Moderation embeds now carry pre-filled VRChat T&S report links for a user's **profile picture** and **user icon** — the two account-level content types that had a report-URL builder but were never surfaced anywhere. (Sticker, emoji, print and prop reports were already offered on their in-instance spawn embeds.) As with everything else, Scarlet only assembles the pre-filled report; a human files it with VRChat.
- **Bundled alert SFX set.** The `BL_SFX_*` sound effects are rendered to WAV and bundled in the build so they play on every audio route — local player, Java Sound, and the Discord-voice output — with no MP3 decoder dependency. SIREN_CHIRP drives the nuisance alarm today; the rest are ready to wire to their events.
- **Maintainer and release-process disclosure.** scarlet is now under a new maintainer hatson321 ai is used to assist in the api updates and gets proof read by hatson321 and the other scarlet admin team members before release. tests are performed as well before release to ensure safe operational status and a fully detailed update html is provided disclosing what was changed and tested. The notice is shown once; headless or scheduled runs log it once and continue without blocking on a dialog.

### Changed

- **Follow-into-new-instance reacts far faster (~90s → ~30s).** The auto-launch used to wait for the group **audit log**, which is bounded both by the poll interval and by VRChat's own audit-ingest lag. When *Launch on Instance Create* is enabled, Scarlet now also watches the group's live open-instance list on a short, self-limiting cadence and cold-boots the client the moment a new instance appears. It stays gentle on the API — one lightweight call at a tunable interval (`instance_follow_fast_poll_seconds`, default 30s, 15s floor), only while the feature is on, backing off a full minute on any error or rate-limit — and it's deduplicated with the audit path so an instance is never launched into twice.
- **Advisory colours reworked for readability.** The player-list advisory colours were raw AWT primaries (harsh, and rough over a Discord screenshare). They're retuned to a calmer, higher-legibility set that reads the same at a glance but is easier on the eyes. Trust-rank colours are separate: they live in the Rank column (VRChat's codes) and don't feed the row colour. UI-only; Discord embed colours are unchanged.
- **Reverse-image avatar lookup rebuilt without a keyed provider.** "Search by picture" used to depend solely on avtrDB. When this release was prepared, its endpoint was rejecting Scarlet's datacenter-hosted requests, so it is rebuilt from pieces Scarlet already trusts: the image's file ID is resolved to its owner via VRChat's own (authenticated) file API, each remaining provider is queried by author (the standard VRCX `authorId` lookup), and only the avatar whose image matches that file ID is kept. No new API key, no new third-party dependency. Author-lookup failures are tracked separately from text search, so a provider that doesn't support author lookup can't disable its text search. If the owner can't be resolved or no provider indexes them, it returns no match and falls back to name search, as before.

### Fixed

- **Tagging kicks, bans and warns works again — tag search was refused for everyone in 0.4.19.** Scarlet only lets a Discord button, menu or pop-up through if its ID is on a built-in default allow-list (otherwise an admin has to grant it with `/scarlet-discord-permissions`). The tag search added in 0.4.19 introduced two new IDs — the search pop-up (`tag-search`) and its results menu (`select-tags-search`) — and neither was added to that list. So clicking **Edit tags**, typing a search and submitting was rejected for every moderator, and tagging looked completely broken. Both are now allowed by default. They still check that the person has ban-management / moderator permissions, so nobody new can tag entries. **If you are still on 0.4.19**, see *Tag search refused on 0.4.19* in the README for the one-time `/scarlet-discord-permissions` commands that unlock it until you update.
- **Timed ban, group join-request (Accept / Reject / Block) and watched-remove were refused by default too.** Same cause as above: the timed-ban buttons and custom-duration pop-up (`timed-ban`, `timed-ban-custom`), the join-request buttons (`group-request-accept`, `group-request-reject`, `group-request-block`) and the watched-remove menu (`watched-remove`) were never on the default allow-list, so they only worked for servers where an admin had granted them by hand. All are now allowed by default; each still enforces its own check (ban management for timed bans, invite management for join requests, and the watched-remove menu is only ever shown privately to whoever ran the command).
- **Tagging reworked: dropdowns are back, and `/tag` searches every tag as you type.** **Edit tags** once again opens every tag in dropdowns with descriptions, like before 0.4.19 (Discord allows 25 per dropdown, so tags are split across up to five; each dropdown filters as you type in it). The separate Browse tags button is folded into Edit tags (it still works on older posts). New **`/tag add`** and **`/tag remove`**, run inside a moderation event's thread, autocomplete live across *all* tags — label, id and description, typo-tolerant — so typing "harass" shows only the harassment tags no matter how many tags exist. Up to five tags per command; each option skips tags already picked in the others. `/tag` is usable by moderators by default (it checks moderator access itself). Tag descriptions in the dropdowns now use Discord's full 100 characters (previously cut at 50). The 0.4.19 search pop-up is retired.
- **Staff can tag without extra setup: if your role can post in the moderation thread, you can tag.** Tagging and descriptions required ban-level access (VRChat ban management, `groupex-bans-manage`, Discord Timeout Members or Manage Server), so instance hosts who warn and kick but can't ban got "You do not have permission to modify audit event moderation state." on their own kicks. Tagging and descriptions (Edit tags, `/tag`, Edit description) are now allowed for anyone whose Discord role can **send messages in the moderation thread** — so it follows the server's existing staff role and channel permissions, with nothing to grant in Scarlet. Also allowed: everything that worked before, the moderator who performed the action (via their linked VRChat account), anyone with VRChat instance-moderation permission in the group, and a new `groupex-tags-edit` override. Read-only viewers of a moderation channel still can't tag, and redacting events still requires full moderation access. The permission-denied message now says what would let the person tag.
- **Coming back online no longer means hours of stale catch-up.** After downtime, Scarlet walked the missed audit log forward one day per poll (once a minute) with no limit, so an install that had been idle for months spent hours replaying old events before live kicks/bans/warns posted again. Catch-up now goes back at most `audit_catchup_max_days` (new setting, default 14, 1-90; older history is skipped with a log warning) and polls at the 10-second burst rate while behind, so a full two-week backlog clears in a few minutes.
- **New slash commands register without a version bump.** Scarlet only synced its commands with Discord when the version number changed, so a build that added a command under the same version (like `/tag` during testing) never showed it. It now reconciles on every start; only new or changed commands are sent.
- **Training simulator can now fire moderator warn / kick / ban events.** Previously the simulator only produced player joins, advisories and vote-to-kicks, so there was no way to get a moderation post with **Edit tags** on it without a real moderation action. The new events run through the real moderation pipeline and post (to the training channel only) with every normal button, so tagging, tag search, Browse tags, descriptions and timed bans can be tested from a non-admin moderator account on any server, however small. Ban / Unban / Timed ban on a training user perform the real permission checks, then answer with a simulated result and never call VRChat.
- **Startup warning for any interaction left off the allow-list.** Scarlet now logs a warning at startup naming any registered button, menu or pop-up that isn't on the default allow-list, so this class of bug shows up in the log on the first launch of a build instead of in production. A correct build logs none.
- **A tag with a label over 100 characters no longer breaks the tag menus.** Discord caps menu option labels at 100 characters; a single longer label made the whole Edit tags / Browse tags menu fail to appear. Labels are now shortened with "…".
- **Tag search: the "no permission" reply is sent correctly.** The search pop-up's permission-denied message was sent through the wrong channel after the interaction had already been acknowledged, so it errored instead of showing.
- **VRChat current-user responses no longer fail when nested avatar tags are arrays.** VRChat now returns `presence.currentAvatarTags` as an array in some responses while `vrchatapi-java` 1.21.0 expects a string. Scarlet normalizes only that mismatched nested field before deserialization. Adapted from [Chloethecat's Scarlet fork](https://github.com/Chloethecat/Scarlet).
- **"Edit tags" and "Manager notes" buttons open again.** Both handlers built a modal but never `.queue()`d it, so the modal was never sent and the interaction went unacknowledged — the button just showed "interaction failed" (the repeated "did not acknowledge" warnings in the logs). This was universal, not platform-specific. Both now queue the reply.
- **Moderation logs and report data no longer silently vanish when VRChat's API is flaky.** The moderation embed dereferenced the target user in several unguarded spots (title, image, join date, pronouns, status), so whenever the API failed to fully resolve a user — rate-limited, private profile, or a transient error — the whole embed threw and the moderation event never posted (and neither did its report links). This was the inconsistent "some of the report data won't appear." Every user field is now null-guarded and falls back to the audit entry's own IDs, so the log and the pre-filled report links always post with whatever data is available (missing fields are simply omitted, with a note). Same hardening for a null actor.
- **Outstanding-moderation re-ping actually works now.** The feature that reminds a moderator when they log a ban/kick/removal without a reason (tags or description) had never been wired into the run loop — it simply never fired — and its "outstanding" test was inverted (`!tags || description`), so even when reachable it flagged fully-logged actions and missed un-logged ones. Fixed both (now `no tags AND no description`, and it skips redacted and ban-bundled auto-kicks), plus a dead date-loop it shared with the mod-summary scheduler. **A message a moderator posts in the moderation thread now counts as a valid reason**: the first such message is captured as the entry's description, so the re-ping clears. (Requires an "Outstanding Moderation" channel to be configured and the relevant *Ping on outstanding…* toggles enabled.)
- **Bans no longer show up as standalone kicks.** When you ban a user who's in an instance, VRChat auto-issues an instance-kick alongside the ban; Scarlet bundles that kick under the ban instead of logging it separately. A long-standing inverted date comparison (going back to the legacy builds) made the “most recent prior action” resolve to a user's *oldest* action, so for anyone with any moderation history the co-occurring ban was missed and its auto-kick was posted as a normal kick. Fixed the comparison; the same field also drove the embed's “Most recent” line, which was showing the oldest action rather than the newest.
- **Headless: the multiple-data-folder prompt can no longer hang a service.** When Scarlet finds more than one data folder and there's no interactive console, it no longer opens a blocking read on stdin — it logs the folders and deterministically loads the first, so a headless bot never stalls at startup.
- **A down or gated avatar-search provider no longer floods the console.** A provider that fails — DNS-dead, HTTP 403/404, timed out, or connection refused — now logs **one** warning when it first goes down and **one** when Scarlet gives up on it, then stays quiet; the stack traces for these expected network failures are dropped from WARN/DEBUG (TRACE only). Every failure now backs off, and the backoff **escalates** with each repeat (up to ~2h), so a permanently-down provider is retried a couple of times an hour at most instead of on every single search, and it auto-recovers (with a one-line note) the moment it responds again. This now also covers HTTP **4xx/5xx** responses, which previously slipped past the backoff and retried every search — e.g. a provider that starts returning **403** (as avtrDB's search did, before it was removed) used to dump a stack trace on every single lookup.
- **ARM/glibc Discord voice (DAVE): groundwork + two protocol-version bugs.** On ARM/Termux, Discord voice can join/leave-thrash when the native DAVE library falls back to the JNA path. Two `(short) transitionId` casts that should have used the negotiated MLS protocol version were corrected, and the DAVE handshake path (`DAudioDaveSession`) now logs each epoch/transition step so a real ARM voice session can pinpoint where the handshake fails. This entry originally referred to a separate Android artifact; that artifact has since been removed and is no longer distributed or supported. (Groundwork — the underlying glibc-aarch64 fallback still needs a test log from an affected user to close out.)

### Removed

- **Dropped the dead `vrcx.avtr.zip` avatar-search provider.** Its hostname no longer resolves (NXDOMAIN), so every lookup could only ever throw `UnknownHostException` — it was the single largest source of avatar-search log spam.
- **avtrDB no longer powers "search by picture".** Its endpoint had been rejecting requests from datacenter IPs and likely VPNs, so reverse-image search was rebuilt without it (see Changed). **avtrDB is back for avatar name search** (see Added).

### Notes

- Trust rank is inferred from VRChat account tags, which VRChat only populates fully for some users — treat the Rank column as a strong hint, not a guarantee.
- Scarlet's avatar search (text, author, and reverse-image) is built on the **VRCX avatar-search provider format** — the query convention (`?search=` / `?authorId=` / `?fileId=`) and the community provider ecosystem [VRCX](https://github.com/vrcx-team/VRCX) established — and the reverse-image rebuild follows VRCX's own documented file-ID → author fallback. Scarlet implements that format itself; it does not bundle or import VRCX's code.

## 0.4.19

The largest maintenance release in a while: keeps Scarlet working against VRChat's current API, **restores the avatar images on moderation messages**, makes settings and moderation state **crash-safe to write**, adds a **verified-user notification**, and lands a large batch of reliability, correctness, and security fixes from a full codebase audit. Stays on stable VRChat API **1.21.0** (the newest nightly adds nothing Scarlet uses).

### Added

- **Verified-user notifications.** When a member passes age verification and is auto-invited to the group, Scarlet now posts an "Age Verification Complete" entry to the staff action-log channel — including the member's real VRChat age-verification status — **and** a confirmation in the member's ticket. Fires once for both the verified-role and self-link paths; new setting, on by default. (Previously, members who earned the verified role directly got no acknowledgement.)
- **Crash-safe data persistence across the board.** The fsync + temp-then-atomic-rename + recover-from-backup protection `settings.json` already had now covers timed bans / pending actions, the staff and secret-staff lists, the Discord↔VRChat link map and per-user / per-audit metadata, the event calendar, the report template, and Discord permissions. A hard crash or power loss mid-write no longer drops a ban timer, staff roster, or moderation link; a truncated file is recovered from a backup on next load. (Verified by hard-crashing the machine mid-edit.)
- **Idle-instance auto-close.** New opt-in setting (`auto_close_bot_instance_minutes`, default off) that closes the bot's *own* VRChat instance once everyone else has left and a timer elapses — for hosts who forget to close up or step away. Uses Scarlet's live in-instance presence (not the group-instance API), resets while anyone else is present, re-checks at expiry, stops if a host/mod closes the instance first, and only ever touches the instance the bot is in.
- **Multiple-data-folder picker.** When more than one data folder holds a saved config, Scarlet asks at startup which to load instead of silently adopting one (headless/CLI fallback included); the scan also covers a bare `~/Scarlet` from older layouts. `SCARLET_HOME` still overrides discovery entirely.

### Changed

- **Tracks VRChat API `1.21.0`.** Bios and the user image fields moved to the separate `PublicProfile` response upstream; group lookup/calls/gallery moved to new signatures. A build on the older client can no longer read VRChat correctly.
- **The API-update checker only flags stable releases now** — it ignores the community client's `-nightly.N` pre-releases (which you don't want running a live bot), and speaks up only when an actual stable ships.

### Fixed

- **Moderation avatar images restored.** VRChat moved `currentAvatarImageUrl` / `iconUrl` / `userIcon` off `User` onto `PublicProfile`; Scarlet was reading blanks and falling back to the robot on warns/kicks/bans. It now resolves the image from the (cached) public profile — full image → thumbnail → icon URL → user icon → robot only if all blank — and the same resolver feeds the pedestal-report and user-info embeds.
- **Clicking Invite no longer unbans the target** (a stray duplicated code path issued an unban and blocked the UI on two REST calls).
- **`actor-moderation-summary` now redacts** (same `shouldRedact` gate as the query-history commands) so it no longer leaks another protected actor's counts.
- **Moderation-command reliability:** null-user hangs on add-role/remove-role/transfer-check/transfer-start/actor-summary; the immediate-ban tag menu and the enum-setting editor no longer throw past Discord's 25-option cap; the multi-ban/unban modals open again (invalid length bound); the busy-instance monitor embed updates again; the inverted blacklist enforcement; configured audit-type colors no longer wiped on save; two-level Discord config now loads; missing `return`s that bypassed the age-gate permission check and the empty-tags guard; pedestal reports classified correctly (missing `switch` breaks); boolean checkboxes reflect saved state and a wrongly-typed setting is skipped, not crashed.
- **Stability / null-safety:** empty or corrupt JSON data files (staff, secret staff, watched entities, watched groups, permissions) load cleanly; the audit poll tolerates missing `hasNext`/`results`; unknown audit titles render as text not `[C@…`; NPE guards for unresolved-group permission checks, missing join dates, calendar close-after-end, and the redaction path; the data-directory discovery finds a working location instead of nulling out; closing a URL-input dialog with no input no longer freezes the UI on Linux/X11.
- **Security / hardening:** NAT64 (`64:ff9b::/96`) addresses rejected by the public-URL guard; log-export path-traversal hardening (anchored match + canonical containment); size-capped streaming reads of untrusted URLs.
- **Platform / process:** IPC socket stops at EOF (Unix-domain commands match again); empty Windows VRChat registry value no longer throws; unset `PATH` no longer NPEs a static initializer; Linux TTS installer terminal fallback (`xterm -e sh -c`), Solus/Clear Linux detection (`test -d`), and timeouts on the package-search/command-check subprocesses.
- **Smaller:** avatar-search author provider (stray literal, URL-encoding, malformed-row skipping); `volatile` User-Agent statics; iOS-vs-PC avatar-rating copy-paste; tag-rename autocomplete refresh; report-tag separators; GitHub release parsing; `:`-less location parsing; UTF-8 JSON read; an option helper honoring `required`.
- **Linux TTS:** dropped the unreliable **Mimic** and **Festival** engines — Festival was registering an error message as ~22 bogus "voices", and Mimic (a Mimic-3 binary) failed on the Mimic-1 flags Scarlet sends; both only ever fell back to a working voice anyway. The Linux voice list is now Flite / Pico / eSpeak(-NG) only, and each Flite-family engine is probed at startup so a broken binary never offers a voice that can't speak.

- **ARM/glibc Discord voice (DAVE) groundwork.** On a glibc arm64 box (e.g. a Raspberry Pi running the desktop jar) there is no native `libdave-jvm`, so voice falls back to the JNA `DAudioDaveSession` path — which currently mis-handshakes and makes the bot thrash (join/leave) in voice. That fallback (only ever active on such platforms) is now heavily logged at each DAVE handshake step, with the MLS-init failure surfaced at error level, so the next ARM voice attempt pinpoints where E2EE dies. Also fixed the two casts the author had flagged `// smells sus` (a transition id was being passed where a protocol version is expected on the failure-recovery paths). This historical entry previously directed Termux/Android users to a separate build; that build has since been removed and is no longer distributed or supported.

### Notes

- **Left as-is by design:** the Discord ban/kick hierarchy check (that's how Discord works).
- **Reviewed and deferred:** the JSON cache holding its map lock across a network fetch, a few EDT/threading hot-spots, the logging shutdown-drain, and a mangled image-asset filename regex.

## 0.4.18-b4

A second VRChat API update in quick succession. Unlike the last one this could not be absorbed by a dependency bump alone — VRChat renamed a field Scarlet reads, so this build carries an actual source change.

### Changed

- **Updated `vrchatapi-java` to `1.20.9`** (from `1.20.9-nightly.5`). Upstream renamed `GroupPost.roleId` to `roleIds`, so a build pinned to the old client can no longer read group post roles correctly, and the current client will not compile against the old call. This is the reason the release exists. (For the record, `1.20.9` is the newest tag — the `1.20.9-nightly.N` tags that version-sort after it were all cut *before* it.)
- **Group announcement embeds now show visibility, targeted roles, and editor.** `GroupAnnouncement` gained `visibility`, `roleIds` and `editorId` in this API version, so announcements now display the same detail group posts already did. The editor is only shown when it differs from the author, so an unedited announcement does not carry a redundant duplicate field. Role IDs resolve to role names where Scarlet has them cached and fall back to the raw ID where it does not.

### Notes

- Upstream also removed `User.username` in this version. Scarlet already used `displayName` throughout and is unaffected — noted only in case anyone building an older fork hits it.
- Nothing else in Scarlet's API surface was affected. Of the 74 VRChat API types Scarlet references, only `GroupPost` and `User` lost members; every other change was additive.

## 0.4.18-b3

A VRChat API update forced an early release; alongside it this build adds timed group bans and automatic evidence capture, and fixes a recurring secure-store corruption.

### Added

- **Consolidated per-user moderation log (`/moderation-log`).** When a single user has accumulated a pile of separate moderation posts, a staff member can run `/moderation-log user:<vrchat user>` and Scarlet spins up **one consolidated thread**: it opens with a header tallying the totals (**warns, mutes, instance kicks, group removals, bans, unbans**), then lists every action newest-first, each linking back to its original Discord post, plus a note if the user currently has an active timed ban. How far back it reaches is configurable at **Settings → Moderation → "Moderation log: include history from the past N days"** (0 = all recorded history). It reads Scarlet's own per-user audit index — no trawling of Discord history — so it works as far back as Scarlet has logged, and skips redacted or already-bundled entries. (If it's run somewhere a thread can't be created, it falls back to an inline reply.)
- **Timed group bans — ban now, auto-unban later.** Based on a community contribution (reworked to fit the current codebase). Each moderation post's action buttons now include a **Timed ban** row: **6h / 24h / 3d / 7d** one-click options plus **Timed ban…**, which opens a modal accepting a flexible duration (`30m`, `6h`, `3d`, `2w`, or a plain number of hours). Clicking one bans the user immediately and records an expiry; if the user is *already* banned it simply attaches the timer, converting a standing ban into a timed one. A background sweep (every 60 s) lifts expired bans, **attributing the unban to the moderator who set it**. Timers are persisted in `pending_moderation_actions.json`, so a 7-day ban survives restarts. The sweep is deliberately fail-safe: if VRChat's membership lookup returns nothing or the unban call fails, the record is left in place to retry on the next tick — a transient API hiccup can never strand a user as banned or silently drop the timer — and if the ban was lifted manually in the meantime, the moot timer is cleaned up. All actions require the same group ban-management permission as a normal ban. The button row can be turned off at **Settings → Moderation → "show timed-ban buttons on moderation posts"** (on by default); with it off the row is hidden and any stale button gives an ephemeral "disabled" reply. The sweep still finishes any timers that were already set. *(The originally submitted code targeted 0.4.15 and, as written, would have overwritten the richer `ScarletPendingModActions`, never actually unbanned anyone (a placeholder where the unban call belonged), lost its timers on restart, treated a failed status lookup as "unbanned," and shipped without the button handlers or a scheduler; all of that was fixed in this integration.)*
- **Automatic evidence capture — trigger OBS/Medal on a kick or ban (à la BanLogger).** New **Settings → Evidence** options: enable "trigger capture hotkey on kick/ban" and set the hotkey combo you bound in your capture tool (e.g. `CTRL+SHIFT+F9`, or just `F9`). When a `group.instance.kick`, `group.member.remove`, or `group.user.ban` audit event is detected, Scarlet synthesizes that key press for 250 ms via an OS-level `Robot`, so a tool listening for a *global* replay-buffer/clip hotkey captures the moment even when it isn't focused. Off by default; best-effort (it logs and moves on if the key can't be sent or the environment is headless), and never blocks moderation handling. Firing a ban/kick from the debug Event console exercises it too.

### Fixed

- **Recurring secure-store corruption (`AEADBadTagException: Tag mismatch`).** On Windows, credentials lived only in the registry-backed Java Preferences store, which periodically corrupts (antivirus locking `NTUSER.DAT`, roaming/synced profiles, OneDrive-redirected AppData, unclean shutdowns) — and a single failed read of the internal key record used to silently reseed and orphan *every* stored secret at once. Scarlet now keeps a redundant encrypted copy of each secret in a file in the data folder: every write goes to both stores, and a read falls back to the file only when the registry copy is missing or fails its AES-GCM integrity check, then reconstructs the bad copy from the verified-good one. It never destroys data it can't verify — the silent reseed is gone, a full snapshot is written before any forced reseed, and unreadable bytes are quarantined to a timestamped file rather than overwritten. Two CLI commands are included: `repair-secure-store` reconciles the two stores and prints a report, and `repair-secure-store cutover` (after a clean report) snapshots the registry and then empties it so the data-folder file becomes the primary store. Registry-only installs and the security regression tests are unchanged.

### Changed

- **Updated `vrchatapi-java` to `1.20.9-nightly.5`** (from `1.20.8-nightly.15`). VRChat shipped an API change that the previous build no longer tracks cleanly, so this release exists to pick it up. It otherwise carries the same changes as 0.4.18-b2 (below).

## 0.4.18-b2

Full localization (with Russian), moderator training mode, and reliability watchdogs.

### Added

- **Debug build (`scarlet-<ver>-debug.jar`) for testing features without real setup.** `mvnd clean package` now produces a third artifact alongside the desktop and Android jars: a debug edition whose manifest carries `Scarlet-Debug=true`. Only that jar (or launching any build with `-Dscarlet.debug=true`) unlocks an in-app **Debug menu** — release jars never carry the marker, so debug tooling can't show up in a normal build. A debug build **starts offline by default** — it skips the VRChat sign-in prompt so the desktop UI comes up with no login and no Discord bot (that's the whole point); pass `-Dscarlet.debug.offline=false` to sign in for real. The menu includes an **Event console** that can fire **every** event — the player/instance simulations *and* every group-audit type (bans, kicks, member removes, role changes, join requests, posts, updates, …) — through Scarlet's real processing pipeline (`ScarletDiscord.process`), and **mirrors what each event would post to Discord into the console window**, so a tester sees the output with **no Discord bot required**. Player/instance events work fully offline; audit events that resolve VRChat users get the fullest fidelity with a sign-in (`-Dscarlet.debug.offline=false`, still no bot). It also lets a tester **inject sample watched data** (fake groups/users/avatars with unmistakable `*_debug-` ids) to exercise the lists and the new remove-menu. It's built every time but doesn't have to be shipped — hand it out when someone wants to help test.
- **Bulk-prune watch lists from a dropdown (`/watched-avatar remove-menu`, and the same for `watched-user` / `watched-group`).** Cleaning stale entries out of a big list — deleted or banned avatars especially — used to mean one `/…-remove <id>` per entry. The new **remove-menu** subcommand posts multi-select dropdowns of the watched entries **by resolved name** (falling back to the id when a name can't be looked up, e.g. a deleted avatar), so a moderator ticks the dead ones and removes them all in a single action. Discord caps a dropdown at 25 options and a message at five rows, so up to 125 entries are shown at once; with more than that, remove some and run it again for the rest. It's a self-contained command that doesn't alter the existing `list` output, and it reuses each list's own removal path (so removals persist exactly like `/…-remove`).
- **Accept / Reject / Block a group join request straight from Discord.** When your group requires approval to join, VRChat emits a "Created Request" audit event that Scarlet already posts to the moderation channel. That post now carries three buttons — **Accept** (green), **Reject** (grey), **Block** (red) — so a moderator can action the request without opening VRChat. Accept approves the join, Reject declines it, and Block declines and blocks the user from requesting again. Each button requires the clicking moderator to have their VRChat account linked and the group's invite-management permission (and the bot's own account to have it too), the same gate as `/list-join-requests`; unauthorized clicks get an ephemeral refusal. Groups that don't gate joins never produce these events, so nothing changes for them.
- **Import can leave channels behind (for moving a config to a different Discord server).** The migration import dialog has a new **"Carry over Discord channels"** option, on by default. Unchecking it blanks the imported audit/log channel routing after the restore, keeping the bot token, guild, roles and everything else — so a config can seed a new server without inheriting the old channels. The default keeps channels, so existing behaviour is unchanged and **legacy bundles (from before this feature) import exactly as before**. The same channel-scrub is what group cloning will reuse.
- **Multi-language desktop client — all six shipped languages now complete.** Building on the 0.4.18-b1 foundation, the **entire desktop UI is translated** — splash, menu bar, every settings label/section header/enum value/theme-preset name, the instance-creation wizard, moderation confirmations and result popups, the avatar-stats panel, export/import and update dialogs, the status bar, and the spoken advisory/TTS callouts (663 message keys, verified 1:1 across **English, Russian, German, Japanese, Spanish and Indonesian** — no key missing in any of them). It follows the operating system language by default, with a `ui_language` override applied at startup. German, Japanese, Spanish and Indonesian are community-seeded with the remaining keys machine-drafted pending a native pass. A handful of desktop strings that were still hardcoded in English — the "Copy"/"Paste" context actions, the "Alternate credentials" list popup and a bundle-read error — were pulled into the translation system too, so they now follow the chosen language like everything else. The Discord bot still renders in English for now (its string migration is a separate pass), though each interaction already resolves against the invoking user's own Discord locale.
- **Translate shared advisory text into your own language (and restore it) — across every moderation data file that's safe to translate.** Watch lists are often shared between communities with their advisory text — the free-form prose a list author writes — in English, which is hard to act on quickly if English isn't your language. A new **Settings → Advisories** action translates that prose into your desktop UI language on request, and a companion action restores the English originals losslessly (originals are kept, so translate/restore is reversible and switching languages never compounds). It now covers **every data file with human-readable advisory text: watched groups, watched users, watched avatars (their `message`/`notes`), and moderation-tag descriptions** — one Translate button does them all. What it deliberately **never** touches, precisely so nothing breaks: group/user/avatar **names** (they come from VRChat live and aren't stored), ids, snowflakes, types, tags, enum values, a moderation tag's `value` key and its Discord command-choice `label`, and — critically — config files (`settings.json`, `discord_bot.json`, `mobile_devices.json`), id lists (`staff_list.json`), and the pronoun **detection** lists (`good_pronoun.json`/`bad_pronoun.json`), where translating the contents would break matching, routing, or sign-in. It **works out of the box with no setup** — by default it uses the free **MyMemory** translation API (no account, no API key; ~5,000 characters/day, or 50,000 if you drop an email in the optional field), so an average user just presses Translate. For privacy or heavier use you can instead set a **LibreTranslate** endpoint (self-host it locally so advisory text never leaves your machine, or point at any instance) and Scarlet uses that automatically; a scheme-less URL and WAF-picky mirrors are handled (it fills in `https://` and sends a browser User-Agent). Long advisories are split to fit MyMemory's per-request limit and reassembled. Also available as the `translate-advisories` / `restore-advisories` CLI commands. While a translation runs it shows a modal "translating…" progress dialog and refuses to start a second overlapping run, so pressing the button repeatedly can't fire concurrent passes that would hammer the service and trip its rate limit. All new keys are localized across the six languages, and old data files (without the new original-text fields) load unchanged.
- **The CLI now accepts commands typed in your language.** The desktop CLI matched English keywords only (`help`, `exit`, `simulate`, `importgroups`, …); a command word is now resolved through the active UI language first, so a Spanish user can type `ayuda`/`salir`, a German user `hilfe`/`beenden`, a Japanese user `ヘルプ`/`終了`, and so on, with the English words always still working so scripts and docs never break. Each language supplies its aliases through translation keys (`cli.alias.*`), and `help` lists the localized words alongside the English ones when a translated language is active. The "unknown command" message is localized too.
- **Language picker is a dropdown of native names.** The `ui_language` setting is now a combo box instead of a free-text field, listing `System default` plus every language a translation exists for, shown by native name (`English`, `Deutsch`, `Español`, `Bahasa Indonesia`, `Русский`) while still storing the short code. It auto-grows as `messages_<lang>.properties` files are added, so a dropped-in community language appears without any code change.
- **Localized spoken (TTS) callouts, with native-script names.** When the UI language is set, Scarlet's spoken callouts are generated in that language (`Vex входит в лобби`, plus the watched-group/new-account/vote-to-kick lines, the mixed-character-name alert, and the unnamed-user fallback). Player names are handled per language: a name written in the active language's own script is spoken as-is (a Russian voice reads a Cyrillic name natively) instead of being romanized — driven by a language→script map so future languages get the same treatment automatically — while names in other scripts are still romanized with a translated script label so an English voice stays intelligible. (Scarlet supplies the text; the voice selected in TTS settings pronounces it, so a matching voice must be installed and selected.)
- **Translation linter for community files.** On startup (and via a `langlint` CLI command) Scarlet validates every external `lang/messages_<lang>.properties` against the English base and logs a per-file report: keys translated, keys that don't exist in the base (typos), MessageFormat syntax errors, and — the common trap — `{0}` placeholders that are missing or accidentally quoted out by a stray apostrophe. So a broken translation is a startup log line, not a runtime surprise in a language you can't read.
- **Event simulator + Training mode for onboarding moderators.** A new **Settings → Training** toggle enables an event simulator (Edit → *Simulate event (training)…*, or a `simulate` CLI command) that fires realistic moderation events on demand — watched-group/user joins, new account, mixed-character name, suspicious pronouns, watched avatar, vote-to-kick, plain join/leave — so a trainee can learn Scarlet on a screenshare instead of being thrown into a live incident, and without anyone having to actually join a bad group. Simulated events flow through the **real** presentation and Discord pipeline (player-list row with advisory, TTS callout, desktop/mobile notification, and the genuine Discord post with its ban/unban buttons) so the full workflow — including tagging in Discord — is practiceable. Everything Discord- or archive-bound is marked `[TRAINING]`, and simulated players use an unmistakable `usr_training-` id namespace, so a drill can never be mistaken for a real record.
- **Training sandbox — Scarlet behaves as a separate client while training.** Enabling Training mode parks the real player list and presents an empty instance (with a `[TRAINING]` status-bar tag); real joins/leaves keep updating the parked state in the background, and disabling training restores the real instance as it is *then*, so nothing is lost. The Clear Instance button clears whatever you're viewing (the drill during training, the live list otherwise). Row actions on training players (Ban/Unban/Invite, profile-open) and the Discord ban/unban commands on a training target show the same success feedback a live action gives but make no VRChat call. Toggling training off closes the simulator and stows everything instantly. Real actions on real users stay fully live the entire time.
- **Separate Discord channel for training events (`/set-training-channel`).** Simulated `[TRAINING]` events post only to a dedicated channel instead of the real audit log, so drills never mix into live moderation. If no training channel is set, simulated posts are dropped rather than spilling into a real channel, and training users are also kept out of the Discord live-instance roster embed.
- **Crash-safe data saves with dated backups.** Scarlet's small JSON files (watched groups/users/avatars, moderation tags, settings, Discord config, mobile devices, pronoun lists) now write to a temp file and atomically swap into place, keeping the last 10 timestamped copies in a `backups/` folder beside each file. A crash or power loss mid-write can no longer truncate a community's blocklist, and recovery is just copying back a dated file. (This also put several of those files on explicit UTF-8, fixing a latent charset issue for non-ASCII group names.)
- **Health watchdog for Scarlet's silent failure modes.** A background watchdog turns the two ways Scarlet can quietly stop moderating into loud, recoverable events. *Log-tailer stall:* if VRChat is running but no log line has arrived in 5 minutes, Scarlet raises a popup — and, if configured, an ops alert — that instance monitoring may be down, and announces recovery when lines resume. *Session loss:* every ~5 minutes Scarlet actively probes its VRChat session (the previous check only reflected cached login state and couldn't notice a cookie that expired mid-run); on a genuine 401 it alerts once, then attempts unattended re-login with the stored credentials + TOTP every 10 minutes, announcing recovery. Network blips are deliberately not treated as auth loss.
- **Discord ops-alert channel (`/set-ops-alert-channel`).** Sets a Discord channel that receives Scarlet's operational health alerts — session lost/recovered, log tailer stalled/recovered, hard VRChat rate-limiting (a 429 with a long back-off, throttled so a burst pings once), a Scarlet update becoming available, and a VRChat API version mismatch — so staff learn about problems in Discord instead of only from someone watching the desktop app.
- **Adjustable dimming for players who left.** A new **Settings → Appearance → "Dim players who left (%)"** controls how faded departed rows appear (0 = no dimming, default 35), read live so a change shows on the next view refresh.
- **Mobile delivery status view (Settings → Mobile Companion → "Show mobile delivery status").** A read-only snapshot of what each notification path is actually doing: whether the direct LAN listener is running and on which port, how many phone event streams are genuinely live and how long each has been connected (with any backlog on them), whether Firebase Cloud Messaging is configured, and — per paired device — its transport, enabled state, and when it last accepted a notification or last failed and why. A phone that quietly stopped receiving alerts was previously invisible: the desktop went on "sending" into a dead socket and nothing in the UI disagreed. This makes that failure legible rather than leaving "notifications are broken" as the only available diagnosis.
- **Firebase Cloud Messaging is configurable and actually reachable (`mobile_fcm_service_account`).** Setting a Firebase service account JSON path enables the FCM push path, so paired phones receive alerts anywhere rather than only on the LAN or through the relay. Leaving it empty keeps the previous Firebase-free behaviour. Devices with no usable transport now record *why* they can't be reached instead of being skipped silently.

### Changed

- **VRChat-API update check consults GitHub tags as well as JitPack.** The check that flags when the upstream `vrchatapi-java` dependency has a newer version (the usual signal that VRChat's API changed and a Scarlet push release is due) now queries both JitPack's metadata and the library's GitHub tags and takes whichever is newer — so a new build is caught whichever source publishes it first — and only reports "unavailable" when both fail. It tolerates version-scheme drift (separator changes, a `v`-prefix, SemVer `+build` metadata) by comparing the `major.minor.patch` core, and rejects non-version strings so an error or rate-limit response can't be mistaken for a release. The read timeout was also raised so JitPack's cold-build slowness stops falsely tripping a timeout.
- **Credits list reads `Name — Role`.** The credits tab separates each contributor from their role with a dash instead of running the two columns together, and lists the new translation contributors.

### Fixed

- **Japanese (and other CJK) text no longer renders as empty boxes.** When the UI language needs glyphs the theme font can't draw, Scarlet already swaps in a covering system font — but if no *named* CJK family matched it gave up, leaving all-boxes text on minimal Linux desktops. It now also tries Java's logical composite fonts (Dialog/SansSerif/Serif), which aggregate whatever the system's font subsystem has, so any machine with a CJK font installed renders correctly; if genuinely nothing can draw the language, a clear log line now says to install one (e.g. `fonts-noto-cjk`) instead of failing silently. Also shipped the Japanese translation (`ja`, community-contributed) and registered it in the language picker.
- **Players who left no longer fade to invisible down the list.** The dimming on departed players was being re-applied on top of itself as the shared table renderer moved down a run of left rows, so each successive row grew darker until the bottom ones were nearly the background colour. Left rows are now dimmed from the base text colour by a fixed amount regardless of position — and that amount is configurable (Settings → Appearance → "Dim players who left").
- **Startup no longer waits on the VRChat-API version check.** The preflight version check ran synchronously during launch and could stall startup if JitPack was slow to answer; it now runs off the launch path, so a slow dependency check can't delay the app coming up.
- **Paired phones no longer starve Scarlet's worker pool (the main cause of slow or missing mobile alerts, and of moderation posts lagging with them).** Each connected phone holds its event stream open for as long as the companion app runs, and the handler serving that stream blocked its thread for the entire connection — on the shared 4-thread worker pool that also runs audit polling and every VRChat API call. So every moderator who paired a phone permanently consumed a quarter of Scarlet's background capacity: two phones halved it, and around four could stall audit polling outright, at which point neither moderation posts nor mobile alerts went anywhere until someone disconnected. The symptom got worse the more staff paired their phones, which is precisely backwards. The mobile listener now runs on its own pool that grows with connections, and notification delivery on a second pool of its own, so neither can starve moderation work and moderation work can no longer delay alerts.
- **A phone that walks out of Wi-Fi range no longer delays every other notification.** Alerts were written to each connected phone in turn, on the thread dispatching the alert. A phone that leaves the network doesn't close its connection — the socket stays half-open and the system retransmits for tens of seconds — so that write blocked, and every phone behind it in the list waited, and the relay post after them waited too. Each connection now has its own send queue and does its own writing, so dispatch never blocks; a phone that stops keeping up is dropped and reconnects on its own.
- **Alerts no longer vanish into connections that died silently.** The event stream sent nothing between alerts, and Android, mobile networks and home routers all quietly drop idle connections after roughly half a minute. Neither end noticed: the phone waited forever on a connection with no timeout, and the desktop only discovered the problem on its next send — by which point that alert was already lost. This is the reason alerts could stop arriving after a quiet period and only resume when something forced a reconnect. Scarlet now sends a heartbeat every 15 seconds, and the companion app treats 45 seconds of silence as a dead connection and reconnects.
- **A momentary network failure no longer loses a moderation alert outright.** A failed send was logged and discarded — one dropped request and the alert was gone for good, which for moderation traffic is the worst possible failure mode. Sends are now retried up to five times with increasing backoff (2s, 5s, 15s, 45s). Genuine rejections, like a bad auth token, still stop immediately rather than being retried pointlessly.
- **Firebase push was disabled in shipped builds, so devices registered for push received nothing.** The check for a configured Firebase service account was hardcoded to report "not configured" regardless of settings, so any paired device holding only a push token fell through to a webhook endpoint it didn't have and was skipped — with no warning and no log line. It is now wired to the real `mobile_fcm_service_account` setting. (The Mobile Companion settings group also referenced a setting ID that never existed, so the field could not be shown at all.)
- **One unreachable device no longer delays notifications to the others.** Devices were contacted one after another, and a single unresponsive endpoint could burn up to 25 seconds in connection and read timeouts before the next device was even tried. Each device is now contacted independently.
- **The companion app reconnects sensibly instead of retrying forever at a fixed rate.** Retries now back off from 1 second to a maximum of 60, resetting after any connection that held for 30 seconds or more, so a phone that is simply away from the PC stops hammering it and draining battery. It also watches for the network returning and reconnects immediately rather than sitting out the remaining delay — coming back onto Wi-Fi is exactly when a moderator most needs alerts flowing again.

## 0.4.18-b1

Moderation-speed, export-safety, and avatar-status build.

> **Hotfix — please redownload 0.4.18-b1.** The build was updated in place with the avatar-logging privacy fix below. If you downloaded 0.4.18-b1 before this update, grab it again; the version number is unchanged.

### Added

- **Multi-language foundation (English, German, Spanish, Indonesian) — first slice.** Scarlet now has an internationalization layer (`I18n` + per-language `messages_<lang>.properties` bundles, read as UTF-8 so umlauts/accents are correct). The desktop UI follows the operating system language by default, with a `ui_language` setting to override (`system`/`en`/`de`/`es`/`id`, applied at startup). The Discord bot renders converted messages in each invoking user's own Discord language automatically. This release converts a first proof slice of both surfaces — the desktop menu bar and exit dialog, and the `/unlink-vrchat-account` responses — establishing the pattern; the remaining strings will be migrated over follow-up passes. Any language without a translation (or any untranslated line) safely falls back to English. (Fixes the Java quirk where Indonesian `id` silently resolves to English.)
- **Community translations without a rebuild.** Scarlet reads translation files from a `lang/` folder in its data directory, overlaid on the bundled ones — so a volunteer can hand you a `messages_<lang>.properties` for any language (or a fix to an existing one), you drop it in, restart, and it's live, no build required. New languages are auto-discovered, and Scarlet writes an up-to-date English `messages.template.properties` there (with translate-me instructions) as a starting point. The German/Spanish/Indonesian seeds still want native-speaker review, especially moderation terms.
- **Moderation actions post to Discord in seconds, not minutes (burst polling).** When a moderation action is initiated through Scarlet (Discord command, UI, or the bulk-ban queue), Scarlet now polls the VRChat audit log every 10 seconds for up to 2 minutes instead of waiting out the full `audit_polling_interval`, so the moderation thread posts as soon as VRChat's audit endpoint exposes the entry. The burst ends early once the expected entries are observed, and any failed audit query (including a 429 rate limit) immediately drops back to the normal interval. Idle API usage is unchanged at one audit call per interval.
- **Flurry burst for in-game moderation.** Moderation tends to come in waves: when any warn/kick/ban/unban is observed in the audit log — including actions taken purely in-game — the same 10-second burst window is (re)armed, so the rest of the wave posts within seconds instead of one poll cycle behind. Self-extinguishes 2 minutes after the last moderation event.
- **Avatar statuses without VRChat launch options.** Avatar bundle/status info previously required VRChat's `[API]` debug logging launch options; without them, statuses silently stayed empty forever. Scarlet now reconstructs the same info through a tiered fallback: the avatar-search databases map the (always-logged) avatar name to candidate IDs, the worn avatar is confirmed by comparing candidates against the user's current-avatar image file — which uniquely identifies the true avatar even among dozens of same-named clones (up to 40 candidates scanned, plus avtrDB search-by-image) — and the confirmed avatar's bundle file and analysis are fetched from the VRChat API, producing the same stats as the log-based path. Private avatars fall back to a database record's stored performance rating, nameplate-style. With launch options enabled the exact log-based path still wins; the fallback acts as a 20-second-grace backstop for anything the log path fails to resolve (e.g. private avatars the file API refuses). Players already present when Scarlet starts mid-session are swept and hydrated after log catch-up, staggered 750 ms apart to avoid bursting the search providers. A one-time popup explains fallback mode and the launch options that enable exact data.
- **Readable avatar stats dialog.** The player-list "View" dialog now opens compact — avatar name, performance rating, file size, uncompressed size — with a **Show all details** toggle revealing the full file/avatar statistics; the dialog sizes itself to the collapsed content and grows when expanded. Values are human-formatted: byte counts as KB/MB/GB, timestamps in local time, large counts with grouping separators, bounds as `2.00 × 1.65 × 2.30`, and section headers are bold. When info is unavailable the dialog now states the actual reason (not indexed, ambiguous clone name, private avatar, or still loading) instead of a generic "not yet available", and — when they are the fix — shows the exact VRChat launch options in a selectable box with a **Copy launch options** button.
- **Avatar search provider manager.** The Settings entry is now **Avatar search providers -> Manage...**, opening a dialog listing every known provider as a checkbox (built-in defaults plus stored customs), with an add-custom-URL field and a Restore defaults button. Saving clears provider backoffs and rescans only the players whose avatars still lack data — already-resolved players are skipped, and per-provider search caching means unchanged providers answer from memory, so a rescan costs almost no network traffic or rate limit.
- **Central VRChat API rate limiter.** All VRChat API requests Scarlet sends now pass through one shared gate in its HTTP client: a token bucket smooths aggregate request bursts (the situation that actually trips limits — e.g. joining a busy instance fires audit polling, avatar hydration for dozens of players, and user/group lookups at once), and on a 429 the whole client backs off together (honoring a `Retry-After` hint) instead of only the one feature that received it. This complements, and doesn't replace, the existing per-feature pacers. Only VRChat API traffic is affected; the avatar databases and Discord use separate paths.
- **Diagnostics view (Help -> Scarlet: Diagnostics...).** A read-only snapshot of Scarlet's connectivity and rate-limit state: VRChat session validity and logged-in account, the API rate limiter's backoff state, audit polling interval and last query cursor, each avatar-search provider's up/backing-off status, and the Discord connection status. Uses only cached state (no extra API calls), and has a Copy button for sharing when troubleshooting.
- **Invite yourself instead of relaunching when VRChat is already running.** Creating an instance while VRChat is already open now offers to send an in-client self-invite (via the VRChat "invite myself to instance" API) rather than launching a second VRChat process. The created-instance dialog adds an **Invite myself in VRChat** option (shown first, with a short explanation, when a running VRChat is detected), and the create-instance wizard's own "open in VRChat" step self-invites automatically if the game is already running instead of relaunching it. Falling back to launch when VRChat isn't running is unchanged.
- **Create non-group instances too.** The create-instance wizard's "Who can join" list now offers personal instance types — Public, Friends+, Friends, Invite+, and Invite — alongside the existing Group Public / Group+ / Group Members. Personal instances are owned by the logged-in account and skip the group-permission checks; group types are unchanged. (The menu item and dialog are retitled from "group instance" to just "instance".)
- **Invite a friend to a created instance.** The created-instance dialog also has an **Invite a user...** option that opens a searchable picker of the Scarlet account's VRChat friends (filter by display name or user ID) and invites the selected friend to the instance. VRChat only delivers invites between friends, so the invite is friend-checked first and reports clearly if the target isn't a friend of the Scarlet account — useful when Scarlet runs on a dedicated bot account and moderators want a click-to-invite into new instances.
- **Three new avatar search databases.** Added avtr.zip, PAW (Puppy's Avatar World), and KitsuneDB alongside avtrDB, NSVR, VRCDB, and WorldBalancer — seven providers total, all queried with caching, User-Agent identification, and 429/timeout/malformed backoff. Only providers that honor creators' avatar removal/blacklist requests are included.
- **Export progress dialog.** Exporting a migration bundle now shows a live Windows-Explorer-style dialog: current phase (compressing/encrypting), the file being added, "N of M files", byte totals against a real pre-scanned total, and a working **Cancel** button. Cancelling (or any failure) deletes the partial bundle so a half-written zip is never left behind.
- **Export asks to clear huge logs and caches first.** Log folders of 20 GB+ exist in the wild and the bundle packs the whole data folder, so when logs+caches exceed 1 GiB the export now offers to clear them first (caches, TTS files, and all log files except the current session's), showing the size breakdown and the amount freed. "Export anyway" remains available.
- **Export refuses to race a running VRChat.** Starting an export while VRChat is running now warns with the process ID(s) shown (Windows `tasklist`, Linux `pgrep` under Proton/Wine) and offers Check again / Export anyway / Cancel. Detection failures are treated as "not running" so a broken tool can never block an export.

### Changed

- **Instance present/left tracking extracted into `InstanceRoster`.** The join/leave/present state machine and its catch-up UI-update ordering — the logic behind several recent bugs (missing players after a restart, avatar lookups for departed players, and the inflated present count) — now lives in a small dependency-free class the event listener delegates to. Joins and leaves share one deferral path, so a queued join can no longer flush after a leave and resurrect a departed player. Behavior is unchanged; the structure just makes the ordering bug impossible to reintroduce from the callers.
- **Audit queries re-cover a 5-minute overlap window.** VRChat can ingest audit entries out of order, so an entry created just before the query cursor could become visible only after newer entries were already seen — and would then fall permanently behind the query window. Every poll now re-queries the last 5 minutes and relies on per-entry ID dedupe, so late-ingested moderation actions always post (and re-seen entries are skipped before triggering any API work). The cursor itself never rewinds, so repeated empty polls cannot walk it backwards.
- **Avatar search accepts providers' native response formats.** Providers have drifted from the original bare-array VRCX response format toward their own search engines' shapes. The parser now accepts a bare array or a wrapper object (`avatars`/`results`/`data`/`items`/`records`), flattens nested author records, and tolerates individual malformed entries; unrecognizable responses back the provider off for 5 minutes instead of retrying with a stack trace on every search. Successful searches log a per-provider result count at DEBUG for diagnostics.
- **One shared UI palette.** The hand-styled parts of the main window (settings page, cards, tab bar, header, CLI input, sidebar dividers) previously inlined their own copies of the theme colors with slightly drifted shades; they now reference a single palette in `Swing` alongside the FlatLaf theme keys, so the shades can never drift apart again.

### Fixed

- **Unicode display names in logs are no longer corrupted to "?".** The log file was written in the platform default charset, so stylized/Unicode usernames were saved as `?` — permanent data loss. The log file is now written as UTF-8, and stdout/stderr are switched to UTF-8 at startup (with the Windows console code page set to 65001) so console output shows the real characters too. Note: whether a stylized glyph actually renders in the terminal still depends on the console font — "fancy-font" names may show as boxes there — but the log file always preserves the true characters now.
- **Fancy/custom-glyph display names no longer show as squares outside the main table.** The player table already applied Unicode font fallback per name, but other spots that render display names used a plain font and drew missing-glyph squares for stylized usernames. The same fallback is now applied in the friend-invite picker, the avatar-stats dialog (avatar name, owner name, notes), the desktop feedback popups, and confirmation/error dialogs that include a name (ban/unban prompts, invite results) — via a shared helper that leaves ordinary ASCII dialogs untouched.
- **Watched-group warnings re-check against fresh membership on rejoin (no more flagging users who already left).** A user's group membership was cached indefinitely, so someone who left a watched group — even after proving it to mods via a ticket — kept getting flagged on every rejoin, making it look like moderation abuse. Now, when an already-flagged user leaves the instance and rejoins, Scarlet re-checks their live group membership before warning; if they've since left the group the warning is suppressed and the cached record corrected, so it stays clean. Scoped to actual rejoins of flagged users, so it's a rare extra lookup and costs nothing for everyone else. Hidden groups aren't exposed by VRChat's API and still can't be verified this way.
- **Self-invite (and instance lookup) no longer fail with "malformed url".** Any VRChat call that puts a tagged instance ID in the URL path let the generated client percent-encode the tags (`~group(...)`, `~region(...)`), and VRChat's Cloudflare WAF rejects encoded parentheses in the path (waf_code 26497). Both self-invite and the instance lookup used for auto-launch short-name resolution now build the path by hand so the tags keep their literal parentheses, matching the form VRChat accepts. Inviting a user/friend was never affected — that call carries the instance in the request body, not the URL.
- **Create-instance-and-open no longer lands in the error world.** Opening a freshly created instance in the VRChat client could drop you into the error/loading world instead of the instance you made, because the launch deep link passed only the instance location and omitted the instance's short/secure name. VRChat requires that short name to resolve and authorize any non-public instance (group, group+, friends, invite, private), so without it the client couldn't join. Scarlet now includes `shortName` in the launch URL wherever it opens an instance — the create-instance wizard, the "Open in VRChat" buttons, and Discord auto-launch-on-create (which resolves the short name from the API first). Also fixed a Linux fallback that launched VRChat through `steam://rungameid` without forwarding any arguments, so that path opened the home world with no instance.
- **Inflated "present" count / players stuck as present after catch-up (hotfix).** When Scarlet reads a session's log at startup (or when a new session log appears), player joins are queued and flushed once catch-up finishes, but player leaves ran immediately — so anyone who joined *and* left during the catch-up window had their row created as "left" and then reset back to present by the late-flushing join. Those players stayed marked present indefinitely, inflating the present count (e.g. showing 41 present when far fewer actually were). Leaves are now queued during catch-up the same way joins are, so they flush in log order and departed players correctly show as left. Live leaves are unchanged. (A running session that already has the stale entries needs a restart to re-read the log cleanly.)
- **Avatar CHANGES after a player leaves are no longer tracked, but their last avatar is kept (hotfix).** Avatar lookups could continue for players who were no longer present — including after they left while Scarlet and VRChat kept running, and for players from a previous instance or a closed VRChat session whose state lingered in memory. Departed players remain visible in the player table with the avatar they were wearing while present, but Scarlet no longer follows them: once a player leaves, avatar switches attributed to them are ignored and no new lookups are initiated (the log-based `[API]` analysis handler, the database/API fallback, queued lookups, and the rescan sweep all check presence before doing any work or recording any result). Their last-known avatar and stats are frozen in place rather than wiped, so the row still shows what they had. Per-instance avatar state is cleared when leaving an instance and when VRChat quits, and rescans refuse to run unless a VRChat process is live and an instance is current. Besides being the correct privacy behaviour, this removes a class of unnecessary avatar lookups and the rate-limit pressure that came with them.
- **Join checks no longer run for players who left during catch-up (hotfix).** Preamble join rows are queued and flushed once log catch-up finishes; if a player had already left again within the same log, the queued join checks still ran and fetched their VRChat profile and group memberships. Those lookups are now skipped for anyone no longer present — their row is still added to the table, just without the API calls.
- **Overlapping/miscolored UI on Windows displays with scaling (125%, 150%, ...).** On Java 9+ JREs, Java2D applied the Windows display scale on top of Scarlet's own FlatLaf/UI-scale handling, causing overlapping buttons and tables, duplicated window artifacts, and miscolored popups. Scarlet now disables Java2D's display scaling at startup on Windows (equivalent to launching with `-Dsun.java2d.uiScale=1 -Dsun.java2d.uiScale.enabled=false`) and lets FlatLaf derive the scale from the system font instead, so no launcher `.bat` workaround is needed. Passing either `sun.java2d.uiScale` property on the command line still overrides this.
- **Cache cleanup no longer tries to delete the live log.** The active session's log file could end up in the delete list because the guard compared `File` path strings, which fail on relative-vs-absolute mismatches (the logger can capture the data directory before it is assigned during class initialization), producing "file in use" errors on every cleanup. The guard now compares canonical paths and the per-session-unique timestamped file name, on both the periodic cleanup and the pre-export cleanup.
- **NSVR (NekoSune) avatar search works again.** The search endpoint moved from `avtr.nekosunevr.co.uk` to `vrcavatarsearch.nekosunevr.co.uk`; the old host answers with a notice string that failed JSON parsing on every search, spamming the log with Gson stack traces. The provider now points at the current host, and malformed responses in general now trigger a quiet backoff instead of per-search stack traces.
- **WorldBalancer avatar search works again.** The provider now calls WorldBalancer's current `vrcx_search` endpoint; the previous `scarlet_search` route is defunct and always returned nothing.
- **Remaining light-grey component patches in the dark theme.** Scroll panes and viewports, checkbox/radio/toggle label backgrounds, password fields (the export PIN dialog), disabled text fields, spinners, sliders, progress bars, trees, titled borders, split panes, and toolbars had no theme keys and fell back to FlatLaf's stock grey, showing up as mismatched patches. All are now themed to the shared palette, and the avatar-stats dialog no longer draws an inconsistent inset border around its content.
- **Stale avatar info cleared on switch.** When a player switches avatars, the previous avatar's stats are removed from their row immediately instead of lingering until new data arrives.

## 0.4.18

### Added

- **Live progress for Linux package installs.** Linux package installs now show a small progress dialog with an indeterminate bar, a masked input field for answering unexpected package-manager prompts, and an always-visible terminal output pane that streams the package manager's output live, so you can see it working instead of waiting in the dark. Privileged installs use `pkexec` when available; interactive/user-space package managers run inside a PTY-backed terminal embedded in Scarlet instead of opening an external terminal emulator. It's a reusable component (`PkexecInstaller`) so the TTS/voice installer and the other Linux install prompts can share it.
- **Desktop notifications (toasts) on Windows and Linux - no PowerShell.** Advisories can now raise native desktop notifications. Windows uses Java's built-in system-tray balloon, which Windows 10/11 surfaces as an Action Center toast; Linux uses `notify-send`; macOS uses `osascript`. PowerShell is deliberately never invoked, so one code path works across platforms and nothing pops a console window. A new **Settings -> Desktop Notifications** section adds a master on/off (default off), independent per-category toggles (watched users/groups/avatars, votes-to-kick, moderation, staff joins/leaves, new players, mixed-character names, suspicious pronouns), and a **Send desktop test notification** button. On Linux, if `notify-send` is missing, the test button and the first failed live toast offer to install the right libnotify package through the detected package manager. Toasts fire only for live joins, never when an instance first loads, and the tray icon is created lazily the first time a toast is shown.
- **Per-category advisory toggles.** A new **Settings -> Advisories** section controls watched users, watched groups, watched avatars, new players, mixed-character names, votes-to-kick, and suspicious pronouns separately. Turning a category off hides its Instance-table advisory and suppresses that category's TTS callout, so unwanted pronoun-style alerts no longer keep speaking after the visual advisory is disabled.
- **Guided audio-player install on Linux.** If local TTS playback is enabled but none of `pw-play`/`paplay`/`aplay` is present, Scarlet now offers to install one for you via your distro's package manager (reusing the same detector as the TTS/xdg-utils prompts), opening a terminal for the password. It targets `alsa-utils` (the universal `aplay`); `pw-play` and `paplay` already ship with PipeWire and PulseAudio respectively, so the prompt only appears on minimal setups. Until a player is installed, playback falls back to direct ALSA output.
- **More Linux TTS voices, installed by choice.** Linux TTS now discovers multiple local command-line engines instead of only `espeak`: Flite, Mimic, Pico/`pico2wave`, Festival/`text2wave`, eSpeak NG, and legacy eSpeak. Voice choices are shown as `engine/voice` (for example `flite/slt`, `pico2wave/en-US`, or `espeak-ng/en`), while old bare eSpeak voice names still fall back cleanly when they match. The startup installer only offers the default engine needed to make TTS work; a new **Settings -> Text-to-Speech -> Install Linux TTS voices** button lets Linux users explicitly choose extra repo-backed engines from their detected package manager.
- **Toggle for suspicious-pronoun detection.** A new "TTS: Flag suspicious pronouns" setting (Settings → Text-to-Speech, default on) turns the whole pronoun-detection path on or off — the Advisory entry, the spoken callout, the mobile alert, and the player-list highlight. Leave it on and use "TTS: Announce suspicious pronouns" to keep flagging while muting only the spoken line.
- **Declutter the main window.** Right-click the player-list header to show or hide columns — the same list as Edit → Columns, now where you'd expect it, and your choices are still remembered between launches. A new **View** menu lets you hide the **CLI** tab if you don't use it (`ui_show_cli_tab`).
- **Unlink VRChat accounts.** A new self-service `/unlink-vrchat-account` lets any member remove their own Discord↔VRChat link (handy for re-linking to re-test verification), and a staff-only `/unlink-vrchat-account-for` (requires Moderate Members) unlinks another member by Discord user and/or VRChat user. Both only clear the link record — they don't change Discord roles or VRChat group membership.
- **PIN-encrypted migration bundles (required).** Exporting a bundle now requires a PIN (at least 4 characters), which encrypts the whole bundle with AES-256-GCM (PBKDF2-SHA256 key) - the same primitives Scarlet uses for stored credentials. This matters because a bundle ships `global-prefs.key` alongside the credentials it decrypts, so an unencrypted bundle is effectively plaintext to anyone holding it; the PIN adds a layer whose key isn't in the file. The GCM tag also authenticates the bundle, so it can't be silently altered. Import prompts for the PIN and fails fast on a wrong one before touching any local data. A lost PIN cannot be recovered.
- **Move-out option when exporting.** The export dialog has an "I'm moving to another computer" checkbox. When set, after the bundle is written and verified Scarlet offers to remove this machine's data folder and stored credentials. The confirmation's destructive button is disabled and counts down (~10 seconds, "Remove all settings… 10… 9…") before it can be pressed, so the wipe can't be triggered reflexively; the "Keep" button is immediate. The removal runs during shutdown after all background work has stopped, so nothing can re-create the deleted files, and it's skipped if the bundle was saved inside the data folder.
- **One-file migration between machines.** A new **Settings -> Backup & Migration** section moves an entire Scarlet identity to another PC or operating system. **Export bundle** only copies from the source PC: it writes a single portable `.zip` containing Scarlet's data folder plus encrypted Java Preferences credentials exported as OS-independent XML, so Windows registry-backed credentials can import cleanly on Linux/macOS Java Preferences stores. **Import bundle** lets you choose data/config files, secure credentials/sign-ins, or both; data-only imports skip credential key files so the destination PC's existing sign-ins keep working, while credential imports include Java Preferences plus `global-prefs.key`. Import restores into the destination machine's correct data and Preferences locations, then quits Scarlet so the old in-memory session cannot overwrite the imported files. Because the credential-encryption key lives in the data folder rather than anything machine-bound, the Discord token, VRChat login, 2FA secret, session cookie and alternate credentials all decrypt unchanged on the destination, including Windows->Linux and Linux->Windows.

### Changed

- **Slimmer advisory text.** Multiple advisories in the Instance table now use a readable inline separator instead of newline boxes, suspicious-pronoun advisories collapse to a short "Suspicious pronouns" label, and watched avatars can populate the Advisory column when detected.
- **Pronoun toggles are visible in Settings.** "TTS: Announce suspicious pronouns" was previously only changeable by editing the settings file; both pronoun toggles now appear in the Text-to-Speech settings section, and are hidden in editions built without the pronoun feature.
- **Verification auto-invite no longer pre-checks group membership.** After a member verifies, Scarlet now sends the VRChat group invite directly instead of first querying their group membership status. Eligibility (VRChat age-verification or the manual-verification member role) is already decided at link time, and invite failures are now classified after VRChat responds.
- **Restart now relaunches Scarlet itself.** The existing Discord `/server-restart restart-now` action and the new CLI `reboot` / `restart` command now start a fresh Scarlet process from the current Java command line instead of only exiting with a restart code for an external wrapper. The relaunch uses the current JAR path or classpath, so it does not depend on the JAR filename, and it inherits the current terminal streams so console logging continues after reboot.

### Fixed

- **Repeated TTS callouts and notifications (Linux).** Scarlet could re-announce every join in the current VRChat session log over and over - even hours later - because the log tailer treated any modified-time change without newly appended bytes as an in-place overwrite and re-read the whole file from byte 0, replaying each line as a *live* event. VRChat running under Proton on Linux bumps the log's mtime without exposing new readable bytes, so this fired continuously. The tailer now only reads genuinely new appended data; real log rotation (a new, shorter file) is still detected and handled as before.
- **Missing instance players after restarting VRChat.** When VRChat wrote a new session log while Scarlet kept running, Scarlet now resets the tailer's per-log live state and flushes the new log's queued catch-up rows, so players already present in the restarted session appear in the instance table immediately after catch-up.
- **Linux TTS/package install actually runs now.** Installing eSpeak / extra TTS voices (and the related Linux package prompts) only knew how to open a terminal emulator, and it detected terminals with the external `which` command - which isn't installed on many modern/minimal distros - so on those systems nothing opened and it reported failure. Scarlet now installs through `pkexec` first - a graphical polkit password prompt, no terminal required, run as Scarlet's own child process so its exit code is trustworthy - and if terminal semantics are needed, Scarlet opens an embedded PTY-backed terminal inside its own installer dialog instead of launching `gnome-terminal`, `xfce4-terminal`, or another external emulator. All three Linux install prompts - TTS engines, the audio player (alsa-utils), and libnotify (`notify-send`) - now use this same path and verify the package appeared before reporting success.
- **Linux package installs no longer run user-space managers as root.** The progress installer now uses `pkexec` only for package managers marked as requiring sudo. Homebrew, Nix, AUR helpers, and other user-space managers run as the current user with the same live-output progress dialog, avoiding root-owned package caches or outright install failures.
- **AUR/helper password prompts now use an embedded real terminal.** Helpers such as `yay`, `paru`, and `pamac` may spawn `sudo`, which can reject password input unless it has a real terminal. Scarlet now runs those helpers inside a Pty4J-backed terminal in the installer dialog, so prompts stay inside Scarlet while still getting real PTY semantics.
- **Linux package install progress no longer hides terminal-style updates.** The progress dialog now streams raw output chunks instead of waiting for newline-terminated log lines or filtering terminal control sequences, so AUR/Pamac-style TTS voice installs can show the exact prompts and progress text emitted by the package manager.
- **Linux optional TTS installs now verify the selected engines.** The extra-voice installer now waits for the exact selected commands (for example `mimic`) to appear after the package manager runs, and terminal fallbacks print "Installation failed" with the real exit code instead of implying success when the package manager rejected or failed the install.
- **Linux desktop notification tests now catch broken sessions.** Scarlet now waits briefly for `notify-send` to report success, so the Settings test no longer says notifications are working when the command exists but fails because the notification daemon or DBus session is unavailable.
- **Verification auto-invite failures now tell the truth.** A VRChat 403 body of `"You're not a member?"` during invite means Scarlet's logged-in VRChat account is not a member of the configured group, not that the linked member is already invited. Scarlet now treats the placeholder auto-invite group as unset, falls back to the main configured VRChat group, downgrades expected invite rejections to WARN, and replies with setup-specific guidance for missing group membership, missing invite permission, missing group, or already-invited cases.
- **Suspicious-pronoun row coloring no longer overrides stronger advisories.** Watched group/avatar row colors now win over the amber pronoun hint, so higher-priority risk information stays visible.
- **Watched groups found through alternate VRChat credentials now appear correctly.** Scarlet was accidentally reusing the primary account's group list when merging an alternate account's lookup result, which could hide a valid watched-group advisory.
- **WorldBalancer avatar search uses Scarlet's endpoint.** The bundled WorldBalancer avatar-search provider now calls `/scarlet_search` instead of the old VRCX-compatible `/vrcx_search` route.
- **Linux TTS now plays on your actual output device.** With "TTS: Use default system audio device" enabled, Scarlet plays generated speech through an external player (`pw-play`, `paplay`, or `aplay`, auto-detected in that order) instead of the Java Sound API. Java Sound opens the ALSA hardware device directly and bypasses PipeWire/PulseAudio, so TTS never showed up in the system volume mixer and often played to the wrong (silent) device even when the `.wav` played fine manually. Playback now routes through the sound server, appears in volume control, and follows your selected output. Set the `SCARLET_TTS_PLAYER` environment variable (or `-Dscarlet.tts.player=...`) to force a specific player command. Windows and macOS fall back to the previous Java Sound path unchanged.
- **Verification group invite no longer fails with "Could not check your VRChat group membership status."** `getGroupMembership` mis-read VRChat's API: it treated only HTTP 404 as "not a member," but VRChat returns **403 "You're not a member"** when the queried user isn't in the group and 404 only when the group itself doesn't exist. Looking up a not-yet-member — i.e. every user being invited after verification — threw an error instead of reporting "not a member," so the auto-invite always failed. Non-member 403s are now handled correctly, which also fixes the same latent fault in the moderation and UI membership-status lookups.
- **Migration security hardening.** Several defences were added to the import path: the preferences XML is validated (with an XXE-hardened parser) so a bundle can only write Scarlet's own Preferences subtree and never inject arbitrary Java Preferences; per-entry, total-size and entry-count limits guard against zip bombs; restored files are written after deleting any existing target so a planted symlink can't redirect the write; and exported bundles, automatic backups, restored credential files, and the backup folder are now created owner-only (POSIX) with old pre-import backups auto-pruned, so decryptable secrets no longer accumulate world-readable. PINs are held in `char[]` and wiped after use.
- **Migration import is backed up and hardened.** Before importing, Scarlet now always creates a local pre-import backup bundle of the destination machine's current data and credentials, and aborts the import if that backup cannot be made. Import also refuses malformed bundles without `preferences.xml`, replaces Scarlet's existing Preferences subtree instead of leaving stale secure keys behind, flushes the imported Preferences before quitting, and skips shutdown saves after import so restored files remain intact. Migration and backup bundles contain directly decryptable secrets, so treat them like passwords and delete them when you no longer need them.
- **VRChat API preflight timeouts are quiet now.** The best-effort upstream version check no longer dumps a full DEBUG stack trace for normal network failures such as timeouts, DNS issues, refused connections, offline mode, or SSL/socket interruptions. Startup still reports that the bundled API is being used and the manual status dialog shows a short reason.
- **Missing optional Windows TTS voice registries are quiet now.** Scarlet still probes legacy SAPI and modern OneCore voice categories so it can find Microsoft Sam-era voices, per-user voices, Desktop voices, and Natural Voice adapters across Windows versions, but absent optional categories such as HKCU Speech or OneCore no longer print scary DEBUG stack traces.
- **Popups now match the dark main window.** The custom theme styled most components but never set `OptionPane`, `TextArea`, `TextPane`, `EditorPane`, or `List` colours, so message/confirm dialogs and the text blocks inside them fell back to FlatLaf's lighter default grey and clashed with the rest of the UI. These now use the same dark palette (dialog background matched to the main window base, text areas/lists to the input colour), so popups across the app look consistent.

## 0.4.18

Moderation-callout and settings-reliability release.

Highlights:

- **Condensed TTS callouts.** Join-time announcements for a user (watched user, watched group(s), new account, suspicious pronouns) are now combined into a single spoken line, and multiple watched groups are read together instead of only the first.
- **Smarter suspicious-pronoun detection.** Scarlet now recognizes real pronoun sets, including neopronouns, so fields like `she/her puppy` no longer false-flag, while genuinely abusive fields (self-harm directives, deny-listed terms, links/invites) are still caught.
- **Settings reliability.** Dropdown settings now display their saved value instead of resetting to the default, and Discord commands that change a setting update the desktop UI live.
- **Safer auto-invite default.** The verification auto-invite group now defaults to an obvious placeholder instead of a real group.

### Added

- `TTS: Announce suspicious pronouns` setting to toggle the spoken pronoun warning independently of the other announce switches.
- Built-in recognition of common pronoun sets and neopronouns, plus more comprehensive default `good_pronoun.json` / `bad_pronoun.json` lists.

### Changed

- Join-time TTS callouts are consolidated into one spoken line per user; multiple watched groups are listed together; vote-to-kick and watched-avatar phrasing trimmed.
- Suspicious-pronoun detection rewritten: a field containing a legitimate pronoun passes even with extra words, while self-harm/violence directives (including basic leetspeak), embedded deny-listed terms, and links/invites are flagged first.
- Verification auto-invite group default changed from a real group to a placeholder.
- Bumped Scarlet release / version metadata to `0.4.18`.

### Fixed

- Dropdown settings (e.g. mobile minimum severity) reset to their default when the Settings tab was rebuilt; they now show the saved value, and can no longer be silently overwritten by re-selecting the shown default.
- Settings changed via a Discord command now update the desktop Settings UI live — the change-listener registry no longer silently drops the UI updater (a `ConcurrentSkipListSet` comparator keyed only on priority treated distinct listeners as duplicates).

## 0.4.17-b3

Scarlet Companion (Android push notifications) release.

Highlights:

- **Scarlet Companion Android app.** Receive Scarlet alerts on your phone from anywhere in the world — no accounts, no third-party services. Pair by scanning a QR from Scarlet's settings.
- **Per-alert notification sounds and an in-app alert log.**

### Added

- Scarlet Companion Android app, with push notifications relayed through a lightweight Node.js server, per-alert-type sounds, a persistent in-app alert log, and per-type / minimum-severity notification controls.

### Changed

- Bumped Scarlet release / version metadata to `0.4.17-b3`.

### Fixed

- Stale notification channels after app updates, notifications not arriving when switching Wi-Fi → mobile data, silent pairing failure when no Firebase was configured, and watched group/user notifications being filtered out by the default severity threshold.

## 0.4.17-b2

TTS and packaging cleanup release.

Highlights:

- **Clearer mixed-character TTS alerts.** Scarlet now plays a bundled alert sound before a short spoken warning when a joined user's display name uses mixed scripts or lookalike characters, then lets TTS pronounce the best readable form without spelling out language labels.
- **Smaller desktop JAR.** RVC, the old lite/minimal package variants, and dormant staged browser automation code have been removed from the desktop build, while Android DAVE natives now stay in the Android artifact instead of the main desktop JAR.
- **Bundled startup security checks.** Security regression checks now live in the main Scarlet source tree and run during normal Scarlet startup instead of living under the test tree.
- **Fork update metadata.** `meta.json` now advertises `0.4.17-b2` for both release and build update checks.

### Added

- Bundled `tts/mixed-character-alert.wav` so mixed-character username alerts work from the packaged JAR without requiring a separate local audio file.
- Moved security regression checks into the main Scarlet source tree so the packaged JAR carries the checks with the application.

### Changed

- Mixed-character username TTS now uses a short alert phrase and best-effort readable pronunciation instead of announcing script names such as Cyrillic or Greek for each character.
- Mixed-character username normalization now favors Latin lookalike output when a display name is mostly readable as an English-style name, while still preserving full non-Latin names instead of forcing them through the lookalike path.
- The main desktop shade excludes Android and Termux DAVE native payloads; the Android classifier build still includes them automatically during normal Maven packaging.
- Bumped Scarlet release / version metadata to `0.4.17-b2` and pointed update / download release checks at the then-current maintained fork.

### Fixed

- Security regression checks are no longer stranded under `src/test`; they are bundled with Scarlet and executed as part of normal startup.

### Removed

- RVC voice-conversion code, bundled Python bridge assets, and RVC settings/UI wiring have been removed from Scarlet.
- The lite and minimal shaded JAR variants have been removed now that RVC is no longer shipped.
- Dormant staged browser/web automation sources, including the old NanoHTTPD/JCEF/Selenium experiment tree, have been removed.

## 0.4.17-b1_hotfix

Hotfix update for the first Linux-compatible fork release.

Credit to ChanAurora for reporting both the help-desk report issue with custom-font usernames and the stale Scarlet version shown in generated reports.

Hotfix highlights:

- **Readable help-desk reports for stylized usernames.** Report URLs now use a normalized ASCII subject while preserving the exact VRChat display name in the report description so staff can identify the reported user.
- **Correct report versioning.** Report footers, Discord embed footers, UI labels, startup output, and packaged JAR metadata now report the fork-specific `0.4.17-b1_hotfix` identity.
- **Fork update metadata.** `meta.json` now advertises `0.4.17-b1_hotfix` for both release and build update checks.
- **Lower-bandwidth update checks.** Scarlet reuses the same `meta.json` fetch for update and announcement checks and asks GitHub for `304 Not Modified` when nothing changed.
- **Security hardening for announcements.** Announcement link buttons are restricted to normal web links so `file://` paths and custom app protocols cannot be broadcast as clickable actions.
- **Security hardening for VRChat launch links.** Windows client launch now uses argument-list process creation and URL-encodes the VRChat location, preventing crafted locations from injecting extra launch arguments.
- **Announcement metadata guard.** GitHub now raises an immediate warning issue if `meta.json` announcement metadata is pushed by a GitHub actor outside the trusted announcer allowlist.
- **Fork branding polish.** Remaining plain `Scarlet` version labels, startup text, and log prefixes were updated to the then-current fork identity where user-facing ownership mattered.

Original 0.4.17-b1 highlights still included:

- **Scarlet on Android.** A new `scarlet-android` Maven module produces a standalone APK that tails VRChat logs from the official VRChat Android app over Wireless Debugging — no USB, no root, no terminal.
- **Group instance wizard.** A desktop UI for spinning up VRChat group instances with world / access / region / age-gate / avatar-performance prompts, and an option to launch straight into the new instance in VR or Desktop mode.
- **Discord warn + action-log channel.** A new `/discord-warn` command and a configurable action-log channel so warn / kick / ban events get a paper trail.
- **Faster, friendlier self-updates.** Hourly update polls, a manual *Help → Scarlet: Check for updates* entry, a fork-aware metadata URL, and a comparator that recognises `-b1` suffixes as iterations ahead of the bare release.

### Security Advisory

- **CVE pending / not assigned: announcement custom-protocol link handling.** During local abuse testing of the new GitHub-backed announcement popup, development builds accepted arbitrary URL schemes from `meta.json`. If a maintainer or compromised push-capable account published an announcement with a local file or custom protocol URL, and a user clicked the announcement's `Open link` button, Scarlet could hand that URI to the operating system through Java Desktop integration. Depending on the user's browser/OS/protocol-handler settings, this could invoke a local application or registered protocol handler. The fixed behavior allowlists only `http://` and `https://` announcement links; all other schemes are ignored defensively. The issue required control of the announcement metadata source plus user interaction, and no announcement link is opened automatically.
- **Announcement metadata guard.** Pushes that change `meta.json` on `main` are checked by GitHub Actions against a trusted announcer allowlist. Untrusted actors create a warning issue and fail the guard job. This warning path is triggered by GitHub's push event, not by raw-file polling, so it is not affected by CDN cache delay.

### Added

#### Scarlet on Android (new `scarlet-android/` module)

- New `scarlet-android/` Maven module producing a standalone APK (`scarlet-android-<ver>.apk`) that captures VRChat log output from the official VRChat Android app via an embedded ADB client over Wireless Debugging and writes a VRChat-formatted `output_log_<ts>.txt` that Scarlet's existing tailer consumes.
- One-time Wireless Debugging pairing flow in `MainActivity` / `AdbPairingService` using NSD discovery of `_adb-tls-pairing._tcp` plus a 6-digit pairing code — no USB, no root, no terminal commands.
- `ScarletLogService` foreground service that owns the `dadb` session + logcat tail and auto-reconnects when Wireless Debugging drops.
- `AndroidLogcatTail.rewrite(...)` translator that reformats `logcat -v year -s Unity:V VRCApplication:V` lines into the `yyyy.MM.dd HH:mm:ss Log - …` shape expected by `ScarletVRChatLogs.VRChatLogTail`.
- `scarlet.vrcAppData.dir` / `SCARLET_VRC_APPDATA_DIR` override in `VrcAppData.resolve(...)` so the Scarlet core tailer can be pointed at any directory — including the path where `scarlet-android` writes its `output_log_*.txt` — without patching `VrcAppData` on Android.

#### VRChat group instance creation

- Desktop UI wizard for creating VRChat group instances directly from Scarlet, with simple world / access / region / age-gate questions and an option to launch the new instance in the VRChat client in VR or Desktop mode.
- Instance-creation flow backed by the newer VRChat group instance APIs, with more complete support for recent VRChat API additions.
- Avatar performance gating in group instance creation, using the live VRChat API `minimumAvatarPerformance` field with Poor, Medium, and Good-or-better presets.

#### Discord

- `/vrchat-group` modern group API commands: audit-type discovery, member search, ban / invite / join-request lists, group posts, group announcements, and confirmed owner-level transfer actions.
- `/discord-warn` plus `/set-discord-action-log-channel` so Discord warn / kick / ban actions can be logged to a server-selected channel.
- Discord member join logging with invite-use detection when the bot has Manage Server permission. (Discord IP addresses remain unavailable because Discord does not expose them to bots.)

#### Self-update flow

- *Help → Scarlet: Check for updates* menu entry for an on-demand check that reports up-to-date / update-available / probe-failed results.
- `MiscUtils.compareScarletVersion` so a `-b1` suffix is treated as an iteration **newer** than the bare release — `0.4.17` → `0.4.17-b1` is now a recognised update.
- Public `Scarlet.checkUpdateNow()` entry point + `UpdateCheckResult` so the UI shares parsing and version-comparison logic with the periodic background check.

#### Misc

- Startup data-folder warning explaining that the fork-specific data folder is separate, the legacy folder is left in place, and switching back to the original repo may require setup again.
- Hidden `popup-test` CLI dispatcher for dry-run popup testing without performing the confirmed action.

### Changed

- Bumped Scarlet release / version metadata to `0.4.17-b1_hotfix` and pointed update / download release checks at the then-current maintained fork.
- Periodic update poll runs **hourly** instead of every 3 hours.
- Update prompt rewording: "Hey, your release is *X*, there is a new release of *Y*. Open the download page?" (in both the auto-prompt and the new manual dialog).
- Default Scarlet data directories and user-agent branding moved from the legacy upstream owner name to the then-current fork identity, with a one-time opt-in prompt before copying data from the old default folder.
- Log categories and direct console log prefixes were rebranded to the then-current fork identity.

### Fixed

- `meta.json` was missing a comma between its two fields, so the update probe failed JSON parsing every run; the file now parses cleanly.
- Fork update metadata URL now uses GitHub's raw endpoint, and a missing `meta.json` is downgraded to a warning instead of producing a startup error stack trace.
- Help-desk report subjects now normalize custom-font / stylized Unicode usernames into URL-safe ASCII, while the report description includes the exact original display name whenever normalization changes it.
- The custom-font username fix covers Latin-Extended stroke/bar glyphs like the `༒808 ȼɍᵾsȺđɇɍ༒` case (`tf_subject=808+crUsAder`), fullwidth and mathematical styled Latin, combining marks, CJK, Cyrillic, Greek, Hebrew, Arabic, Devanagari, and decorative Unicode flair.
- Version reporting now reads the packaged implementation version at runtime instead of relying on a compile-time-inlined constant, preventing report footers from showing an older build after partial recompiles.
- All primary packaged JAR variants now receive manifest implementation metadata so report/version text can resolve the correct build from the artifact itself.
- Report footer branding now uses the maintained fork identity instead of plain `Scarlet`.
- Background update / announcement polling now uses HTTP validators (`ETag` / `Last-Modified`), a short in-memory cache, and a shared 5-minute metadata cache key so unchanged `meta.json` checks do not repeatedly download the full file or get pinned to a stale GitHub CDN object for too long.
- Announcement checks now run every 10 minutes instead of hourly, while normal update checks remain hourly.
- Announcement link buttons now only open `http://` and `https://` URLs; local files and app/protocol links are ignored defensively.

### Removed

- `avatarrecovery` provider dropped from the default and configured avatar search providers.

## 0.4.16-b5
  - Added a third `minimal` edition built alongside full and lite; `mvn clean package` now produces `scarlet-0.4.16-b5-minimal.jar`
  - Added feature-gated command/subsystem wiring so the minimal edition disables built-in Discord kick/ban, watched-avatar management, scheduling/calendar, auxiliary webhooks, animated emoji generation, DAVE voice encryption, evidence submission, and RVC while keeping the core VRChat audit-log + Discord/TTS pipeline
  - Added Android/Termux support for Discord DAVE voice E2EE via `Platform.IS_ANDROID` / `Platform.IS_TERMUX`, `AndroidDaveJvmLibraryLoader`, and a local `DaveNativeBindings` override so the bundled Lavaplayer native loader is bypassed on Bionic
  - Added two Maven profiles for producing a Bionic-compatible native library: `-Pandroid-dave-native -Dscarlet.buildAndroidDaveNative=true` and `-Ptermux-local-dave -Dscarlet.localAndroidDaveNative=<path>`
  - Added runtime override knobs `scarlet.daveJvm.native` / `SCARLET_DAVE_JVM_NATIVE` and `scarlet.dave.native` / `SCARLET_DAVE_NATIVE`, plus `<Scarlet dir>/native/{termux,android}/libdave-jvm.so` / `libdave.so` search paths
  - Added "lite" edition built alongside the full edition - `mvn package` now produces both `scarlet-0.4.16-b5.jar` and `scarlet-0.4.16-b5-lite.jar`; the lite JAR has no RVC UI, no Python bridge, and skips the torch/rvc-python dependency install flow
  - Added compile-time feature flag `net.sybyline.scarlet.Features.RVC_ENABLED`, driven by the bundled `scarlet-features.properties` resource (stripped by the lite shade execution)
  - Added `tts_rvc_pitch` setting (range -24..+24 semitones) under Settings -> Text-to-Speech for tuning the RVC model's pitch offset when the TTS voice's fundamental doesn't match what the model was trained on
  - Added `--models-dir` flag to the RVC Python bridge so it finds models even when the bridge is extracted to a different subtree than the user's models directory
  - Added stem-contains matching for `.index` files so a model and its retrieval index are paired even when the filenames don't match exactly
  - Added automatic refresh of classpath-extracted bridge assets on startup so upgraded Python bridges take effect without requiring users to delete the AppData copy
  - Added per-install IPC authentication tokens so local IPC commands are no longer accepted unauthenticated by default
  - Added public-only outbound URL validation to block SSRF-style fetches against private, loopback, and local network targets across GIF conversion and import paths
  - Added strict GIF safety limits covering payload size, frame count, dimensions, and total pixel area to reduce memory/CPU denial-of-service risk
  - Added path sanitization and root-boundary checks for evidence exports so attachment-driven filenames cannot escape the configured evidence directory
  - Added explicit interaction ownership checks for pending moderation and instance-creation flows so one Discord user cannot hijack another user's in-progress controls
  - Added runtime permission re-checks on privileged Discord UI actions such as redact/unredact, tag editing, notes edits, watched-group imports, report-template changes, aux webhook changes, and moderation actions
  - Added safer interaction defaults by moving buttons/selects/modals onto explicit known-safe allowlists instead of framework-wide implicit allow
  - Added hardened encrypted-preferences migration with automatic legacy wrapper detection, backup export, and rewrapping under the current per-install secret
  - Added automatic recovery of older Discord and VRChat credentials by migrating legacy encrypted preference nodes to the current secret wrapper
  - Added startup backup warnings before secure-store initialization, including current and legacy data locations to copy before migration runs
  - Added startup diagnostics for secure preference initialization so stalls now log the current stage and worker-thread location instead of appearing frozen
  - Added deferred secure-store opening during VRChat startup so early splash progress remains visible before encrypted credential access begins
  - Added security regression checks covering encrypted preference round-trips, legacy wrapper migration, and public URL validation
  - Fixed RVC conversion failing with `_pickle.UnpicklingError: Weights only load failed` on PyTorch 2.6+ by transparently restoring `torch.load`'s `weights_only=False` default and allowlisting `fairseq.data.dictionary.Dictionary` via `torch.serialization.add_safe_globals`
  - Fixed RVC conversion failing with `Expected a JsonObject but was JsonPrimitive` when rvc-python progress prints leaked onto stdout - the bridge now routes library stdout to stderr during inference, and Java parses the last balanced JSON object rather than the whole buffer as a defence-in-depth measure
  - Fixed dependency-install wheel picker falling back to CPU on CUDA 13.0 because of a broken version comparison; now uses tuple comparison and correctly selects `cu124` for CUDA >= 12.4
  - Fixed unnecessary torch reinstall causing `WinError 5 - Access is denied` on Windows when the DLLs were already loaded; the install step is now skipped entirely when both `torch` and `torchaudio` are already present
  - Fixed post-install `_try_import` checks reporting packages as missing due to stale `importlib` finder / metadata caches; caches are now invalidated after every pip run
  - Fixed `torchcodec` incorrectly gating `rvc_compatible` in `--status` output; it is now in the optional package set (rvc-python 0.1.5 does not import it)
  - Fixed RVC-related settings appearing in the UI of the lite edition - they are now filtered out when `Features.RVC_ENABLED` is false
  - Fixed `EncryptedPrefs` decrypt flow so secure preferences actually decrypt in decrypt mode, and removed pointless base64-wrapping of plaintext before encryption
  - Fixed legacy secret-at-rest protection relying on a universal default fallback by moving to a persisted random per-install fallback secret
  - Fixed unauthenticated local IPC control on Windows named pipes and Unix sockets from being accepted without a secret by default
  - Fixed `/vrchat-animated-emoji from-url` and related remote-fetch paths accepting arbitrary internal/private targets
  - Fixed evidence file writes being vulnerable to path traversal through templated or attachment-derived names
  - Fixed privileged Discord interaction handlers trusting the UI layer too much by enforcing authorization again at execution time
  - Fixed pending moderation state and instance-creation state being reusable by other users who could trigger the same custom IDs
  - Fixed runtime dependency bootstrap trusting downloaded jars too easily by disabling it by default and validating remote checksums when enabled
  - Fixed startup appearing to freeze with no clue when secure preferences are slow or blocked by adding staged logging and splash progress around secure-store work
  - Fixed `libdave-jvm-master/CMakeLists.txt` referencing `src/main/cpp`, `boringssl`, `mlspp`, `libdave/cpp`, `cmake`, and `json-cpp` at the project root - the actual source tree lives under `natives/` - so the Android-aware root CMakeLists can now find the sources it needs when invoked by the `android-dave-native` Maven profile
  - Fixed `DaveLibraryLoader.handleMissingDependency()` on Termux now detecting glibc-only bundled binaries (`libc.so.6`) and surfacing an "incompatible native library" dialog instead of trying to run `sudo apt install libopus0`
  - Updated `vrchatapi-java` to `1.20.8-nightly.14`
  - Fixed the Settings UI occasionally painting card contents outside the scroll viewport during rapid resize/scroll, which could draw controls over the tab strip, search row, or footer
  - Fixed `java.io.IOException: The handle is invalid` being thrown ten times a second from `Scarlet.spin()` on Windows when the user launched Scarlet without an attached console (double-clicked JAR via `javaw.exe`, Windows shortcut, Task Scheduler, detached launch) - the CLI reader now detects the invalid stdin handle on first failure, logs it once at INFO, and skips further `System.in.available()` polling for the session
  - Fixed `ErrorResponseException: -1: java.io.InterruptedIOException` being logged at ERROR when closing Scarlet while JDA still had in-flight command-registration REST calls (triggered by `updateCommandList()` after a version change) - installed a custom `RestAction.setDefaultFailure` handler in `ScarletDiscordJDA` that routes `InterruptedIOException` down to DEBUG and delegates all other failures to JDA's original default handler
  - Fixed inverted `awaitShutdown` condition in `ScarletDiscordJDA.close()` - the code was force-shutting-down JDA only after graceful shutdown had already succeeded (no-op) and doing nothing when the 10s timeout elapsed (leaving pending requests hanging); now correctly force-shuts-down on timeout and preserves the thread interrupt status on `InterruptedException`
  - Fixed RVC, TTS, and xdg-utils dependency-installer dialogs having their Yes/No buttons clipped off the bottom of the screen on Windows at elevated display scale (125%/150%/200%)
  - Fixed RVC dependency installation failing with "pip reported success installing rvc-python but the package is still not importable" on fresh Python 3.10/3.11 installs by adding compatibility shims for `collections.abc`, Python 3.11 dataclass defaults, and better import-error surfacing in the bridge
  - Fixed `_try_import` silently discarding exception details - the bridge now records the actual exception into a process-wide `_IMPORT_ERRORS` map and surfaces broken imports in `--status`
  - Changed `TtsService` RVC accessors (`isRvcAvailable`, `getRvcStatus`, `getRvcModelsDir`, `getRvcModels`, `setRvcConfig`) to null-safe so a null `RvcService` in the lite edition is handled gracefully
  - Changed `RvcService.getResourcePath()` to prefer a fresh classpath extraction over the AppData cache, so bundled bridge upgrades take effect on the next launch
  - Indefinitely postponed: Support for multiple groups
  - Pending: Staff & Instance Analysis, (live infographic?)
  - Pending: Limited Google Drive interoperability
  - Pending: Distinct Server and Client modes
## 0.4.16-b4
  - Added built-in `/discord-kick` and `/discord-ban` Discord slash commands using the native Discord member picker
  - Added optional reason parameter to `/discord-kick` and `/discord-ban`
  - Added DM notification sent to the target user before a kick or ban is carried out
  - Added one-time opt-in prompt on first startup asking whether to enable the built-in Discord kick/ban commands
  - Added `discord_kick_ban_enabled` toggle under Settings Ã¢â€ â€™ Discord
  - Added ephemeral response when `/discord-kick` or `/discord-ban` is used while the feature is disabled, directing the user to Settings
  - Added in-app CLI panel as a dedicated "CLI" tab in the Scarlet UI, with a terminal-style read-only output area and a command input field
  - Added `rawCommand` output routing so CLI results appear in the in-app panel as well as the system log
  - Added "Run CLI command" entry under Settings Ã¢â€ â€™ CLI for running commands via a popup dialog
  - Added startup banner to the console showing the Scarlet version and a hint to type `help`
  - Fixed CLI `help` command output not being printed to the console
  - Improved logging for Discord kick and ban actions (actor, target, guild, reason all recorded at INFO level)

## 0.4.12
  - Added User Spawn Prop extended event
  - Added notes field for watched entities and groups
  - Added autocomplete for watched entity and group ids
  - Added filter to narrow potential avatars based on author id
  - Updated JDA to 5.6.1 (was 5.2.1)
  - Fixed custom Emojis not being properly detected
  - Fixed some text not being sanitized for Markdown
  - Fixed some events not firing on player joins
  - Fixed watched entities not loading or importing
  - Fixed watched users commands failing and having incorrect links

## 0.4.12-rc8
  - Fixed error in output for banning or unbanning multiple users with the appropriate Discord command
  - Fixed error when custom moderation tags is exactly a multiple of 25

## 0.4.12-rc7
  - Added User Spawn Emoji and Watched Moderation extended events
  - Added Ban User and Unban User buttons to several event embeds (User Switch Avatar, User Spawn Pedestal/Sticker/Print/Emoji)
  - Added Watched Users and Watched Avatars
  - Added user current avatar name to Instance UI
  - Changed maxium number of custom moderation tags to 125 (was 25)
  - Fixed watched entity imports via Discord command using the incorrect option name
  - Fixed mislabeled Discord command descriptions
  - Fixed silenced watched groups overriding TTS messages for lower priority groups

## 0.4.12-rc6
  - Added links to the Scarlet VRChat Group
  - Added Group Invites created to Moderation Summary
  - Added option to skip confirmation to send a group invite to players via Instance UI
  - Added deep equality check to avoid editing Discord commands with identical content
  - Changed the `Edit tags` button on moderation events to auto-populate currently assigned tags
  - Fixed custom moderation tags with long descriptions causing some Discord interactions to fail
  - Fixed Instance UI failing to send group invites
  - Fixed instability caused by unexpected/unknown API enum values failing deserialization

## 0.4.12-rc5
  - Added `tag-immediately` option to `vrchat-user-ban` Discord slash command, allowing user to specify tags and description before banning
  - Added user pronouns and status description to moderation event embed
  - Added Invite to Group button to Instance UI
  - Added API call rate limit to Instance UI
  - Changed Instance UI to skip updating during preamble
  - Fixed `permissions` autocomplete suggestions missing for Discord interactions
  - Fixed VRChat Help Desk autofill links missing most information
  - Fixed sticker images not showing up in the User Spawn Sticker extended event embed

## 0.4.12-rc4
  - Added Outstanding Moderation and Action Failure extended events
  - Added `outstanding-moderation`, `submit-evidence`, `vrchat-user-ban-multi`, and `vrchat-user-unban-multi` Discord slash commands
  - Added optional extra filter step to narrow down potential avatars from User Switch Avatar extended event
  - Removed unloaded libraries from distribution
  - Changed api cache default max age
  - Fixed bugs with the intended ephemerality of Discord responses being ignored
  - Fixed bugs with ipc-piped commands not executing

## 0.4.12-rc3
  - Added setting to configure which VRCX-compatible avatar search providers to use
  - Added cli commands via named pipe
  - Added report links to various events and search results
  - Added button to view potential avatar matches for easier manual correlation
  - Changed VRChat Client launching to use URI on non-Windows platforms

## 0.4.12-rc2
  - Added avatar thumbnail to User Switch Avatar extended event (not foolproof, can be default/previous avatar image)
  - Updated VRChat Help Desk autofill links to account for recent changes
  - Fixed bugs with bundled bans/kicks

## 0.4.12-rc1
  - Added setting to launch a new VRChat Client instance into newly created group instances
  - Added option to assign/remove a VRChat Group Role when adding/removing staff from lists
  - Added Discord subcommands `add-role` and `remove-role` to `vrchat-group`
  - Added Discord message command `fix-audit-thread` to be used when moderation event messages fail to populate
  - Changed Discord command `query-target-history` to redact secret staff names in event description
  - Fixed typo in event log messages description header

## 0.4.11
  - Added User Spawn Print extended event
  - Added Bundle ID to User Switch Avatar extended event (when available)
  - Added user age verification status to Instance UI
  - Added title to runner

## 0.4.11-rc11
  - Added User Spawn Pedestal, User Spawn Sticker, and Suggested Moderation extended events
  - Added autocomplete to VRChat User and VRChat World options for Discord commands
  - Changed default Discord commands permissions to `USE_APPLICATION_COMMANDS`

## 0.4.11-rc10
  - Added ban/unban buttons to Vote-to-Kick log entries
  - Added snapshot repository to dependency downloader
  - Changed periodic report to include group joins, leaves, and kicks
  - Fixed crash loading permissions data
  - Fixed bug with empty pagination

## 0.4.11-rc9
  - Internal overhaul of all Discord commands and interactions
  - Added the `/scarlet-discord-permissions` Discord command
  - Added the `/vrchat-search` Discord command
  - Added file lock so multiple instances of Scarlet do not run for the same group at the same time on a given system
  - Added Discord commands (`vrchat-animated-emoji`) that convert gifs into spritesheets for VRChat Gallery animated emoji
  - Removed the `/scarlet-permissions` Discord command (old configuration will not be lost if you update)
  - Changed the `/watched-groups` Discord command to consolidate subcommands
  - Changed Discord command default permissions to requiring only either `MODERATE_MEMBERS` or `MANAGE_SERVER`, depending on the sensitivity of the command
  - Fixed selector in settings tab for evidence root not having any effect

## 0.4.11-rc8
  - Added functionality to paginated Discord command responses
  - Changed WorldBalancer domain from `avatarwb.worldbalancer.duia.us` to `avatar.worldbalancer.com`
  - Fixed `/(secret-)staff-list list` command sometimes failing

## 0.4.11-rc7
  - Added input sanitization/resolution for many Discord commands to accept many intuitive forms for groups, users, worlds, etc. (VRChat Web links, group short codes, etc.)
  - Added `/vrchat-group` Discord command, with `create-instance` and `close-instance` subcommands
  - Added customizability for JVM options via `./java.options` file
  - Removed attachment containing user state to moderation events (will rework to improve usability)

## 0.4.11-rc6
  - Fixed TTS not outputting to Discord voice channel
  - Fixed versions being both missing and in reverse order in `/server-restart` Discord command autocomplete

## 0.4.11-rc5
  - Added tentative support for tailing the VRChat client logs on Linux
  - Added setting to modify the audit polling interval from the canonical 60 seconds
  - Fixed some embeds not having linked titles
  - Fixed secret channels not being set

## 0.4.11-rc4
  - Fixed duplicate avatar ids being logged
  - Fixed embed field having too many characters
  - Fixed misleading output from `run.bat`
  - Fixed an infinite loop of failure `run.bat`

## 0.4.11-rc3
  - Added update option to `/server-restart` Discord command
  - Fixed duplicate alternate field in AvatarSearch.VrcxAvatar

## 0.4.11-rc2
  - Added naive in-memory cache for avatar searches
  - Fixed IllegalArgumentException in TTSService
  - Fixed NullPointerException in staff lists
  - Fixed StackOverflowError in ScarletData

## 0.4.11-rc1
  - Added extended events: User Join, User Leave, User Switch Avatar, and Instance Monitor
  - Added avatar search support (only exposed internally for now)
  - Added support for auto restarting (when runner is used)
  - Added secret staff list: events actored by these users can be diverted to other channels and are not visible to normal staff under most conditions
  - Changed handling of Vote-to-Kick Initiated extended event to properly handle initiator name when applicable
  - Changed Moderation Report extended event to include totals and optionally omit staff without activity
  - Removed the `associate-ids` Discord command in deference to using the staff commands
  - Fixed some extended events not being sent to auxiliary webhooks
  - Fixed Markdown escaping for display names in multiple places

## 0.4.10
  - Added UI scaling
  - Added `joins` field to moderation summary
  - Changed templating for VRChat Help Desk autofill to optionally append footer
  - Changed TTS to replace some special Unicode characters before generating audio
  - Added primitive input sanitization of the Group ID
  - Fixed some users having negative account age in Instance UI
  - Fixed AcctAge column of Instance UI comparing by textual instead of numerical value

## 0.4.10-rc5
  - Added daily moderation summary with configurable time-of-day to generate
  - Added templating for VRChat Help Desk autofill
  - Added TTS announcements for Votes-to-Kick Initiated
  - Added tentative search of the system path for different PowerShell executable names

## 0.4.10-rc4
  - Added location to extended event embeds
  - Changed evidence submission to not require a direct reply, but instead to just be in the same thread

## 0.4.10-rc3
  - Added settings for enabling and selecting the evidence submission folder
  - Added listing auxiliary webhooks to `config-info`
  - Fixed some settings not getting created as default if they didn't exist

## 0.4.10-rc2
  - Fixed Email OTP (again)
  - Added Instance Inactive extended event
  - Staff mode now properly ignores audit log polling and related systems
  - Fixed app hanging on startup if no VRChat logs were found
  - Fixed log parsing for Vote-to-Kick Initiated
  - Fixed data not getting saved
  - Fixed the `/set-audit-ex-channel` having a typo in one of its options

## 0.4.10-rc1
  - Added staff list command `/staff-list`
  - Added extended events: Staff Join, Staff Leave, and Vote-to-Kick Initiated
  - Added audit event bundling: if a User Ban results in a Instance Kick, then the latter is logged with the former by default
  - Changed the command suggestions to display more human-friendly names over internal identifiers
  - Fixed Discord roles being mistakenly formatted like Discord users
  - Fixed moderation events initiated with commands or buttons not properly referencing the initiating user

## 0.4.9
  - Added ability to redact audit events: they do not count in the running sum of moderation events for embeds
  - Fixed autocomplete for the `scarlet-permission` Discord command not working
  - Fixed manual command update button not working

## 0.4.9-rc3
  - Added ability to revert instance UI to default ordering by right clicking header
  - Changed Discord command `set-permission-role` to `scarlet-permission` and added support for multiple roles
  - Fixed bug (#9) that tried to set a Discord embed field value to more than 1024 characters

## 0.4.9-rc2
  - Added application version change detection
  - Changed Discord bot to only send command list if version changes
  - Changed instance UI to only sort once the log tailing/parsing has caught up 
  - Changed watched groups sort order to prioritize `priority` instead of the flags `critical` and `silent`
  - Fixed NPE when emitting aux webhooks for certain events
  - Fixed watched groups added via Discord commands missing the `id` field, causing them to magically disappear

## 0.4.9-rc1
  - Fixed bug: Discord bot responds properly to the `set-audit-aux-webhooks` command
  - Updated dependency: com.github.vrchatapi:vrchatapi-java:1.18.9 to version 1.19.1

## 0.4.8
  - Added ability to silence TTS messages for particular groups
  - Added ability to import watched groups from UI
  - Fixed bug: removed references to restricted/internal api
  - Fixed regression: switched emailotp and totp auth flows

## 0.4.8-rc1
  - Added experimental "staff mode" (discord integration disabled, data like watched groups must be synced manually)
  - Added ability to ban and unban VRChat users with desktop UI, Discord buttons, and Discord commands
  - Added ability to disable pings on Discord for moderation audit events
  - Added priority system to groups
  - Added default sorting for instance UI
  - Added colored text based on group watch type for instance UI
  - Added ability to forward audit event messages to other Discord servers via webhooks
  - Added AuditID to autopopulation of description field for VRChat Help Desk Links
  - Added support for Email OTP
  - Fixed TTS subprocess hang if selected TTS voice isn't installed on the system
  - Fixed regression: implicit class loading from main class happened before dependencies were downloaded

## 0.4.7
  - Fixed regression: TOTP codes incorrectly reporting as invalid

## 0.4.6
  - Added splash screen
  - Added update check popup
  - Added automatic disabling log parser if VRChat client not present
  - Fixed auth refresh not triggering
  - Fixed setting TTS voice not working or persisting
  - Changed UI theming

## 0.4.6-rc1
  - Added list of users in current instance to the UI
  - Added various links to the UI
  - Added ability for group to autopopulate email field for VRChat Help Desk Links
  - Fixed watched groups not being properly detected

## 0.4.5
  - Added caching layer for some VRChat API requests
  - Added some autocomplete for the `watched-group` command
  - Added initial text `Unclaimed` to new moderation events
  - Fixed missing reprompt on invalid credentials
  - Fixed incorrect gateway intent in instructions

## 0.4.5-rc4
  - Added ability to specify where Scarlet stores data
  - Added ability to select a TTS voice
  - Added Discord commands to configure watched groups
  - Added Discord command to export Scarlet logs
  - Added dummy UI for ease of exiting the application
  - Added default command permissions for Discord commands
  - Changed log name format for JDA classes

## 0.4.5-rc1
  - Fixed NPE when checking if a joining player has watched groups

## 0.4.4
  - Fixed importing legacy CSV list of watched groups (Excel format, instead of RFC4180)

## 0.4.3
  - Added step to installation instructions to make discord bot private
  - Added newer version check
  - Added more build automation
  - Fixed discord bot responding to interactions from servers other than the server specified in configuration
  - Fixed discord bot sometimes not automatically rejoining voice channel
  - Fixed some errors not printing to log file
  - Changed link to download a JDK from potentially confusing Github link to Adoptium website proper

## 0.4.2
  - Added important step to installation instructions
  - Added `pause` line to runner
  - Removed input prompt for Discord audio channel snowflake
  - Fixed bug in dependency downloader not properly unescaping characters
  - Changed Java version check to warn instead of terminating application

## 0.4.1
  - Added logger thread and log files
  - Added importing watched groups legacy CSV via attachment submissions
  - Fixed bug where the process could hang upon quitting
  - Fixed typos and missing comments
  - Changed some logging levels
  - Changed handling of tags for importing watched groups legacy CSV

## 0.4.0
  - Added installation and usage instructions to README.md
  - Added CHANGELOG.md
  - Added SETTINGS.md
  - Added automation for building a zip
  - Added runtime check for JVM's Java version
  - Added ability to automatically generate TOTP code from secret
  - Added link and message reference to group audit event `group.instance.close`
  - Added TTSService to announce events in a Discord voice channel
  - Added VRChat log tailing/parsing
  - Added Watched Groups, which generate TTS announcements when members' joining is detected
  - Removed unused code and resources from automatic dependency downloader
  - Renamed `submit-evidence` Discord message command to `submit-attachments`
  - Fixed audit event embed color overrides being ignored
  - Fixed typos

## 0.3.3
  - Fixed escaping for VRChat Help Desk autofill link generation
  -
