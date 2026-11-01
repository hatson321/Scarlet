[discord-invite]: https://discord.gg/CP3AyhypBF
[discord-widget]: https://discord.com/api/guilds/1342131776876838912/widget.png

[![Discord Widget][discord-widget]][discord-invite]

<img src="images/sybyline_scarlet.png?raw=true" alt="Scarlet logo" width="300" height="300"/>

# Scarlet

A self-hostable VRChat Group management utility with Discord integration.

> Scarlet is actively maintained by **Hatson321**. AI may assist with API updates and implementation; changes are reviewed and tested before release, and release notes identify the changes and testing performed.

- [VRChat Group](https://vrchat.com/home/group/grp_f12667c7-df5f-454f-9a34-5ed8c33112a1)
- [Changelog](CHANGELOG.md)
- [Settings](SETTINGS.md)
- [Frequently Asked Questions](FAQ.md)
- [Installation](#installation)
- [Building from source](BUILDING.md)

---

## Why Scarlet?

Scarlet is self-hosted, meaning you have complete control of your group's data.  
Your VRChat and Discord credentials are held only on your own machine — they are never sent to a third party, and no Scarlet-operated service ever receives them.  
Since there is no automatic synchronization of data between groups running Scarlet, you don't have to worry about other groups seeing who your group has moderated or what groups you are tracking.

Scarlet does talk to a small number of outside services — VRChat and Discord because that is the job, plus a handful of **optional** features (avatar search, mobile push, update checks) that are listed individually in [SECURITY.md](SECURITY.md#1-network-connections) along with how to turn each one off. No optional feature is enabled by default. One of them — the mobile companion's off-network relay — does ship pointing at a maintainer-run host, and Scarlet will not contact it until you have paired a phone and accepted an on-screen notice naming that host and what it receives; you can decline and keep using the companion over your own network, or point it at a server you run. If a build behaves differently from that list, treat the list as the claim and the build as the thing to check.

---

## Highlights

- **Cross-platform.** Runs on Windows and Linux (this is the Linux-compatible fork). There is also an **experimental PowerPC edition** (`scarlet-<version>-ppc.jar`) for 32-bit PowerPC Linux; it is heavily untested and has so far only been run on a Nintendo Wii running Wii-Linux. The desktop app is Java/Swing; the VRChat-log features work when VRChat runs on the same machine, including under Proton/Wine on Linux.
- **Near-instant moderation posts.** Moderation actions (Discord command, UI, or bulk-ban queue) and in-game warns/kicks/bans post to Discord within seconds via burst polling, then settle back to the idle interval.
- **Watched users, groups, and avatars** with per-category advisories, spoken TTS callouts, native desktop notifications, and optional mobile push — each independently toggleable.
- **Avatar statuses with or without VRChat's `[API]` launch options.** When the launch options aren't set, Scarlet reconstructs avatar/performance info through avatar-search databases plus current-avatar-image confirmation and the VRChat API, and tells you honestly when something can't be resolved.
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

- **`moderation-summary [hours-back:int?]`** / **`outstanding-moderation [hours-back:int?]`**  
  Generates a summary of moderation actions, or a list of outstanding (unresolved) ones, over the given window.  
  Example: `/moderation-summary "48"`

- **`set-audit-channel <audit-event-type:string> [discord-text-channel:channel?]`**  
  Routes an audit event type to a text channel. Omit the channel to unset.  
  Example: `/set-audit-channel "group.instance.kick" <#log-instance-kicks>`

- **`set-audit-ex-channel <audit-ex-event-type:string> [discord-text-channel:channel?]`**  
  Routes an extended (Scarlet-derived) event type to a channel.  
  Example: `/set-audit-ex-channel "groupex.instance.vtk" <#log-instance-kicks>`

- **`set-audit-secret-channel <audit-event-type:string> [discord-text-channel:channel?]`** / **`set-audit-secret-ex-channel <audit-ex-event-type:string> [discord-text-channel:channel?]`**  
  Routes an audit or extended event type to a *secret* channel (for staff-only, sensitive events).  
  Example: `/set-audit-secret-channel "group.instance.kick" <#log-instance-kicks-secret>`

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

---

### Localization

- The desktop UI and the spoken TTS callouts are translated. **English and Russian** ship complete; German, Spanish, and Indonesian are in-progress pilots that fall back to English for anything not yet translated.
- Pick a language in **Settings -> Appearance** (a dropdown of native names); it defaults to your operating system language and applies on restart. The Discord bot renders each reply in the invoking user's own Discord language.
- **Community translations without a rebuild:** drop a `messages_<lang>.properties` file into the `lang/` folder in Scarlet's data directory and restart — new languages are auto-discovered, and Scarlet writes an up-to-date English `messages.template.properties` there as a starting point. The `langlint` CLI command (and a startup check) validates community files, flagging missing keys and broken `{0}` placeholders.

---

### Training mode

- Enable **Settings -> Training** to unlock an event simulator (Edit -> *Simulate event (training)...*, or the `simulate` CLI command) that fires realistic events on demand — so new moderators can learn on a screenshare instead of a live incident, and nobody has to actually join a bad group.
- The simulator can also fire **moderator warn / kick / ban** events. Each produces a genuine moderation post and thread with the real **Edit tags**, **Edit description**, **Ban / Unban** and **Timed ban** buttons (and `/tag` works in its thread), so a trainer (or a maintainer testing a build) can exercise the whole Discord tagging workflow from a moderator's account without banning anyone. These posts go **only** to the training channel set with `set-training-channel` (nothing is posted if none is set), Ban / Unban / Timed ban on a training user run the real permission checks and then reply with a simulated result without calling VRChat, and drills never appear in moderation summaries (those are counted from VRChat's own audit log).
- Simulated events run the **real** pipeline — the player-list row, TTS callout, desktop/mobile notification, and a genuine Discord post with its ban/unban buttons — so the whole workflow, including tagging in Discord, is practiceable.
- While training, Scarlet behaves as a **separate client**: the real instance is parked and kept updated in the background, actions on training players show real success feedback but make no VRChat call, and turning training off restores the live instance instantly. Everything training-related is marked `[TRAINING]` and, with `set-training-channel`, posts to its own channel — a drill can never be mistaken for a real record.

---

### Reliability and health monitoring

- A background watchdog turns Scarlet's silent failure modes into loud, recoverable ones: if the VRChat log goes quiet while the game is running, or the VRChat session expires mid-run, Scarlet raises a popup, attempts unattended re-login, and (with `set-ops-alert-channel`) posts a Discord health alert.
- Data files (watched lists, settings, Discord config, moderation tags, ...) are written crash-safely and keep the last several dated backups in a `backups/` folder beside each file, so a crash mid-write can't lose a blocklist.
- After downtime, Scarlet catches up on the missed audit log quickly and at most `audit_catchup_max_days` back (default 14, see [SETTINGS.md](SETTINGS.md)), so live moderation posts resume within minutes rather than replaying months of old events.
- The **Diagnostics** view (Help -> Scarlet: Diagnostics...) shows connectivity, rate-limit, and avatar-provider status without making extra API calls.

---

### Desktop and mobile notifications

- Advisories can raise native desktop notifications (Windows Action Center, Linux `notify-send`, macOS `osascript` — PowerShell is never invoked), configured per category in **Settings -> Desktop Notifications**.
- A mobile companion can push the same alerts to a phone; pair it from **Settings -> Mobile Companion**.

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
- A Windows or Linux PC with Java 8 (or newer) installed.

Scarlet runs on both Windows and Linux (this is the Linux-compatible fork). The Discord and VRChat-API features work anywhere; the VRChat-log features (instance monitoring, extended events, avatar statuses) require the VRChat client to be running on the same machine — natively on Windows, or under Proton/Wine on Linux.

If you wish to not install Java for all users on the PC, if you would like to have several different Java installations, or if you would otherwise prefer to keep the Java installation to Scarlet only, see the instructions further below.

> **Installation tutorial:** The previous tutorial is private and outdated. A new tutorial is needed; please use the written instructions below in the meantime.

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

1. Download the latest release (`zip` is recommended): https://github.com/KozyBlake/Scarlet/releases/latest  
2. Copy or extract the files into the directory of your choosing.  
3. If you have Java 8 (or newer) installed to the system PATH, skip this step.  
    - Download and extract a Java 8 JDK, such as one from [Adoptium](https://adoptium.net/temurin/releases/?package=jdk&version=8) (pick your OS/arch — Windows or Linux).  
    - Create (or overwrite) the file `scarlet.home.java` next to `run.bat`.  
    - Copy-paste etc. the root path of the JDK to the **first line** of `scarlet.home.java` and save the file.  
4. If you want Scarlet to store data in a specific folder:  
    - Create (or overwrite) the file `scarlet.home` next to `run.bat`.  
    - Copy-paste etc. path of the desired directory to the **first line** of `scarlet.home` and save the file.  
5. Start Scarlet: on Windows run `run.bat`; on Linux run the included launch script (or start the jar directly with your Java 8+ runtime).
