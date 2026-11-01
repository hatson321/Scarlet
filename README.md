[discord-invite]: https://discord.gg/CP3AyhypBF
[discord-widget]: https://discord.com/api/guilds/1342131776876838912/widget.png

[![Discord Widget][discord-widget]][discord-invite]

<img src="images/sybyline_scarlet.png?raw=true" alt="Scarlet Logger logo" width="300" height="300"/>

# Hatson321/Scarlet Logger

A self-hosted VRChat group management and moderation-log utility with Discord integration, a desktop player list, watched-entity advisories, and text-to-speech.

> scarlet is now under a new maintainer hatson321 ai is used to assist in the api updates and gets proof read by hatson321 and the other scarlet admin team members before release. tests are performed as well before release to ensure safe operational status and a fully detailed update html is provided disclosing what was changed and tested.

[Downloads](https://github.com/Hatson321/Scarlet/releases) · [Discord community][discord-invite] · [VRChat Group](https://vrchat.com/home/group/grp_f12667c7-df5f-454f-9a34-5ed8c33112a1) · [FAQ](FAQ.md) · [Settings](SETTINGS.md) · [Changelog](CHANGELOG.md) · [Security and network behavior](SECURITY.md) · [Building from source](BUILDING.md)

## Which version should I use?

Release status checked **November 1, 2026**:

| Version | Status | Where to get it |
| --- | --- | --- |
| **0.6.0** | Published stable release | [Stable release and downloads](https://github.com/Hatson321/Scarlet/releases/tag/0.6.0) |
| **0.6.1-a11** | Published preview / prerelease | [Preview release and downloads](https://github.com/Hatson321/Scarlet/releases/tag/0.6.1-a11) |
| **0.6.1** | Unpublished stable candidate under review | Not a public stable update |
| **0.6.1-a12** | Local release preparation | Not the current public preview download |

The feature details and command reference below describe the **published 0.6.1-a11 preview**, unless stated otherwise. Do not assume that every preview change is in 0.6.0. Consult the notes attached to your exact release; a local build or unpublished draft is not evidence of publication.

**Need matching source?** The repository's `main` application source is based on the published **0.6.0 stable release**, with refreshed documentation and the approved Help → Credits cleanup. It is not the a11 preview source, and these overlays mean GitHub's **Code → Download ZIP** is not byte-for-byte identical to the original release archive. For exact release-source reproduction, download the `scarlet-<version>-source.zip` attached to your release. The a11 feature reference below does not add those features to stable `main`.

## What Scarlet does

- Collects authorized VRChat group audit events and routes them to configured Discord channels, with moderation threads, tags, evidence, and report preparation.
- Shows players observed by the local VRChat client. Group audit logging and local client-log observation are different sources; neither is a complete view of every group instance.
- Provides watched-user, watched-group, and watched-avatar advisories, TTS, and desktop notification controls. Staff Mode supports local use without requiring a Discord bot or a configured group; it does not grant group permissions.
- Creates and manages instances, schedules group events, and offers opt-in automatic startup joining and client-exit behavior.
- Saves local moderation history, settings, watched lists, and migration bundles. Independent backups are still important.
- Runs from the same release JAR on Windows and Linux. VRChat-log features need access to the local client's output logs; Linux/Proton/Wine compatibility depends on the installation and feature.

### Preview additions and limitations

**Avatar-provider cache (a11).** With provider permission granted, successful public avatar metadata is reused for up to 24 hours and empty results for five minutes, including across restarts. The bounded cache stores metadata, not downloaded avatar images. Use **Avatar search providers → Manage → Clear saved avatar results** when needed. Cache use does not bypass provider consent or make private, ambiguous, or unindexed avatars resolvable.

**Vote-to-kick context (a11).** Discord vote-to-kick alerts can include the target's small profile thumbnail, short bio, pronouns, and status when available. Cached context can accompany the initial alert; missing optional details may be filled in afterward. The base alert does not depend on successful profile enrichment. This is an observed vote-to-kick initiation, not proof that a kick completed or that the target did anything wrong.

**Saved-history summaries.** `moderation-summary` and its scheduled counterpart scan Scarlet's saved structured audit records for the current group in a background worker. They exclude confidential, redacted, and training records; unreadable records produce a partial-result notice. Missing or never-collected history cannot be counted. The separate `actor-moderation-summary` and `outstanding-moderation` commands still query VRChat.

**Discord delivery.** Moderation threads are prepared after the main embed, and unfinished work can survive a restart in the local delivery queue. Profile preparation, audit availability, permissions, rate limits, and service response times can still delay messages. There is no guaranteed “within seconds” delivery time.

**Platforms.** Windows and Linux are the normal desktop targets. Experimental PowerPC build support is not a promise of runtime compatibility, and no PPC JAR is attached to the public releases listed above.

## Installation

### Requirements

- **Java 11 or newer; Java 21 recommended.** Use a runtime matching your OS and CPU architecture. Check what the terminal actually runs with `java -version`.
- A Windows or Linux computer. The downloadable Scarlet JAR does not bundle Java.
- For group/Discord operation: a VRChat account authorized for your group, a Discord server you can configure, and a dedicated Discord application/bot.
- For player-list and local-log alerts: a VRChat client on the same computer, or the corresponding supported local Proton/Wine setup. Group API auditing alone does not require a running VRChat client.
- Staff Mode can be used without a Discord bot or group setup; features needing group audits or Discord remain unavailable.

### Download and launch

1. Download `scarlet-<version>.jar` from the chosen [release](https://github.com/Hatson321/Scarlet/releases). The source ZIP is for source review/building, not the ready-to-run application.
2. Put the JAR in a folder you control. Current JAR installation does not require an old `run.bat` or a legacy application ZIP.
3. From a terminal in that folder, launch the selected version:

```sh
java -jar scarlet-0.6.0.jar
```

For the public preview, use its actual filename instead:

```sh
java -jar scarlet-0.6.1-a11.jar
```

A portable Java installation also works: invoke that installation's `bin/java` or `bin/java.exe` directly. If you just installed Java, reopen the terminal and check `java -version`; an existing terminal or Windows file association may still select a different runtime.

> **Installation tutorial:** The previous tutorial is private and outdated. A new tutorial is needed; use these written instructions in the meantime.

### Data location and upgrades

Use **Data Folder** in Scarlet, or the `explore` CLI command, to open the active data folder. It is not necessarily the folder containing the JAR.

To select a separate data directory, set the `SCARLET_HOME` environment variable or pass the **case-sensitive JVM property before `-jar`**:

```sh
java -DSCARLET_HOME="/path/to/scarlet-data" -jar scarlet-0.6.1-a11.jar
```

Windows example:

```text
java "-DSCARLET_HOME=C:\Scarlet-Data" -jar scarlet-0.6.1-a11.jar
```

Before upgrading, keep an independent backup and close the old Scarlet process. Do not run two versions against the same data folder. Renaming a JAR does not create a clean profile, and an upgrade does not intentionally reset all existing settings. Older builds may not compare newer version suffixes correctly; use the verified release download manually if needed.

### Set up the Discord application

Skip this section for local-only Staff Mode.

1. Create a dedicated application in the [Discord developer dashboard](https://discord.com/developers/applications).
2. Configure **Guild Install**, not a user-installed application, and include the `bot` scope.
3. Give the bot the channel permissions needed for the features you enable:
   - Logging: **View Channels**, **Send Messages**, **Embed Links**, **Attach Files**, and **Read Message History**.
   - Moderation threads: **Create Public Threads** and **Send Messages in Threads**.
   - Voice TTS: **Connect** and **Speak** in the selected voice channel.
   - Webhook features: **Manage Webhooks**. Poll features: **Create Polls**.
   - Optional Discord moderation: **Kick Members** and/or **Ban Members**, with the bot's role above the target. The requesting moderator's permission and role hierarchy are checked too.
   - Invite attribution: **Manage Server**, as well as the member intent.
4. Enable **Server Members** and **Message Content** under **Privileged Gateway Intents**; these are used by the configured integration.
5. Invite the bot to your server and configure its token and server ID in Scarlet. Keep the token private.
6. Assign each audit/extended event to its intended channel. Channel access remains a Discord administrator's responsibility; Scarlet does not make a channel private for you.

The built-in `discord-warn`, `discord-kick`, and `discord-ban` commands are optional and must be enabled under **Settings → Discord**. Warning requires the requesting moderator's **Moderate Members** permission; kick/ban require the corresponding permission and hierarchy. Enabling the commands does not grant the bot those permissions automatically.

### Set up the VRChat group

For group auditing, the signed-in VRChat account needs **View Audit log**. Membership queries, member notes, bans, invitations, instance management, and scheduling need their corresponding group permissions; grant only what you intend to use. The existing setup guidance includes **View All Members** and **Manage Group Member Data** for the applicable membership features.

A Discord role does not grant the VRChat bot account missing group permissions. Commands may also require the requesting member's linked VRChat account to have authority. Staff Mode is different: it does not require group setup and cannot use that absence to perform privileged group operations.

Watched alerts are not automatic bans. Moderation commands and buttons can perform real actions when authorized; an explicitly created timed ban can later be lifted automatically if its continuity checks pass.

## Privacy and external services

Scarlet is self-hosted; it does not automatically pool moderation databases between groups. Your configured Discord channels, webhooks, exports, and third-party providers are nevertheless data-sharing destinations. Review their access carefully.

VRChat and Discord authentication necessarily send credentials or session tokens to the respective services. Do not describe all credentials as “never sent anywhere.” Scarlet's local data, logs, cached metadata, and backups should be treated as sensitive.

**External avatar databases require owner consent.** In the a11 preview, the identifying-lookup permission is off until reviewed under **Avatar search providers → Manage**. After permission, selected providers may be queried automatically during avatar changes or missing-information rescans, as well as manual searches. Requests identify the running version, public maintainer support contacts, configured group ID, and signed-in primary VRChat account ID; they do not contain login credentials. Turning off consent or deselecting providers stops future provider lookups.

Other network features have separate defaults and triggers. Update/preview/announcement notification options default on in the reviewed preview. Hiding an announcement notification does **not** establish that metadata polling has stopped. Explicit advisory-translation actions may send selected prose to MyMemory or a configured LibreTranslate service. Review [SECURITY.md](SECURITY.md) before enabling or sharing anything.

The current release line has **no Android/mobile companion, phone pairing, or mobile alert-delivery feature**. Desktop notifications and Discord messages are separate features.

## Discord command reference

This reference is checked against **0.6.1-a11**. `<...>` means required, `[...]` optional. Discord presents named options; use its suggestions for IDs, enum choices, and event types rather than treating the examples as shell commands. Commands can be hidden by feature switches or refused by current permissions.

### Moderation and tags

| Command | Purpose |
| --- | --- |
| `/moderation-tags list [entries-per-page]` | List up to 125 custom tags. |
| `/moderation-tags add <value> <label> [description]` | Add or update a tag; value and label are required. |
| `/moderation-tags delete <value>` | Remove a tag. |
| `/tag add <tag> [tag-2] [tag-3] [tag-4] [tag-5]` / `/tag remove …` | Edit tags inside a moderation event's thread. |
| `/vrchat-user-info <vrchat-user>` | Show available user and audit information. |
| `/vrchat-user-ban <vrchat-user> [tag-immediately]` / `/vrchat-user-unban <vrchat-user>` | Request an authorized group ban/unban. |
| `/vrchat-user-ban-multi` / `/vrchat-user-unban-multi` | Open the bulk-action prompt. |
| `/discord-user-info <discord-user>` | Show stored information about a Discord member. |
| `/discord-warn <discord-user> [reason]` | Warn by DM and record the configured action log. |
| `/discord-kick <discord-user> [reason]` / `/discord-ban <discord-user> [reason]` | Confirm an authorized Discord kick/ban. |
| `/submit-evidence <evidence-submission> [additional attachments]` | Submit up to five attachments when evidence handling is configured. |

Example: `/moderation-tags add value:trolling label:Trolling description:Disruptive behavior`

People who can view and send messages in the moderation thread can tag its events. Other supported paths include the linked actor, relevant moderation permissions, and the `groupex-tags-edit` override. Keep moderation channels restricted to the intended staff. Permission to tag is not permission to ban.

### Watched entities

The command families are similar, but **their option names and removal commands are not identical**:

| Family | Required add options | Remove one | Optional add options |
| --- | --- | --- | --- |
| `/watched-group` | `vrchat-group`, `group-type` | `delete-watched-group` | `group-tags`, `group-priority`, `message` |
| `/watched-user` | `vrchat-user`, `entity-type` | `remove` | `entity-tags`, `entity-priority`, `entity-message` |
| `/watched-avatar` | `vrchat-avatar`, `entity-type` | `remove` | `entity-tags`, `entity-priority`, `entity-message` |

Examples:

```text
/watched-group add vrchat-group:grp_00000000-0000-0000-0000-000000000000 group-type:UNKNOWN
/watched-group delete-watched-group vrchat-group:grp_00000000-0000-0000-0000-000000000000
/watched-avatar remove vrchat-avatar:avtr_00000000-0000-0000-0000-000000000000
```

Each family also offers `list`, `view`, `remove-menu`, `export`, `import`, `set-critical`, `set-silent`, `set-type`, `set-priority`, `set-message`, `set-notes`, `set-tags`, `add-tag`, and `remove-tag`. Select the actual option names shown by Discord; `set-notes` opens a form rather than taking a prose value inline. `remove-menu` shows up to 125 entries at once. Import/export here concerns that watched list, not a complete migration backup.

### Group management and scheduling

`/vrchat-group` provides `audit-types`, `search-members`, `list-bans`, `list-invites`, `list-join-requests`, announcement/post operations, `open-instance`, `close-instance`, role assignment/removal, and ownership-transfer operations. These use the configured group and check applicable authority. Use Discord's named option prompts.

`/vrchat-group close-instance <vrchat-location> [close-hard] [close-in-minutes]` accepts a complete official launch link or raw location, including `~ageGate`. A world ID alone or short link is insufficient. Normal soft close blocks new joins without ejecting existing occupants; hard close is different. A rejected or uncertain outcome is not automatically proof that nothing changed: check the actual instance before retrying.

For desktop instance creation, use **Create Instance** in the action bar. The wizard can prepare group or personal instances where supported and authorized.

`/schedule` manages event specifications. A new schedule uses:

```text
/schedule add <event-id> <event-title> <event-description> <event-date> <time-zone-id> <time-of-day> <event-duration> <event-frequency> <event-category>
```

Choose a date such as `2026-11-08`, time `19:00`, zone `America/Chicago`, and duration **`02:00` for two hours**, not `120`. Select frequency/category from Discord's choices. Use `set-active` to control activation, and `set-mirror-on-discord` for Discord-event mirroring. `list`, `remove`, and the `set-*` subcommands edit title, dates, recurrence, roles, platforms, and other schedule options.

### Staff, audit channels, and summaries

| Command | Purpose |
| --- | --- |
| `/staff-list list` / `add` / `delete` | Manage regular staff entries; optional account/role association is a separate choice. |
| `/secret-staff-list list` / `add` / `delete` | Manage confidential staff entries; restrict both command access and secret-channel access. |
| `/set-audit-channel <audit-event-type> [discord-text-channel]` | Route a native audit event; omit the channel to unset. |
| `/set-audit-ex-channel <audit-ex-event-type> [discord-text-channel]` | Route a Scarlet-derived event. |
| `/set-audit-secret-channel <audit-event-type> [discord-text-channel]` | Configure the confidential native-event destination. |
| `/set-audit-secret-ex-channel <secret-audit-ex-event-type> [discord-text-channel]` | Configure a supported confidential extended destination. |
| `/set-audit-aux-webhooks <audit-event-type>` / `/aux-webhooks` | Configure optional named webhook mirrors. |
| `/query-target-history <vrchat-user> [days-back]` / `/query-actor-history <vrchat-user> [days-back]` | Query VRChat audit history. |
| `/moderation-summary [hours-back]` | Summarize saved local history; default 24 hours, with a positive lookback required when supplied. |
| `/actor-moderation-summary <vrchat-user>` / `/outstanding-moderation [hours-back]` | API-backed moderation views. |
| `/set-discord-action-log-channel [discord-text-channel]` | Route Discord moderation results and member-join invite logs. |
| `/set-ops-alert-channel [discord-text-channel]` | Route operational warnings and recovery/update notices. |
| `/set-training-channel [discord-text-channel]` | Route synthetic training events only; unset means no simulated Discord post. |
| `/set-discord-account-age-alert <threshold>` | Choose a preset threshold, such as **1 week**; choose **Disabled** to clear. There is no `days` argument. |
| `/export-log [file-name]` | Attach a Scarlet log; review/redact sensitive material before sharing outside trusted staff. |

Select **Instance Kick** or **Instance Warn** from native audit suggestions. Vote-to-kick is a separate extended event (`groupex.instance.vtk`), not an instance kick. Free-typed aliases such as `kick`, `warns`, and `live` are not separate event types.

For a **Live Player List**, assign `groupex.instance.playerlist` with `/set-audit-ex-channel`. It does not require Instance Monitor or User Switch Avatar logging. It reflects the local client's current observed group-instance roster, not every group instance. A separate confidential destination is supported; if both routes use the same channel, public filtering wins. Scarlet does not edit that channel's access permissions. Supported confidential extended destinations are Instance Monitor, Action Failure, and Live Player List; clear old unsupported assignments rather than assuming they work.

### Configuration, permissions, and utilities

- `/config-info` shows configured routes and other stored configuration. Its legacy Scarlet-role section is **not a complete view of current interaction overrides**.
- `/settings edit <setting-id>` opens a file-backed setting, e.g. `/settings edit setting-id:audit_polling_interval`.
- `/scarlet-discord-permissions set <target> <type> <name> <value>` and `list <target> [type]` manage interaction rules. Types include Slash Command, Button Press, String Select, Modal Submit, and Other. Default falls through to the built-in rules; it is not a universal permission grant.
- The actual Scarlet overrides are `groupex-bans-manage` and `groupex-tags-edit` under **Other**. There is no `event.set_tags` permission. For example: `/scarlet-discord-permissions set target:@Moderators type:Other name:groupex-tags-edit value:Allow`. Use `list` for the relevant target/type to inspect stored overrides. Additional action, account, hierarchy, and bot permission checks still apply.
- `/config-set` configures summary schedules, moderation thresholds, and the report template.
- `/set-voice-channel [discord-voice-channel]` connects/disconnects Discord TTS; `/set-tts-voice <voice-name>` selects an installed voice.
- `/set-verification-auto-invite [enabled] [verified-role] [members-role] [vrchat-group-id]` and `/set-ticket-tool-auto-response [enabled] [discord-category] [notify-role] [channel-name-regex]` configure optional verification integrations. `/set-ticket-age-verify-message` edits the response text.
- `/link-vrchat-account [vrchat-user]` / `/unlink-vrchat-account` manage your own association. `/unlink-vrchat-account-for` is the staff operation for another association.
- `/server-restart restart-now` restarts Scarlet. `/server-restart update-now <target-version>` requires an explicit version; `/server-restart update-now` alone is incomplete.
- `/vrchat-search` searches the selected world/user/group/avatar category. `/vrchat-animated-emoji from-url` / `from-file` prepare animated-emoji spritesheets where the feature is enabled.

## Local alerts, language, and training

### Staff Mode and TTS

In the a11 preview, Staff Mode local watched-user/group/avatar and vote-to-kick alert eligibility is not limited to the configured group's instance. Alert detection still depends on what the client logs and on the enabled category settings; it cannot infer an event that was not observed.

Check the global TTS state, selected working voice, output destination, and each category's **Advisory** and **TTS** toggles. Watched-entity and vote-to-kick callouts depend on both category switches. A nuisance alarm tone does not by itself prove speech synthesis is working. Avatar lookup also depends on available evidence and, for external fallback, explicit provider consent.

For local playback, enable **TTS: Use default system audio device**. Otherwise speech is routed toward the configured Discord connection, not automatically to the desktop speakers. Linux needs a supported installed speech engine; use **Settings → Text-to-Speech → Install Linux TTS voices** to review the available package choices. See the [TTS troubleshooting FAQ](FAQ.md#why-do-i-hear-an-alarm-but-no-spoken-alerts).

Desktop notification support depends on the OS and installed notification tools. There is no phone-pairing or mobile-delivery setup.

### Localization and advisory translation

Choose a language in **Settings → Appearance** and restart when requested. Russian has broad coverage but is not complete; English fallback and hard-coded English text remain. Other translations also vary in coverage. Do not assume every Discord reply is localized.

Community `messages_<lang>.properties` files go in the active data directory's `lang/` folder. Scarlet can generate an English template; `langlint` checks missing/unknown keys and placeholder consistency. The separate [historical translation-string inventory](TRANSLATION.md) is not a current setup guide, and its line numbers may be outdated.

`translate-advisories` translates selected saved prose using MyMemory by default or a configured LibreTranslate endpoint. Review which text will leave the computer; names, IDs, and tag identifiers are not meant to be translated. `restore-advisories` restores saved originals for watched groups, watched users, watched avatars, and moderation tags where originals exist.

### Training mode

Enable the Training setting to use **Edit → Simulate event (training)** or `simulate`. Supported kinds include `join`, `watched`, `wuser`, `new`, `mixed`, `pronouns`, `avatar`, `vtk`, `leave`, `warn`, `kick`, and `ban`.

Training uses synthetic IDs and marked records, shows a training player list while keeping the live list updated in the background, and restores the live view when turned off. Synthetic Discord events go only to the configured training channel. Synthetic target ban/unban/timed-ban actions check permission and return a simulated result rather than changing a real user's moderation state.

**Training is not a global dry-run switch.** Ordinary commands targeting real users or real instances are not made harmless by enabling Training. Use the simulator's synthetic targets for drills. Permission checks can still require real account/group information. Training records are excluded from the saved-history moderation summary.

## Automatic VRChat launch and exit — a11 preview

These are separate opt-in features, not interchangeable toggles.

### Join an active instance at startup

**VRChat Client: Launch on Instance Create / Startup** can check for active instances of the configured group on Scarlet startup and request a safe launch into the newest eligible instance. It also follows the configured instance-create path. Access requirements, age verification, current lifecycle state, and unambiguous client tracking still apply. It is not a promise to take over every already-running client or to switch to every second instance a group opens.

### Exit after a closed instance becomes empty

**VRChat Client: Exit when instance is closed and empty** (`vrchat_client_exit_closed_empty`) is off by default. Saving the toggle takes effect without restarting Scarlet. It can close the **sole verified same-OS-user VRChat client, including a manually launched one**.

It requires a matching current-session group closure audit, a healthy caught-up local output-log roster containing only the authenticated client account, a full 30-second healthy grace period, and fresh roster/session/process checks. An empty but still-open instance does not qualify. The feature closes the client, not the server instance.

This exit path does **not** wait for separate API player-count confirmation. Missing self, unread/stale logs, another player, changed session, uncertain process identity, or multiple clients prevent exit. Multiple clients can produce a rate-limited warning in the operations channel; there is no ambiguity-bypass button. Close-audit delivery can still delay the decision.

Normal close comes first. A **separate default-off Windows force-close option** permits termination of the exact verified process only after at least 15 seconds and fresh checks. It can lose unsaved client state. It does not kill by a blanket executable name or request administrator elevation. Linux/Wine process visibility may limit automatic exit; the Windows force fallback is unavailable there.

### Recover a stalled automatic startup

**VRChat Client: Recover stalled auto-launch** watches verified Scarlet-owned automatic launches, not manually launched clients. The a11 policy allows at most two replacement launches, waits at least three minutes per attempt, and requires guarded quiet-log evidence or three separately spaced paired connection-failure/reset cycles. A successful world join ends the watch; its window expires after ten minutes per attempt.

Scarlet first attempts normal close, verifies exit, rechecks the target, and obtains fresh join information before relaunching. Startup recovery has its own separate default-off Windows force-close option. It does not open a second client while the first remains running, recover arbitrary later gameplay freezes, or bypass instance access/age verification.

### Closing an idle server instance is different

The optional idle-instance close policy acts on the bot's current server instance, not merely the local executable. It retains its own current-session, group, local-roster, and fresh API occupancy checks, then requests a soft close. It cannot make verification and closing one atomic server operation. Do not confuse its requirements with the client-exit policy above.

## Saving, export, restore, and diagnostics

### Back up and migrate

Use **Settings → Backup & Migration** or the first-run **Restore from export / backup** option. A Scarlet migration export or automatic pre-import backup ZIP is different from a generic ZIP, individual `.bak`, or manually created archive.

Existing-session import/export asks Scarlet to stop and save before a separate offline tool acquires the data/preference locks. Logging stays stopped until Scarlet starts again. Preview builds include console progress messages; **“Created pre-import migration backup” means a safety backup was created, not that the import completed**. Wait for the final success/failure result.

Exports require a new destination outside the data folder and a PIN. The verified-export deletion option and any cleanup are separate destructive choices; cancelling later does not undo cleanup. Keep an independent backup until restore and sign-in have been checked. A restored expired session may still require login/2FA.

Example offline entry points, preserving the same data location:

```text
java "-DSCARLET_HOME=C:\Scarlet-Data" -jar scarlet-0.6.1-a11.jar --restore
java "-DSCARLET_HOME=C:\Scarlet-Data" -jar scarlet-0.6.1-a11.jar --export
```

The console `--import-bundle "<bundle.zip>"` workflow also exists. Its `--data-only` choice excludes incoming credential stores; it is not a substitute for reviewing the import's destination and options. Private plaintext staging requires enforceable filesystem permissions and can refuse an unsuitable filesystem.

### Health and known limitations

- **Help → Scarlet: Diagnostics** shows current connectivity/rate-limit/provider information without issuing extra API checks.
- `/set-ops-alert-channel` routes configured operational warnings. Log-reader stalled/recovered messages use observed activity; quiet healthy sessions and starting VRChat after Scarlet has been idle can still cause misleading notices. A recovered notice is not proof that all components are healthy.
- Session recovery may attempt unattended authentication, but expired credentials, 2FA, permissions, and network failures can still need operator action.
- Audit catch-up is bounded by `audit_catchup_max_days` (default 14 in a11). Events beyond that window can be skipped; catch-up is not guaranteed to finish within minutes.
- Guarded saves and backups reduce corruption risk; no application can guarantee that a crash, disk failure, or external edit never loses data.
- An intermittent Windows internal-communication shutdown-test timeout remains unresolved. Static package checks do not establish live Linux/PowerPC, GUI/audio, or service compatibility.

When reporting a problem, include the exact Scarlet build, relevant setting names, approximate event time, and a narrowly scoped Scarlet log excerpt. Review/redact credentials, tokens, private join links, and unnecessary personal information before sharing. Do not post migration bundles or credential stores in public issues.

### Where extended events come from

Staff join/leave, observed user activity, and vote-to-kick initiation depend on the local client log. **Instance Inactive** is instead inferred from changes in the group API's active-instance list. Both are Scarlet-derived events, not interchangeable with native server audit entries.

## CLI and developer references

Run `help` in Scarlet's CLI tab for the current command list. Useful commands include `info`, `explore`, `tts <message>`, `simulate <kind> [name]`, `langlint`, `translate-advisories`, `restore-advisories`, `restart`, and `stop`. A `tts` command uses the configured output route; local and Discord outputs have different requirements. `vrchatapi-test` contacts the live VRChat API and is not an offline diagnostic.

See [BUILDING.md](BUILDING.md) for release-source builds and pinned dependencies, [SETTINGS.md](SETTINGS.md) for controls, [SECURITY.md](SECURITY.md) for data/network handling, and [FAQ.md](FAQ.md) for common setup and troubleshooting questions. Release notes and testing disclosures describe limitations; they are not a guarantee that every feature was live-tested on every platform.
