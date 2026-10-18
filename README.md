<img src="images/sybyline_scarlet.png?raw=true" alt="Scarlet Logger logo" width="300" height="300"/>

# Hatson321/Scarlet Logger

A self-hostable VRChat Group management utility with Discord integration.

> scarlet is now under a new maintainer hatson321 ai is used to assist in the api updates and gets proof read by hatson321 and the other scarlet admin team members before release. tests are performed as well before release to ensure safe operational status and a fully detailed update html is provided disclosing what was changed and tested.

### Project history and acknowledgments

The original fork's attribution is preserved below as historical context. Current maintenance is described above.

> **About this fork.** Scarlet (this fork) is maintained by **KozyBlake** and developed largely with AI assistance — a "vibe-coded" project. Features and fixes come from KozyBlake and community ideas, implemented and reasoned through with an AI pair, then reviewed and built by KozyBlake. On first launch Scarlet shows this notice and lets you close it if running AI-assisted software isn't for you. (KozyBlake maintains and is accountable for the project; the AI is a tool, not the maintainer.)

- [Maintained repository](https://github.com/Hatson321/Scarlet)
- [Changelog](CHANGELOG.md)
- [0.6.0 release notes](RELEASE_NOTES_0.6.0.md) — prepared locally; publication is a separate step.
- [Settings](SETTINGS.md)
- [Frequently Asked Questions](FAQ.md)
- [Installation](#installation)
- [Building from source](BUILDING.md)

## Updating to 0.6.0

Close the old Scarlet process and keep an independent backup before launching
`scarlet-0.6.0.jar`. One JAR supports Windows and Linux. A new filename does not
create a separate data directory.

A10 and later compare numbered build suffixes numerically, so A11 sorts after A10.
Older builds retain their old comparison behavior; install this JAR manually if
your existing copy does not recognize it as newer.

### VRChat launcher safety — A13

Scarlet no longer closes VRChat by process name or force-kills it to follow an
instance. A restart requires a client whose launch ancestry, process ID, start
time and owner Scarlet can verify. It requests a normal close and waits for a
confirmed exit before starting again. Unknown or ambiguous ownership means no
automatic restart; close that client yourself or use an in-game invite instead.
Steam/URI launches that cannot provide launch ancestry remain unowned.

Only one launch lifecycle can run at a time, including its startup wait.
Automatic launch waits run separately from moderation logging, and extra launch
requests are skipped rather than queued. The instance-creation wizard still
does not restart an already-running client. These checks do not establish that
Easy Anti-Cheat has finished its own cleanup or diagnose an EAC failure.

### Release-review safety fixes — A12

A12 rechecks the requesting moderator's current authority before queued bulk
unbans and before applying changes from an open Discord dialog. A dialog can
expire, and old unban queue entries without verifiable requester/group context
are rejected instead of being executed with the bot's privileges.

Unreadable existing saved records are not treated as empty records and overwritten.
This also covers settings, Discord configuration and permissions, schedules, watched
lists and the regular staff list. The affected store refuses writes until a valid
load/recovery, and unreadable permission rules cannot silently become default access.
The original bytes remain available for recovery. Settings can still recover from
a valid existing backup; failed settings saves now report an error rather than
silently claiming success. Pending in-memory settings can be retried after storage
is repaired. Redaction updates modify only their own fields, preserving concurrent
changes to other saved moderation details.
Backup/import plaintext staging requires enforceable private filesystem permissions;
if those permissions cannot be established, the operation stops rather than
silently creating sensitive temporary files in a shared location.

Optional idle-instance auto-close now requires a healthy, current log session,
the configured group, the logged-in bot alone in the local roster, and a fresh
server occupancy check. Uncertainty restarts the grace period. It requests a
normal soft close, not a hard close that ejects occupants. Occupancy and closing
cannot be one atomic VRChat API operation, so a player can still join in between.
This is separate from the optional feature that exits Scarlet's managed VRChat
client after an already-closed instance becomes empty.

For a source build, use the entrypoints in [BUILDING.md](BUILDING.md), including
the pinned upstream VRChat SDK bootstrap. These changes do not remove the
supervised release checks in [BETA_ACCEPTANCE.md](BETA_ACCEPTANCE.md).

### Saved-history moderation summaries

`moderation-summary` and scheduled moderation summaries now read Scarlet's saved
audit records on this computer. They do not download VRChat audit history, scan
Discord messages, or fetch staff profiles. A dedicated background worker keeps the
scan off Scarlet's normal logging and command-handling paths. One summary can run
at a time; additional requests receive a busy response. Shutdown cancels the worker.

Summaries are scoped to the current group and include only visible, non-training
records. Duplicate audit receipts count once; a related ban and kick still count
as different events. Secret staff and redacted records remain hidden. Unprocessed
outbox-only entries are omitted until attribution has been resolved. Local join
records without a matching group are omitted rather than guessed.

The result is labeled saved local history: missing, deleted, never-collected or
still-unprocessed events cannot be counted. Even an empty result does not prove
that no activity occurred. Unreadable files are skipped and contribute nothing to
moderation counts. The final notice states how many files could not be read and
were excluded, with safe file details in Scarlet's local logs. Such results are
labeled partial because the skipped files may contain additional actions.
An unreadable audit record cannot be counted using another saved copy whose
privacy information cannot be trusted. Unsafe directories, privacy conflicts,
cancellation or resource limits still prevent totals from being shown.
The reader is bounded to five
minutes, 250,000 directory entries, 100,000 selected records, 8 MiB per file and
2 GiB total read. A shorter date range may reduce selected records and join reads,
but the unindexed audit folder still has to be scanned.

The separate `actor-moderation-summary` and `outstanding-moderation` commands still
use VRChat audit queries. Their behavior is not changed by the local summary feature.

### Full instance links

`vrchat-group close-instance` now accepts complete official VRChat launch links
with bare flags such as `~ageGate`, URL-encoded parameters, or reversed query order.
It also accepts a full raw location. A world ID alone or a short link is not enough:
provide both the world and complete instance ID. Invalid links and links for another
group are rejected before calling the close API. Existing permission checks remain.

A10 fixes date-only account-join values in returned instance user details, which
could previously make a successful close look like a failure. Timed closes show
the scheduled time; an immediate close and an already-closed response have distinct
messages. Other permission errors remain errors. If a response still cannot be
read, Scarlet warns that the outcome is uncertain and does not automatically
resend the close request. Check the instance status before retrying that warning.
The date compatibility rule is limited to the nested account-join field; closing
timestamps and other event timestamps remain strict.

### Privacy-filtered close diagnostics — A11

An unexplained HTTP 403 now gets a neutral reply with a reference ID, rather than
suggesting that missing permissions are the established cause. Match that reference
to the local `Close-instance request`, `Close-instance response`, and
`Instance-close outcome` entries. Requested hard-close and closing-time options
remain visible even when a request is rejected. Omitted options are labeled:
normal soft close, immediately. The effective closing time is a separate field:
after an accepted request it uses the returned time, or the requested time when
the response omits it. A rejected request leaves that result field empty.

Only bounded, structured error fields are considered for the local diagnostic.
The message uses a restricted diagnostic vocabulary: unknown words and sensitive
values are masked. It is not a verbatim server response, and some reasons may
remain unclear. Headers, cookies, other response fields, and raw launch links are
not included in these new diagnostic entries. Discord receives the reference ID,
not the server message. Review even the filtered log excerpt before sharing it.

This is a diagnostic update, not a confirmed fix for the tester's unexplained 403.
The request target, permission checks, and strict already-closed recognition are
unchanged. The command does not automatically resubmit a failed or uncertain close;
the HTTP client's existing connection-recovery behavior is unchanged. Check the
instance's actual state before any deliberate retry. A normal close blocks new
joins; it does not automatically eject players who are already inside.

### Gentler audit fetching

Audit-history GET requests share a single-permit pacer across API-backed queries, normal
logging, counts and compatibility fallbacks: at most one request every two seconds.
Local moderation summaries no longer use this pacer. Other API endpoint pacing is unchanged.

A VRChat 429 applies at least a 30-second shared cooldown, including a zero or
missing `Retry-After` value. Repeated limits increase the cooldown, and longer
server-requested waits are honored. Waiting calls can be cancelled and do not
release a refilled burst immediately after the cooldown.

The bounded historical-query helper remains available, but local summaries do not
call it. Routine polling and moderation writes are not automatically retried by
this policy. Pacing cannot guarantee avoidance of all service limits.

### Moderation delivery improvements

A kick bundled into a ban thread waits durably while that thread is being prepared,
without treating the normal wait as a failed delivery or a channel-permission warning.
It becomes eligible for delivery when the thread is ready; periodic recovery still
handles restarts or missed wake-ups. Actual delivery failures retain their warnings
and retry backoff.

The three independent moderation snapshot lookups use bounded parallelism while
retaining privacy filtering, shared API rate limiting and cancellation safeguards.
This can reduce preparation time; it does not guarantee a particular latency or fix
authentication failures. The actual VRChat ban operation and polling cadence are unchanged.

### Background moderation threads

Moderation threads and their action buttons now build in the background after the
main Discord embed is sent. Unfinished work remains in Scarlet's local delivery queue
for retry after failures or restart, without deliberately reposting an acknowledged embed.
Current privacy and destination checks still apply. Normal shutdown stops this worker
before saving data or handing off to offline export/import.

This removes thread-building work from the audit polling path; it does not change the
polling intervals or guarantee an instant embed. Existing profile/snapshot requests and
VRChat/Discord response times can still delay a single ban embed. Local timing entries
help distinguish preparation, primary delivery, and background follow-up time.

### Optional VRChat client exit

In Settings, enable **VRChat Client: Exit when instance is closed and empty
(Scarlet-launched clients only)** (`vrchat_client_exit_closed_empty`). It is off by default.
Enable it before launching the client through Scarlet; process discovery is limited to
the first two minutes after that launch request.
This is separate from the existing setting that closes the bot's own instance remotely.

The new option requests a normal exit of a local client launched by this Scarlet session
only after its exact monitored group instance is confirmed closed and the live player
list contains only that client's authenticated account. An open empty instance never
qualifies. Scarlet waits 30 seconds and checks fresh server instance information before
sending the exit request. A scheduled future closure is not treated as already closed.
Scarlet itself stays running, and this option does not close the instance on the server.

The client must have been newly launched through Scarlet; an already-running or manually
started client is not deliberately adopted. Launch tracking correlates new process identity
with Scarlet's launch request, not a guaranteed Steam process ancestry chain. Ambiguous
processes, stale/incomplete logs, missing audit or server proof, another player, a world or
account change, or unavailable process metadata leave the client running. Linux/Wine
configurations that hide the executable or start time may therefore not support this option.
No administrator elevation, forced termination, or name-wide process termination is used
by this feature. A normal-exit request may be declined by the client; there is no force fallback.

The existing snapshot-privacy and timed-ban safeguards from 0.5.0-a3 remain:

Moderation snapshot buttons now display only approved public profile/group fields
and check the current event access policy. Old local snapshot files are not scrubbed;
keep those files and their backups private. Group snapshots with missing or uncertain
visibility information may now show no verified public information.

Automatic timed unbans now require the original ban's server identity and a fresh,
complete audit-history check. **Timers created before this safeguard cannot establish
that identity and require manual review; Scarlet leaves the current ban in place.**
A replacement or ambiguous ban is retained for review rather than automatically lifted.
Temporary verification failures delay the check by five minutes. Review the current
group ban and audit history before manually unbanning anyone. The API cannot make
the final verification and unban atomic, so a simultaneous external moderation change
is still a limitation.

## Restore and consistent export — 0.5.0-a1

On a fresh or wiped installation, Scarlet offers **Restore from export / backup** before
asking for account credentials. Choose a Scarlet migration export or Scarlet's automatic
pre-import backup ZIP, confirm the destination, and enter the export PIN if requested.
The normal restore includes settings, channel mappings and saved credentials. After success,
launch Scarlet normally. Expired sessions or invalid credentials can still require sign-in or 2FA.
Generic ZIPs, individual `.bak` files and manually created `.tar.gz` backups are not migration bundles.
Cancelling or failing a first-run restore does not continue into account setup automatically.

From an existing installation, Settings → Backup & Migration → Import or Export asks to
**stop and save Scarlet first**. A separate offline tool waits for the previous process to exit
and locks Scarlet's data and shared preference storage. Logging remains stopped until you
start Scarlet again; do not assume that every live event during this gap can be recovered.
Do not run older Scarlet builds or external file-editing tools against the same data during migration.

Exports require a new filename outside the data folder and a PIN. The tool verifies the
encrypted archive before offering the separately confirmed, delayed **Remove all local data**
action. Optional log/cache/TTS cleanup is another explicit deletion: cancelling a later export
does not undo cleanup. Keep an independent backup until restoration and sign-in have been checked.
The wipe removes Scarlet's local files and saved credentials; it is not forensic secure erasure.

For a desktop restore without starting the logger, run from the build's directory, preserving
the same data location used by your launcher:

```text
java -DSCARLET_HOME="<Scarlet-data-folder>" -jar "scarlet-0.6.0.jar" --restore
java -DSCARLET_HOME="<Scarlet-data-folder>" -jar "scarlet-0.6.0.jar" --export
```

The existing `--import-bundle "<bundle.zip>"` console workflow remains available.
`--data-only` excludes incoming credential stores and preserves the destination's existing
legacy Discord token instead of copying the token embedded in the incoming configuration.

---

## Why Scarlet?

Scarlet is self-hosted, meaning you have complete control of your group's data.  
Your VRChat and Discord credentials are held only on your own machine — they are never sent to a third party, and no Scarlet-operated service ever receives them.  
Since there is no automatic synchronization of data between groups running Scarlet, you don't have to worry about other groups seeing who your group has moderated or what groups you are tracking.

Scarlet does talk to outside services — VRChat and Discord because that is the job, plus features such as avatar search, translation providers, media lookup, and update checks listed in [SECURITY.md](SECURITY.md#1-network-connections). Defaults and triggers differ by feature; they are not all manual-only. **External avatar-database lookups are off until the owner grants permission in Avatar search providers → Manage.** After permission, selected databases may be queried automatically during avatar changes and missing-information rescans as well as manual searches. Uncheck permission or all providers to stop future lookups. The Android/mobile companion and every desktop mobile-delivery path have been removed from active Scarlet. If a build behaves differently from that list, treat the list as the claim and the build as the thing to check.

**Avatar-query identification:** the provider User-Agent includes the running Scarlet version, public maintainer support contacts, the configured VRChat group ID and the signed-in primary VRChat account ID. It does not include login credentials. Group/account IDs are supplied automatically by the installation, not by a search target or avatar creator. This format uses a new permission (`avatar_search_identified_lookups_enabled`, default false), so existing owners must review the updated notice and opt in again before provider queries resume. Unrelated HTTP requests keep their existing non-identifying header.

---

## Highlights

- **Cross-platform.** Runs on Windows and Linux (this is the Linux-compatible fork). There is also an **experimental PowerPC edition** (`scarlet-<version>-ppc.jar`) for 32-bit PowerPC Linux; it is heavily untested and has so far only been run on a Nintendo Wii running Wii-Linux. The desktop app is Java/Swing; the VRChat-log features work when VRChat runs on the same machine, including under Proton/Wine on Linux.
- **Near-instant moderation posts.** Moderation actions (Discord command, UI, or bulk-ban queue) and in-game warns/kicks/bans post to Discord within seconds via burst polling, then settle back to the idle interval.
- **Watched users, groups, and avatars** with per-category advisories, spoken TTS callouts, and native desktop notifications — each independently toggleable.
- **Avatar statuses with or without VRChat's `[API]` launch options.** When API-log information is missing and the owner permits external avatar-database lookups, Scarlet can attempt reconstruction through selected databases plus current-avatar-image confirmation and the VRChat API. Opting out leaves those external fallback lookups disabled; private, ambiguous or unindexed avatars may remain unresolved.
- **Instance creation and management** for both group instances (Public/Group+/Members) and personal instances (Public/Friends+/Friends/Invite+/Invite), with queue/age-gate/content presets, invite-a-friend, and self-invite when VRChat is already running.
- **Multi-language UI (English and Russian complete).** The whole desktop client — including spoken TTS callouts — follows your OS language or a chosen one; the Discord bot replies in each user's own Discord locale. Community translations drop into a `lang/` folder with no rebuild.
- **Training mode.** A settings toggle enables an event simulator so a trainer can run realistic drills — including practicing the Discord tagging workflow — without anyone joining a bad group, fully isolated from live moderation (see **Training mode** below).
- **Reliability watchdogs.** Scarlet detects a stalled VRChat log tailer or an expired session, attempts unattended re-login, and can route health alerts to a staff Discord channel — so it never fails silently. Data files save crash-safely with dated backups.
- **Migration bundles, a diagnostics view, and a searchable friend/invite picker** round out the desktop tooling.

---

## Features

### Discord Commands

Arguments in `<>` are required and `[]` optional. Most commands require staff permissions and act on the configured VRChat group. Entity IDs look like `usr_…` (user), `grp_…` (group), and `avtr_…` (avatar).

#### Moderation Commands

> **Can't tag kicks, bans or warns on 0.4.19?** Tag search was shipped without being allowed by default. Update to 0.4.20, or have an admin run the two one-time commands under [*Tag search refused on 0.4.19*](#configuration-commands).

- **`moderation-tags list [entries-per-page:int?]`**  
  Lists your custom moderation tags (up to 125 tags).  
  Example: `/moderation-tags list`

- **`moderation-tags add <value:string> <label:string?> <description:string?>`**  
  Adds or updates a custom moderation tag. (Replaces the old `create-or-update-moderation-tag`.)  
  Example: `/moderation-tags add "trolling" "Trolling" "Provocative or mocking behaviour intended to antagonise someone"`

- **`moderation-tags delete <value:string>`**  
  Removes a custom moderation tag. (Replaces the old `delete-moderation-tag`.)  
  Example: `/moderation-tags delete "trolling"`

- **`tag add <tag> [tag-2] [tag-3] [tag-4] [tag-5]`** / **`tag remove <tag> [tag-2…tag-5]`**  
  Run inside a moderation event's thread to add or remove tags on that kick, ban or warn. Each tag option searches **every** moderation tag as you type (name, id or description, typos allowed) and shows its description, so you don't need to know the tags by heart or scroll through several dropdowns. The **Edit tags** button on the post still opens all tags as dropdowns.  
  Example: `/tag add tag:harassment tag-2:mic-spam`
  **Who can tag:** anyone whose Discord role can **send messages in the moderation thread** — so your existing staff role and channel permissions decide, with nothing to set up in Scarlet. (Also: full moderators, the moderator who took the action via their linked VRChat account, VRChat instance moderators, and the `groupex-tags-edit` override.) Keep moderation channels read-only or hidden for non-staff.

- **`vrchat-user-info <vrchat-user:string>`**  
  Lists internal and audit information for a specific VRChat user.  
  Example: `/vrchat-user-info "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-user-ban <vrchat-user:string> [tag-immediately:bool?]`**  
  Bans a specific VRChat user from the group, optionally opening the tag prompt straight away.  
  Example: `/vrchat-user-ban "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-user-ban-multi`** / **`vrchat-user-unban-multi`**  
  Bans or unbans several VRChat users at once via a follow-up prompt.  
  Example: `/vrchat-user-ban-multi`

- **`vrchat-user-unban <vrchat-user:string>`**  
  Unbans a specific VRChat user.  
  Example: `/vrchat-user-unban "usr_00000000-0000-0000-0000-000000000000"`

- **`discord-user-info <discord-user:user>`**  
  Lists internal information for a specific Discord user.  
  Example: `/discord-user-info <@123456789123456789>`

- **`discord-warn <discord-user:user> <reason:string?>`**  
  Sends a warning DM to a Discord member and records it in the configured action-log channel.  
  Example: `/discord-warn <@123456789123456789> "Stop spamming chat"`

- **`discord-kick <discord-user:user> <reason:string?>`** / **`discord-ban <discord-user:user> <reason:string?>`**  
  Kicks or bans a Discord member after confirmation, recording the result in the action-log channel.  
  Example: `/discord-ban <@123456789123456789> "Raid account"`

- **`submit-evidence <evidence-submission:attachment> [evidence-submission-2 … -5:attachment?]`**  
  Submits up to five attachments as evidence.  
  Example: `/submit-evidence <(attached file)>`

#### Watched Entities

`watched-group`, `watched-user`, and `watched-avatar` share the same set of subcommands, differing only in the ID they take (`grp_…`, `usr_…`, or `avtr_…`). The examples below use `watched-group`; substitute `watched-user` or `watched-avatar` as needed.

- **`watched-group add <vrchat-group:string> [type] [tags] [priority] [message]`**  
  Starts watching an entity, optionally setting its watch type, tags, priority, and TTS announcement message up front.  
  Example: `/watched-group add "grp_00000000-0000-0000-0000-000000000000"`

- **`watched-group view <vrchat-group:string>`**  
  Shows an entity's stored watch information.  
  Example: `/watched-group view "grp_00000000-0000-0000-0000-000000000000"`

- **`watched-group remove <vrchat-group:string>`**  
  Stops watching an entity.  
  Example: `/watched-group remove "grp_00000000-0000-0000-0000-000000000000"`

- **`watched-group remove-menu`**  
  Posts multi-select dropdowns of the watched entities by name so you can remove several at once (handy for pruning deleted/banned avatars from a large list) instead of one `remove` per id. Shows up to 125 entries; run again for the rest if there are more.  
  Example: `/watched-avatar remove-menu`

- **`watched-group list [entries-per-page:int?]`** / **`export`** / **`import <import-file:attachment>`**  
  Lists all watched entities of that kind, exports them as a JSON file, or imports them from an attached JSON file.  
  Example: `/watched-group export`

- **`watched-group add-tag <vrchat-group:string> <tag:string>`** / **`remove-tag <vrchat-group:string> <tag:string>`**  
  Adds or removes a single moderation tag on the entity.  
  Example: `/watched-group add-tag "grp_00000000-0000-0000-0000-000000000000" "trolling"`

- **`watched-group set-critical` / `set-silent` / `set-type` / `set-priority` / `set-message` / `set-notes` / `set-tags <vrchat-group:string> <value>`**  
  Sets a single property of the watch entry — the critical flag, silent flag, watch type, priority, TTS announcement message, notes, or the full tag set respectively.  
  Example: `/watched-group set-critical "grp_00000000-0000-0000-0000-000000000000" true`

#### VRChat Group Management

Subcommands of **`vrchat-group`** for operating the group itself.

- **`vrchat-group audit-types`**  
  Lists the audit event types currently present in the group (useful when configuring audit channels).  
  Example: `/vrchat-group audit-types`

- **`vrchat-group search-members <member-search:string> [result-limit:int?] [entries-per-page:int?]`**  
  Searches the group's members by display name.  
  Example: `/vrchat-group search-members "display name"`

- **`vrchat-group list-bans [result-limit:int?] [entries-per-page:int?]`**  
  Lists banned users in the group.  
  Example: `/vrchat-group list-bans`

- **`vrchat-group list-invites [result-limit:int?] [entries-per-page:int?]`**  
  Lists pending invites the group has sent.  
  Example: `/vrchat-group list-invites`

- **`vrchat-group list-join-requests [blocked-requests:bool?] [result-limit:int?] [entries-per-page:int?]`**  
  Lists pending join requests, or blocked ones when `blocked-requests` is set.  
  Example: `/vrchat-group list-join-requests`

- **`vrchat-group view-announcement`**  
  Shows the group's current announcement.  
  Example: `/vrchat-group view-announcement`

- **`vrchat-group create-announcement <title:string> <text:string> [send-notification:bool?] [image-file-id:string?]`**  
  Creates or replaces the group announcement, optionally notifying members and attaching an uploaded image.  
  Example: `/vrchat-group create-announcement "Event tonight" "Doors open at 7pm CT."`

- **`vrchat-group delete-announcement <confirm-delete:bool>`**  
  Deletes the current group announcement.  
  Example: `/vrchat-group delete-announcement true`

- **`vrchat-group list-posts [public-only:bool?] [result-limit:int?] [entries-per-page:int?]`**  
  Lists recent group posts.  
  Example: `/vrchat-group list-posts`

- **`vrchat-group create-post <title:string> <text:string> [visibility] [send-notification:bool?] [image-file-id:string?] [role:string?]`**  
  Creates a group post, optionally restricted to a role and/or with an image.  
  Example: `/vrchat-group create-post "Rules update" "Please re-read the group rules."`

- **`vrchat-group delete-post <post-id:string> <confirm-delete:bool>`**  
  Deletes a group post by ID.  
  Example: `/vrchat-group delete-post "<post id>" true`

- **`vrchat-group open-instance <vrchat-world:string>`**  
  Opens a group instance for the given world.  
  Example: `/vrchat-group open-instance "wrld_00000000-0000-0000-0000-000000000000"`

- **`vrchat-group close-instance <vrchat-location:string> [close-hard:bool?] [close-in-minutes:int?]`**  
  Closes an instance, optionally hard-closing (kicking everyone) or scheduling the close after N minutes.  
  Example: `/vrchat-group close-instance "wrld_…:12345~group(grp_…)"`

- **`vrchat-group add-role <vrchat-user:string> <vrchat-role:string>`** / **`remove-role <vrchat-user:string> <vrchat-role:string>`**  
  Grants or removes a VRChat group role for a user.  
  Example: `/vrchat-group add-role "usr_00000000-0000-0000-0000-000000000000" "<role id>"`

- **`vrchat-group transfer-check <vrchat-user:string>`**  
  Checks whether a user is eligible to receive group ownership.  
  Example: `/vrchat-group transfer-check "usr_00000000-0000-0000-0000-000000000000"`

- **`vrchat-group transfer-start <vrchat-user:string> <confirm-group-id:string>`** / **`transfer-cancel <confirm-group-id:string>`**  
  Starts (or accepts) a group-ownership transfer, or cancels an active one. The group ID must be re-typed to confirm.  
  Example: `/vrchat-group transfer-start "usr_00000000-0000-0000-0000-000000000000" "grp_00000000-0000-0000-0000-000000000000"`

- **Desktop instance wizard**  
  Use **Create Instance** in Scarlet's top action bar, or **File -> Create VRChat group instance...**, to create a group instance from a world URL/id and optionally open it in the VRChat client in VR or Desktop mode.

- **Privacy-safe feature usage report**  
  Use **Feature Report** in the top action bar on Windows or Linux to save a local HTML inventory of Discord-output-capable features. Every row contains only the feature name and `Enabled` or `Disabled`; the report does not include channel, guild, role, webhook, account, group, user, path, token, endpoint, or other configuration values, and Scarlet does not upload it.

#### Event Scheduling

Subcommands of **`schedule`** manage recurring VRChat group events (each stored as a "spec" with an ID). Scarlet posts upcoming events ahead of time and can mirror them as Discord events.

- **`schedule list [entries-per-page:int?]`**  
  Lists all configured event schedules.  
  Example: `/schedule list`

- **`schedule add <event-id> <title> <description> <date> <time-zone-id> <time-of-day> <duration> <frequency> <category>`**  
  Creates a new event schedule.  
  Example: `/schedule add "weekly-hangout" "Weekly Hangout" "Casual community meetup" "2026-08-01" "America/Chicago" "19:00" "120" "WEEKLY" "hangout"`

- **`schedule remove <scarlet-event-spec:string>`**  
  Deletes an event schedule.  
  Example: `/schedule remove "weekly-hangout"`

- **`schedule set-active <spec> <active:bool>`** / **`set-featured <spec> <featured:bool>`** / **`set-mirror-on-discord <spec> <value:bool>`** / **`set-notify-create <spec> <value:bool>`**  
  Toggles a schedule's activation, featured flag, Discord-event mirroring, or create-time notification.  
  Example: `/schedule set-active "weekly-hangout" true`

- **`schedule set-max-pending <spec> <count:int>`** / **`set-host-join-early <spec> <minutes:int>`** / **`set-guest-join-early <spec> <minutes:int>`** / **`set-close-after <spec> <minutes:int>`**  
  Sets how many future events to keep posted, how early hosts/guests may join, and how long after the end to auto-close the instance.  
  Example: `/schedule set-host-join-early "weekly-hangout" "15"`

- **`schedule set-title` / `set-description` / `set-date` / `set-time` / `set-duration` / `set-frequency` / `set-category` / `set-access` / `set-overflow` / `set-image <spec> <value>`**  
  Sets an individual property of the schedule (title, description, start date, time-of-day + zone, duration, recurrence frequency, category, access level, overflow-instance handling, or banner image).  
  Example: `/schedule set-time "weekly-hangout" "America/Chicago" "20:00"`

- **`schedule set-tags` / `set-roles` / `set-platforms` / `set-languages <spec> <values…>`**  
  Sets the multi-value properties: event tags, allowed roles, platforms, and languages.  
  Example: `/schedule set-languages "weekly-hangout" "eng" "spa"`

#### Staff Lists

- **`staff-list list [entries-per-page:int?]`** / **`add <vrchat-user> [discord-user] [role]`** / **`delete <vrchat-user> [role]`**  
  Lists, adds, or removes users on the public staff list (used for staff advisories and the `staff-list` display).  
  Example: `/staff-list add "usr_00000000-0000-0000-0000-000000000000" <@123456789123456789>`

- **`secret-staff-list list` / `add` / `delete`**  
  The same operations for the secret staff list, whose members are tracked but not shown publicly.  
  Example: `/secret-staff-list add "usr_00000000-0000-0000-0000-000000000000"`

#### Audit and Logging Commands

- **`query-target-history <vrchat-user:string> [days-back:int?]`** / **`query-actor-history <vrchat-user:string> [days-back:int?]`**  
  Queries audit events targeting, or performed by, a specific VRChat user.  
  Example: `/query-target-history "usr_00000000-0000-0000-0000-000000000000" "14"`

- **`actor-moderation-summary <vrchat-user:string>`**  
  Generates a summary of moderation actions performed by a specific staff member (a per-actor accountability view).  
  Example: `/actor-moderation-summary "usr_00000000-0000-0000-0000-000000000000"`

- **`moderation-summary <hours-back:int>`**  
  Summarizes saved local moderation history in a background worker. The positive lookback window is not a guarantee of complete history.  
  Example: `/moderation-summary "48"`

- **`outstanding-moderation [hours-back:int?]`**  
  Uses VRChat audit queries to list outstanding (unresolved) moderation actions over the given window.

- **`set-audit-channel <audit-event-type:string> [discord-text-channel:channel?]`**  
  Routes an audit event type to a text channel. Omit the channel to unset.  
  Example: `/set-audit-channel "group.instance.kick" <#log-instance-kicks>`

- **`set-audit-ex-channel <audit-ex-event-type:string> [discord-text-channel:channel?]`**  
  Routes an extended (Scarlet-derived) event type to a channel.  
  Example: `/set-audit-ex-channel "groupex.instance.vtk" <#log-instance-kicks>`

- **`set-audit-secret-channel <audit-event-type:string> [discord-text-channel:channel?]`** / **`set-audit-secret-ex-channel <secret-audit-ex-event-type:string> [discord-text-channel:channel?]`**  
  Routes an audit event, or a supported extended event, to a *secret* channel (for staff-only, sensitive events). Extended secret destinations are supported for **Instance Monitor**, **Action Failure**, and **Live Player List**. Older unsupported extended assignments appear as **Clear legacy entry**; omit the channel to remove them. They are not counted as enabled in the feature-usage report.  
  Example: `/set-audit-secret-channel "group.instance.kick" <#log-instance-kicks-secret>`

### Live Player List setup

Select **Live Player List** (`groupex.instance.playerlist`) in `/set-audit-ex-channel` and assign its text channel. It posts directly in that channel; **Instance Monitor** and **User Switch Avatar** logging do not have to be enabled. To show the confidential roster, use `/set-audit-secret-ex-channel` with a separate channel whose access you have restricted appropriately. Scarlet does not change Discord channel permissions. If both routes use the same channel, the public filtering rules win.

The list reflects players observed by the local VRChat client in the configured group's current instance, not every player in every group instance. After log catch-up, Scarlet rebuilds the current roster and resumes its saved message where possible. Players that cannot be observed in the client cannot be inferred by this feature. Leaving the instance, quitting the client, or disabling the route ends the corresponding observation. Long lists omit whole rows with a visible omitted-player count instead of cutting links mid-line.

For native audit setup, select **Instance Warn** or **Instance Kick** from the suggestions. Free-typed `warns`, `kick`, and `live` are not separate event types. Unknown selections are rejected; they must never change an Instance Close assignment. Previously misassigned channels still need manual review because Scarlet cannot recover the user's original intent.

- **`set-audit-aux-webhooks <audit-event-type:string>`**  
  Sets the auxiliary webhooks certain audit event types mirror to.  
  Example: `/set-audit-aux-webhooks "group.instance.kick"`

- **`aux-webhooks list [entries-per-page:int?]`** / **`add <id> <url>`** / **`remove <id>`**  
  Manages the named auxiliary webhooks referenced by `set-audit-aux-webhooks`.  
  Example: `/aux-webhooks add "mirror-1" "https://discord.com/api/webhooks/…"`

- **`set-discord-action-log-channel [discord-text-channel:channel?]`**  
  Sets the channel for Discord warn/kick/ban result logs and member-join invite logs. Omit to disable. Invite tracking needs the bot's Manage Server permission and the member intent.  
  Example: `/set-discord-action-log-channel <#discord-mod-log>`

- **`set-ops-alert-channel [discord-text-channel:channel?]`**  
  Sets a channel to receive Scarlet's operational health alerts — VRChat session lost/recovered, log-tailer stalled/recovered, hard VRChat rate-limiting, an available Scarlet update, and VRChat API version mismatches. Omit to disable.  
  Example: `/set-ops-alert-channel <#scarlet-health>`

- **`set-training-channel [discord-text-channel:channel?]`**  
  Sets a channel to receive simulated `[TRAINING]` events from Training mode, keeping drills out of the real audit log. If unset, simulated events are not posted to Discord.  
  Example: `/set-training-channel <#scarlet-training>`

- **`set-discord-account-age-alert [days:int?]`**  
  Flags Discord members whose account is newer than the threshold in the join log. Omit to clear.  
  Example: `/set-discord-account-age-alert "7"`

- **`export-log [file-name:string?]`**  
  Attaches a Scarlet log file for download.  
  Example: `/export-log`

#### Configuration Commands

- **`config-info`**  
  Shows the current configuration, including which Discord roles are mapped to each Scarlet permission.  
  Example: `/config-info`

- **`settings edit <setting-id:string>`**  
  Opens an editor for one of Scarlet's file-backed settings by ID (the same settings shown in the desktop Settings tab).  
  Example: `/settings edit "audit_polling_interval"`

- **`config-set moderation-summary time-of-day <time-zone-id> <time-of-day>`** / **`config-set outstanding-moderation time-of-day <time-zone-id> <time-of-day>`**  
  Sets the daily time at which the moderation / outstanding-moderation summaries are generated.  
  Example: `/config-set moderation-summary time-of-day "America/Chicago" "09:00"`

- **`config-set outstanding-moderation period-days <days:int>`** / **`config-set suggested-moderation period-days <days:int>`** / **`config-set suggested-moderation kick-count <count:int>`**  
  Tunes the outstanding-moderation lookback period, and the kick-count/period thresholds that drive suggested moderation.  
  Example: `/config-set suggested-moderation kick-count "3"`

- **`config-set report-template view` / `download` / `view-report-template-format` / `edit` / `upload <report-template:attachment>`**  
  Views, downloads, inspects the parameters of, edits, or replaces the VRChat Help Desk report template used to pre-fill report forms.  
  Example: `/config-set report-template download`

- **`set-voice-channel [discord-voice-channel:channel?]`**  
  Sets the voice channel Scarlet joins to speak TTS announcements.  
  Example: `/set-voice-channel <#staff-in-instance>`

- **`set-tts-voice <voice-name:string>`**  
  Selects which installed TTS voice is used for announcements.  
  Example: `/set-tts-voice "Microsoft David Desktop"`

- **`set-verification-auto-invite <enabled:bool> [verified-role] [members-role] [vrchat-group-id]`**  
  Configures auto-inviting newly verified members to a VRChat group.  
  Example: `/set-verification-auto-invite true <@&VerifiedRole> <@&MembersRole> "grp_00000000-0000-0000-0000-000000000000"`

- **`set-ticket-tool-auto-response <enabled:bool> [discord-category] [notify-role] [channel-name-regex]`**  
  Configures the Ticket Tool age-verification auto-response (which ticket channels it fires in, and who to ping).  
  Example: `/set-ticket-tool-auto-response true`

- **`set-ticket-age-verify-message`**  
  Sets (or resets) the message text used by the Ticket Tool age-verification auto-response.  
  Example: `/set-ticket-age-verify-message`

- **`server-restart restart-now`** / **`server-restart update-now [target-version:string?]`**  
  Restarts the Scarlet application immediately, or updates it (optionally to a specific version) and restarts.  
  Example: `/server-restart update-now`

- **`scarlet-discord-permissions set <target:role/user> <type> <name:string> <value:Allow|Deny|Default>`** / **`scarlet-discord-permissions list <target:role/user> [type]`**  
  Controls who can use each Discord command and each Scarlet button, menu and pop-up. `type` is one of **Slash Command**, **Message Command**, **User Command**, **Button Press**, **String Select**, **Entity Select**, **Modal Submit** or **Other**; `name` is the command or interaction ID (the command autocompletes). **Default** falls through to Scarlet's built-in behaviour: every moderation button, menu and pop-up Scarlet ships is allowed by default, and each one still checks the member's own VRChat/Discord permissions, so you normally only need this to *restrict* something. Scarlet logs a startup warning if a build contains an interaction that isn't allowed by default.  
  Example: `/scarlet-discord-permissions set target:@Moderators type:Button Press name:timed-ban value:Deny`

  > **Tag search refused on 0.4.19 (tagging kicks, bans and warns).** Version 0.4.19 shipped the tag search without allowing it by default, so **Edit tags → search** is refused for everyone. **Update to 0.4.20 or later** to fix it. Until you can, a server admin can unlock it by running these two commands once (use your moderator role, or `@everyone` — moderator permissions are still checked):
  >
  > ```
  > /scarlet-discord-permissions set target:@Moderators type:Modal Submit name:tag-search value:Allow
  > /scarlet-discord-permissions set target:@Moderators type:String Select name:select-tags-search value:Allow
  > ```
  >
  > On 0.4.19 the timed-ban buttons (`timed-ban`, `timed-ban-custom`), the group join-request buttons (`group-request-accept`, `group-request-reject`, `group-request-block`) and the `watched-remove` menu are also refused by default and can be unlocked the same way (`type:Button Press`, `type:Modal Submit` for the `timed-ban-custom` pop-up, `type:String Select` for `watched-remove`). All of these are allowed by default from 0.4.20.

- **Scarlet permissions**  
  Scarlet's own permissions (for example `event.set_tags`) are mapped to Discord roles in Scarlet's configuration; the current mapping is shown by `/config-info`. (There is no longer a standalone `scarlet-permission` command.)

#### Account Linking

- **`link-vrchat-account [vrchat-user:string]`** / **`unlink-vrchat-account`**  
  Lets a server member link (or unlink) their own Discord account to their VRChat account.  
  Example: `/link-vrchat-account`

- **`unlink-vrchat-account-for [discord-user:user] [vrchat-user:string]`**  
  Staff command to unlink another member's Discord/VRChat association, by Discord user and/or VRChat user.  
  Example: `/unlink-vrchat-account-for <@123456789123456789>`

#### Utility Commands

- **`vrchat-search <world|user|group|avatar> <search-query:string> [entries-per-page:int?]`**  
  Searches VRChat worlds, users, groups, or avatars.  
  Example: `/vrchat-search user "Vinyarion"`

- **`vrchat-animated-emoji from-url <gif-url:string>`** / **`from-file <gif-file:attachment>`**  
  Generates a VRChat animated-emoji spritesheet from a GIF, given either a URL or an uploaded file.  
  Example: `/vrchat-animated-emoji from-url "https://tenor.com/view/rat-spin-gif-10300642414513246571"`

---

### CLI Commands

Scarlet's CLI tab (and the IPC pipe below) accept these text commands. The English words
below always work, but if the desktop UI language is set to a translated language, the
command can also be typed in that language (e.g. `ayuda`/`salir` in Spanish, `hilfe`/`beenden`
in German, `ヘルプ`/`終了` in Japanese); run `help` in that language to see its aliases.

- **`info`, `help`**  
  Prints Scarlet build/runtime information and the available commands.  
- **`exit`, `halt`, `quit`, `stop`**  
  Shuts down the application.  
- **`logout`**  
  Logs out of the VRChat account and shuts down the application.  
- **`reboot`, `restart`**  
  Restarts the application.  
- **`explore`**  
  Opens the folder Scarlet uses to store data.  
- **`tts <message...>`**  
  Queues a TTS message to be read in the Discord voice channel, if connected.  
- **`link <vrcUserId> <discordUserSnowflake>`**  
  Associates a VRChat account with a Discord account.  
- **`importgroups <file|url...>`**  
  Imports a legacy CSV list of watched groups from a file or URL.  
- **`importgroupsjson <file|url...>`**  
  Imports a JSON list of watched groups from a file or URL.  
- **`simulate <kind> [name]`**  
  Fires a simulated training event (requires Training mode enabled). Kinds: `join`, `watched`, `wuser`, `new`, `mixed`, `pronouns`, `avatar`, `vtk`, `leave`.  
- **`langlint`**  
  Validates the external `lang/messages_<lang>.properties` translation files against the English base and prints a per-file report (missing keys, unknown keys, broken `{0}` placeholders).  
- **`translate-advisories`**  
  Translates advisory text (watched groups/users/avatars and moderation-tag descriptions) into the current UI language. Works with no setup via the free built-in MyMemory service; set a LibreTranslate endpoint in Settings → Advisories to use that instead (e.g. a local self-hosted instance for privacy). Group names, ids and tags are left unchanged, and the English originals are preserved.  
- **`restore-advisories`**  
  Restores the original (untranslated) advisory text for every watched group.  
- **`vrchatapi-test`**  
  Runs a quick check of the bundled VRChat API client against the live API.  
- **`popup`, `popup-test`**  
  Shows a test desktop notification, to verify the notification path works.  
- **`data-transfer`**  
  Runs the export/import migration flow for moving Scarlet to another PC or OS.  
- **`data-folder-notice`**  
  Prints the location of Scarlet's data folder.

Scarlet supports sending cli commands via named pipes.
Windows: `\\.\pipe\ScarletIPC-grp_00000000-0000-0000-0000-000000000000`
Everything else (Unix-like): `/tmp/ScarletIPC-grp_00000000-0000-0000-0000-000000000000.sock`

```ps1
function Send-ScarletIPC
{
    param
    (
    [String]$GroupID,
    [String]$Message
    )
    $request = [System.Text.Encoding]::UTF8.GetBytes($Message);
    $stream = New-Object -TypeName System.IO.Pipes.NamedPipeClientStream -ArgumentList '.',"ScarletIPC-$GroupID",([System.IO.Pipes.PipeDirection]::Out),([System.IO.Pipes.PipeOptions]::None),([System.Security.Principal.TokenImpersonationLevel]::Impersonation)
    $stream.Connect(1000);
    $stream.Write($request, 0, $request.Length);
    $stream.Dispose();
}

Send-ScarletIPC -GroupID 'grp_00000000-0000-0000-0000-000000000000' -Message 'stop'
```

---

### Interoperability

Scarlet links to the official VRChat website whenever possible, including for user profiles, group posts, instances, and much more.  
Scarlet can generate a link that will autopopulate the fields of the VRChat Help Desk report form based on custom tags assigned to moderation events, streamlining the reporting process and reducing mistakes from human error.

The moderation **Report** button gives the requesting moderator a private reply with a copy-and-paste report, an **Autofilled report form (configured template)** link, and a **Simple report form (notes and tags)** link. The configured-template link and copyable text use the same report template; an empty template falls back to the event notes, tags, and footer. The configured template's optional footer setting is respected. If a report or URL is too long for Discord, its complete text is attached instead of losing information. Review the report, sign in to the VRChat Help Desk, and reopen the link if sign-in cleared the prefilled fields. Attach evidence when using the Help Desk user-report form; VRChat recommends in-app reporting for general reports. Scarlet prepares the report, and the moderator submits it manually.

---

### Localization

- The desktop UI and the spoken TTS callouts are translated. **English and Russian** ship complete; German, Spanish, and Indonesian are in-progress pilots that fall back to English for anything not yet translated.
- Pick a language in **Settings -> Appearance** (a dropdown of native names); it defaults to your operating system language and applies on restart. The Discord bot renders each reply in the invoking user's own Discord language.
- **Community translations without a rebuild:** drop a `messages_<lang>.properties` file into the `lang/` folder in Scarlet's data directory and restart — new languages are auto-discovered, and Scarlet writes an up-to-date English `messages.template.properties` there as a starting point. The `langlint` CLI command (and a startup check) validates community files, flagging missing keys and broken `{0}` placeholders.

---

### Training mode

- Enable **Settings -> Training** to unlock an event simulator (Edit -> *Simulate event (training)...*, or the `simulate` CLI command) that fires realistic events on demand — so new moderators can learn on a screenshare instead of a live incident, and nobody has to actually join a bad group.
- The simulator can also fire **moderator warn / kick / ban** events. Each produces a genuine moderation post and thread with the real **Edit tags**, **Edit description**, **Ban / Unban** and **Timed ban** buttons (and `/tag` works in its thread), so a trainer (or a maintainer testing a build) can exercise the whole Discord tagging workflow from a moderator's account without banning anyone. These posts go **only** to the training channel set with `set-training-channel` (nothing is posted if none is set), Ban / Unban / Timed ban on a training user run the real permission checks and then reply with a simulated result without calling VRChat, and drills never appear in moderation summaries (training records are explicitly excluded from saved-history summaries).
- Simulated events run the **real** pipeline — the player-list row, TTS callout, desktop notification, and a genuine Discord post with its ban/unban buttons — so the whole workflow, including tagging in Discord, is practiceable.
- While training, Scarlet behaves as a **separate client**: the real instance is parked and kept updated in the background, actions on training players show real success feedback but make no VRChat call, and turning training off restores the live instance instantly. Everything training-related is marked `[TRAINING]` and, with `set-training-channel`, posts to its own channel — a drill can never be mistaken for a real record.

---

### Reliability and health monitoring

- A background watchdog turns Scarlet's silent failure modes into loud, recoverable ones: if the VRChat log goes quiet while the game is running, or the VRChat session expires mid-run, Scarlet raises a popup, attempts unattended re-login, and (with `set-ops-alert-channel`) posts a Discord health alert.
- Data files (watched lists, settings, Discord config, moderation tags, ...) are written crash-safely and keep the last several dated backups in a `backups/` folder beside each file, so a crash mid-write can't lose a blocklist.
- After downtime, Scarlet catches up on the missed audit log quickly and at most `audit_catchup_max_days` back (default 14, see [SETTINGS.md](SETTINGS.md)), so live moderation posts resume within minutes rather than replaying months of old events.
- The **Diagnostics** view (Help -> Scarlet: Diagnostics...) shows connectivity, rate-limit, and avatar-provider status without making extra API calls.

---

### Desktop notifications

- Advisories can raise native desktop notifications (Windows Action Center, Linux `notify-send`, macOS `osascript` — PowerShell is never invoked), configured per category in **Settings -> Desktop Notifications**.
- Scarlet no longer includes an Android/mobile companion, phone pairing, or any mobile alert-delivery feature.

---

### About Extended Events

Extended audit events (Instance Inactive, Staff Join, Staff Leave, Vote-to-Kick Initiated) are logged with the Discord command `set-audit-ex-channel`.  
Some of these require that a VRChat Client in a group instance must be running on the same machine in order for them to be logged in Discord channels.  
This limitation exists because Scarlet reads the VRChat client log file as it gets updated with information.  
At the moment, these events are not of the same degree as the canonical group audit events, as they are derived from the local client log rather than a server-side audit record, but similar functionality may hopefully be added to VRChat's first-party API in the future.

---

### About Actors and Targets

Actors are the users that *initiated or performed* an event, like a group staff member.  
Targets are the users that had an event *performed on them*, like the user a group staff member kicks from an instance.  
For some events, the target may not be a user, like for when a group instance is created.

---

## Installation

### Requirements

You will need:  
- A VRChat group you (or a dedicated bot account) have permissions for.  
- A Discord server (guild) in which you have permissions.  
- A Discord bot account (application).  
- A Windows or Linux PC with Java 11 (or newer) installed; Java 21 is recommended.

Scarlet runs on both Windows and Linux (this is the Linux-compatible fork). The Discord and VRChat-API features work anywhere; the VRChat-log features (instance monitoring, extended events, avatar statuses) require the VRChat client to be running on the same machine — natively on Windows, or under Proton/Wine on Linux.

If you prefer a portable Java installation, extract a Java 21 JDK and invoke
its `bin/java` executable directly in the launch command below.

#### Linux text-to-speech installation

Scarlet can use an already installed eSpeak NG or Flite engine. To add voices,
open **Settings -> Text-to-Speech -> Install Linux TTS voices**; Scarlet asks
for confirmation before running the displayed package command.

On **Bazzite and OSTree-based Fedora Atomic hosts**, Scarlet uses an existing
Homebrew installation for TTS instead of trying the host's DNF/YUM wrappers.
The default package is `espeak-ng`; Flite is also offered. Homebrew runs as the
current user, without Scarlet elevating it to root. Both normal PATH entries and
Homebrew's standard Linux prefix (`/home/linuxbrew/.linuxbrew/bin`) are checked,
so engines can be found from desktop launches with a minimal PATH.

For manual installation with an existing, writable Homebrew installation:

```sh
/home/linuxbrew/.linuxbrew/bin/brew install espeak-ng
```

If Homebrew is missing or is managed by another account, follow your distribution's
setup instructions first: [Bazzite Homebrew guide](https://docs.bazzite.gg/Installing_and_Managing_Software/Homebrew/).
Scarlet does not bootstrap Homebrew, create containers, layer OS packages, or
restart the device. Package layering with `rpm-ostree` is a separate manual
administration choice and normally requires a reboot before new commands are
usable; it is not an automatic fallback for TTS. Ordinary mutable distributions
keep their existing native package-manager selection, and Windows keeps SAPI.
Restart Scarlet after installing extra engines if their voices do not yet appear.

An installation video for the earlier desktop workflow is available below. Verify its steps against the current written instructions:

Thanks to [@KozyBlake](https://github.com/KozyBlake) for making this video tutorial:

[![installation-tutorial-video](https://img.youtube.com/vi/JMJgMSThBac/0.jpg)](https://www.youtube.com/watch?v=JMJgMSThBac)

---

### Setting up Discord Application

It is recommended that you create a bot account dedicated specifically for running Scarlet.  
Create a new application via the Discord developer dashboard: https://discord.com/developers/applications

Scarlet requires some permissions above the bare defaults:  
1. In the `Installation` tab for your app:  
    - Scarlet only supports installation for servers.  
      Ensure that only the `Guild Install` box is checked in the `Installation Contexts` area.  
      ![setup installation contexts](images/setup_installation_contexts.png?raw=true)  
    - In the `Guild Install` part of the `Default Install Settings` area:  
      - Add the `bot` scope.  
      - Add the `Attach Files`, `Create Polls`, `Create Public Threads`, `Embed Links`, `Manage Webhooks`, `Read Message History`, `Send Messages`, `Send Messages in Threads`, `Speak`, and `View Channels` permissions.  
        ![setup default install settings](images/setup_default_install_settings.png?raw=true)  
2. In the `Bot` tab:  
    - Enable the `Server Members` and `Message Content` intent in the `Privileged Gateway Intents` area.  
      ![setup privileged gateway intents](images/setup_privileged_gateway_intents.png?raw=true)  
3. Invite the bot to your server.  
4. In the `Installation` tab for your app, select the `None` option for the `Install Link` area.  
   ![setup install link](images/setup_install_link.png?raw=true)

---

### Setting up the VRChat Group

The VRChat account that Scarlet will use must have at least these permissions:  
- `Manage Group Member Data`  
- `View Audit log`  
- `View All Members`

At the moment, Scarlet does not enforce any moderation action against users.  
All such actions (e.g., kicking or banning a user) must be performed manually, but Scarlet provides some convenience methods like Discord commands and buttons.

---

### Installing the Scarlet Desktop Application

1. Download the latest release: https://github.com/Hatson321/Scarlet/releases/latest  
2. Copy or extract `scarlet-<version>.jar` into the directory of your choosing.  
3. Install a Java 11 (or newer) runtime for your operating system and CPU architecture. Java 21 is recommended; [Adoptium Temurin](https://adoptium.net/temurin/releases/?package=jdk&version=21) is one suitable distribution.  
4. From a terminal in that directory, start the same JAR on Windows or Linux:

   ```sh
   java -jar scarlet-<version>.jar
   ```

To keep Scarlet's data in a specific directory, set `SCARLET_HOME` in the
environment or pass it as a JVM property before `-jar`, for example:

```sh
java -DSCARLET_HOME="/path/to/scarlet-data" -jar scarlet-<version>.jar
```
