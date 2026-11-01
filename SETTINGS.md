
# Settings

This reference describes the current development source and the features already
available in the published 0.6.1-a11 preview. As of November 1, 2026, stable is
0.6.0; plain 0.6.1 is still a draft and development 0.6.1-a12 is not a published
release. A feature marked as added in a preview is not promised in stable 0.6.0.

Use Scarlet's settings UI and authorized Discord configuration commands whenever
possible. Save settings through the UI; do not replace a working configuration
with the examples below. Stop Scarlet before any necessary manual file edit and
keep an independent, private backup. A new JAR filename does not create a new
data profile, and two versions must not share a running profile.

### Data location

The default Windows data folder is `%LOCALAPPDATA%\Hatson321\Scarlet Logger`.
On Linux it is `$XDG_DATA_HOME/Hatson321/Scarlet Logger`, or
`~/.local/share/Hatson321/Scarlet Logger` when `XDG_DATA_HOME` is unset. An explicit
`SCARLET_HOME` environment variable or `-DSCARLET_HOME="<data-folder>"` Java option
selects another profile; the Java option takes precedence. Use a real, writable
folder, not a virtual location such as Windows “This PC.” Confirm the resolved
folder in Scarlet's startup log instead of assuming it is next to the JAR.

When transferring from an earlier data folder, Scarlet asks before copying data
and leaves the earlier folder unchanged for rollback. Some preferences also use
Java Preferences (the Windows registry on Windows); copying only `settings.json`
is not a complete credential/configuration backup. Use Scarlet's export/restore
flow and keep its archive and PIN private.

The illustrated objects below use JSON with full-line `//` comments for explanation,
not a complete default configuration. Remove comments for a strict JSON validator.
IDs and paths are placeholders; no token, password, cookie or private join link
should be copied into documentation, screenshots or support posts.

## file `logs/scarlet_log_0000-00-00_00-00-00.txt`

Files containing the output of the application log.

## file `caches/XXX/XXX_00000000-0000-0000-0000-000000000000.json`

Files containing cached objects from the VRChat API

## file `avatar-provider-cache.json`

Starting with 0.6.1-a11, external avatar-provider results are saved privately inside
the current Scarlet data profile. Repeated matching lookups can reuse these results
after restarting Scarlet. This is separate from the VRChat API object cache above.

- Successful results expire after 24 hours; successful empty results expire after
  five minutes. Errors, timeouts and rate-limit responses are not cached as empty.
- The cache retains at most 256 entries, with an 8 MiB total size ceiling and a
  512 KiB ceiling per entry. Oversized successful results can still be used for the
  current lookup without retaining them. Evicted or expired entries require a new lookup.
- Results are kept separate for each provider, lookup mode, exact query and requested
  result count. Avatar names alone are not treated as proof of an avatar's identity.
- Only public avatar metadata is saved, not image files, login credentials or the
  operator's identity. Query/provider keys are hashed, but the metadata is encoded,
  **not encrypted**. Treat the file as private profile data.
- Existing provider consent and selection still apply to cache hits. Disabling consent
  prevents further use; it does not delete already saved metadata. Use **Settings →
  Avatar search providers → Manage… → Clear saved avatar results** to clear it.
- If Scarlet cannot safely protect or read the cache file, it logs a warning and
  continues with a bounded memory-only cache. It does not weaken file permissions.

Provider responses also have an 8 MiB transfer/decoded limit and a maximum of 5,000
records (or the smaller requested count). Initializing or clearing the cache does
not itself query a provider; normal opted-in searches and automatic avatar lookups
still run as needed.

## file `caches/XXX/known_404s.json`

List of ids known to return a `HTTP 404 Not Found` from the VRChat API
```json
[
    "XXX_00000000-0000-0000-0000-000000000000",
    "XXX_11111111-1111-1111-1111-111111111111"
]
```

## file `discord_bot.json`

Settings for the Discord bot account. Enter the bot token through Scarlet's setup
flow, not a sample JSON file. Legacy plaintext token fields are migration inputs,
not the recommended storage method. Audit, extended-event, confidential and
operations-alert destinations are separate assignments: configuring one does not
configure the others. Use `/set-audit-channel`, `/set-audit-ex-channel` and the
corresponding secret-channel commands for their respective events.

Use `/scarlet-discord-permissions list` to inspect a role or user's explicit
interaction permissions, selecting the required `target` and optional `type`.
Authorized administrators can use `/scarlet-discord-permissions set` with `target`,
`type`, `name` and `value` (`Allow`, `Deny` or `Default`) to change that policy.
Use the command's choices and autocomplete rather than inventing permission names.
Current **Other** permission identifiers include `groupex-bans-manage` and
`groupex-tags-edit`. Empty explicit listings do not mean the user has no effective
authority: owner/admin handling, defaults and action-specific checks still apply.
The old `scarletPermission2roleSf` map is retained for compatibility; its
`/config-info` display is not the active DPerms policy. Historical keys such as
`event.set_tags` and `event.submit_evidence` are not current permission identifiers.
```json
{
    // The Discord server snowflake id
    "guildSf": "123456789123456789",

    // Optional. The Discord channel snowflake id used for Scarlet's Discord-native moderation and join logs
    "discordActionLogChannelSf": "123456789123456789",

    // Discord account age alert threshold, in minutes. 0 disables alerts. Maximum is 525600 (365 days).
    "discordAccountAgeAlertMinutes": 1440,

    // Whether the bot posts the age-verification prompt in new Ticket Tool ticket channels
    "ticketToolAutoResponseEnabled": true,

    // Optional. Discord category snowflake id for Ticket Tool channels
    "ticketToolCategorySf": "123456789123456789",

    // Optional. Java regex matched against ticket channel names
    "ticketToolChannelNameRegex": "(?i).*ticket.*",

    // Optional. Discord role snowflake id to ping alongside the ticket opener
    "ticketToolNotifyRoleSf": "123456789123456789",

    // Message the bot sends after pinging the inferred ticket opener
    "ticketToolAgeVerificationMessage": "Contact the server's moderation team for its verification process. Do not post sensitive documents in public channels.",
    
    // Optional. The voice channel snowflake id
    "audioChannelSf": "123456789123456789",
    
    // Optional. Path to store evidence submitted for certain audit events
    "evidenceRoot": "C:/Users/Scarlet/Desktop/evidence",
    
    // Legacy compatibility map, not the current interaction-permission policy.
    // Manage active permissions with /scarlet-discord-permissions instead.
    "scarletPermission2roleSf": {},
    
    // Map of auxiliary webhook internal ids to Discord webhook urls
    // Credential-bearing webhook URLs are deliberately omitted from this example.
    "scarletAuxWh2webhookUrl": {},
    
    // Map of audit entry event type to Discord channel snowflake id
    "auditType2channelSf": {
        "group.instance.kick": "123456789123456789",
        "group.user.ban": "123456789123456789",
        "group.instance.create": "123456789123456789"
    },
    
    // Map of extended entry event type to Discord channel snowflake id
    "auditTypeEx2channelSf": {
        "groupex.instance.staff.join": "123456789123456789",
        "groupex.instance.staff.leave": "123456789123456789",
        "groupex.instance.vtk": "123456789123456789"
    },
    
    // Map of audit entry event type to auxiliary webhook internal id(s)
    "auditType2scarletAuxWh": {
        "group.instance.kick": "External Webhook One",
        "group.instance.create": [ "External Webhook One", "External Webhook Two" ]
    },
    
    // Map of audit entry event type to Discord secret channel snowflake id
    "auditType2secretChannelSf": {
        "group.instance.kick": "123456789123456789",
        "group.user.ban": "123456789123456789",
        "group.instance.create": "123456789123456789"
    },
    
    // Map of extended entry event type to Discord secret channel snowflake id
    "auditTypeEx2secretChannelSf": {
    },
    
    // Map of audit entry event type to hex RGB
    "auditType2color": {
        "group.instance.kick": "FF7F00",
        "group.user.ban": "FF0000",
        "group.instance.create": "00FF00"
    },
    
    // Legacy queue migration input, not the current executable queue.
    // Current receipts are managed in data/moderation_queue.json.
    // Legacy entries are held for operator review, not automatically replayed.
    // Submit new actions through the authorized UI/commands; do not hand-edit receipts.
    "queuedActions": []
}
```

## file `discord_perms.json`

Permissions for Discord users and roles, where`"<PermissionType>"` is one of
- `"SLASH_COMMAND"`
- `"USER_COMMAND"`
- `"BUTTON_PRESS"`
- `"STRING_SELECT"`
- `"ENTITY_SELECT"`
- `"MODAL_SUBMIT"`
- `"OTHER"`
and where `"<PermissionName>"` depends on the type of the permission.
For all commands, it is the command name, e.g., `vrchat-search`.
For slash subcommand groups and subcommands, it is the full command name, with a space ` ` between each name component, e.g., `vrchat-search world`
For slash command arguments, it is the full command name of its parent, as above, a space ` `, and the argument name, e.g., `vrchat-search world search-query`
```json
{
    // Map of permission type to permission set
    "<PermissionType>": {
        "byUser": {
            "123456789123456789": {
                "<PermissionName>": true
            }
        },
        "byRole": {
            "123456789123456789": {
                "<PermissionName>": false
            }
        }
    }
}
```

## file `watched_XXX.json`

Array of watched entities (groups, users, avatars)
```json
[
    {
        // The VRChat id
        "id": "grp_00000000-0000-0000-0000-000000000000",
        
        // The category of activity, one of: "UNKNOWN", "MALICIOUS", "NUISANCE", "COMMUNITY", "AFFILIATED", or "OTHER"
        "type": "NUISANCE",
        
        // Array of custom moderation tags associated with activity
        "tags": [
            "trolling"
        ],
        
        // Order in whichentities are considered, lowest first
        "priority": -100,
        
        // Whether action must be taken rapidly
        "critical": true,
        
        // Whether TTS message is skipped
        "silent": false,
        
        // Message announced in the voice channel via TTS
        "message": "Anti-tupper group. Retaliate with documentation of custom tags."
    }
]
```

## file `event_schedule.json`

Specification for scheduled events
```json
{
    // Map of scheduled event ids to their specification
    "eventSpecs": {
        "<spec id>": {
            "id": "<spec id>",
            
            // Whether the event should be scheduled
            "active": true,
            
            // Whether a mirror event should be scheduled on Discord
            "mirrorOnDiscord": true,
            
            // The maximum number of this event to have scheduled at once
            "maxPending": 3,
            
            // The frequency with which an event is scheduled
            "frequency": "EVERY_DAY",
            
            // The next date for which the event will be scheduled
            "date": "2025-12-19",
            
            // The time, with offset from UTC, at which the event should occur
            "time": "22:15:00-06:00",
            
            // The frequency with which an event is scheduled
            "duration": "PT1H",
            
            // Certain template attributes of the event
            "vrcCalendarEventParameters": {
                // see <https://vrchat.community/reference/create-group-calendar-event>
            },
            
            // Certain template attributes for event instances
            "vrcInstanceParameters": {
                // see <https://vrchat.community/reference/create-instance>
            },
            
            // Map of upcoming dates to specifics about scheduled events
            "pending": {
                "2025-12-18": {
                    
                    // When an instance should be created for the event
                    "needsCreate": "2025-12-18T22:14:30-06:00",
                    
                    // When the event is to begin
                    "start": "2025-12-18T22:15:00-06:00",
                    
                    // When the event is to end
                    "end": "2025-12-18T23:15:00-06:00",
                    
                    // When the instance should be closed
                    "needsClose": "2025-12-18T23:15:30-06:00",
                    
                    // The owning group of the event
                    "vrchatGroupId": "grp_00000000-0000-0000-0000-000000000000",
                    
                    // The id of the event
                    "vrchatEventId": "cal_00000000-0000-0000-0000-000000000000",
                    
                    // The discord server in which a mirror event was posted
                    "discordGuildSf": "1234567890123456789",
                    
                    // The snowflake of the mirror event
                    "discordEventSf": "1234567890123456789"
                }
            }
        }
    },
    
    // An alternate Discord server snowflake id in which to post the Discord events
    "alternateGuildSf": "123456789123456789"
}
```

## file `moderation_tags.json`

Array of custom moderation tags
```json
[
    {
        // The internal name of the tag
        "value": "trolling",
        
        // The display name of the tag
        "label": "Trolling",
        
        // The description text of the tag
        "description": "Provocative or mocking behavior intended to antagonize someone"
    },
    {
        "value": "dislikes_tupper",
        "label": "Dislikes tupper",
        "description": "The most severe of all offences"
    }
]
```

## file `staff_list.json`

Array of VRChat userIds
```json
[
    "usr_00000000-0000-0000-0000-000000000000",
    "usr_11111111-1111-1111-1111-111111111111"
]
```

## file `secret_staff_list.json`

Array of VRChat userIds
```json
[
    "usr_00000000-0000-0000-0000-000000000000",
    "usr_11111111-1111-1111-1111-111111111111"
]
```

## file `report_template.txt`

Template for filling out the VRChat Help Desk report form
```txt
I am submitting a user report to be sent for {targetName} for {tags} in our group {groupCode}.
{targetName} entered our {instanceType} instance at {targetJoined}.
{targetName} was removed/left from our instance at {targetLeft}.
{description}

Thank you in advance,
{actorName}
```
Parameters are as such:
| Parameter | Description | Example value |
| --- | --- | --- |
| Group | | |
| `{groupId}` | The group ID | `grp_00000000-0000-0000-0000-000000000000` |
| `{groupName}` | The group name | `MyGroup` |
| `{groupUrl}` | The group URL | `https://vrchat.com/home/group/grp_00000000-0000-0000-0000-000000000000` |
| `{groupCode}` | The group short code | `GROUP.0000` |
| `{groupCodeUrl}` | The group URL via short code | `https://vrc.group/GROUP.0000` |
| Location | | |
| `{worldId}` | The world ID | `wrld_00000000-0000-0000-0000-000000000000` |
| `{worldName}` | The world name | `WorldName` |
| `{worldUrl}` | The world URL  | `https://vrchat.com/home/world/wrld_00000000-0000-0000-0000-000000000000` |
| `{location}` | The entire location string | `wrld_00000000-0000-0000-0000-000000000000:00000~group(grp_00000000-0000-0000-0000-00000000000)~groupAccessType(public)~region(us)` |
| `{instanceType}` | A formatted representation of the instance type | `18+ Group Public` |
| Actor | | |
| `{actorId}` | The actor user ID | `usr_00000000-0000-0000-0000-000000000000` |
| `{actorName}` | The actor user display name | `DisplayName` |
| `{actorUrl}` | The actor user URL | `https://vrchat.com/home/user/usr_00000000-0000-0000-0000-000000000000` |
| Target | | |
| `{targetId}` | The target user ID | `usr_00000000-0000-0000-0000-000000000000` |
| `{targetName}` | The target user display name | `DisplayName` |
| `{targetUrl}` | The target user URL | `https://vrchat.com/home/user/usr_00000000-0000-0000-0000-000000000000` |
| Target ext. | | |
| `{targetJoined}` | The formatted time the target joined the relevant instance | `2025-03-04 05:06:07 UTC` |
| `{targetLeft}` | The formatted time the target left the relevant instance | `2025-03-04 05:06:07 UTC` |
| Audit | | |
| `{tags}` | The custom moderation tags | `Trolling, Harassing, and Insufferable Attitude` |
| `{description}` | The custom moderation description | |
| `{auditId}` | The audit ID | `gaud_00000000-0000-0000-0000-000000000000` |
| Misc | | |
| `{appName}` | The name of this application | `Scarlet` |
| `{appVersion}` | The version of this application | `0.4.10` |

## file `settings.json`

General settings for Scarlet. Settings below are separate controls, not a single
“automatic mode.” Current source defaults for the main monitoring/client controls are:

| Setting key | Default | Meaning |
|---|---|---|
| `audit_polling_interval` | `60` seconds | Audit polling interval, allowed range 10–300 seconds; shared API pacing/cooldowns can add a wait. |
| `audit_catchup_max_days` | `14` days | Startup catch-up window, allowed range 1–90 days; older missed history is skipped with a local warning. |
| `auto_close_bot_instance_minutes` | `0` (off) | Separately requests a normal server-instance close after the configured idle period and safety checks. |
| `vrchat_client_launch_on_instance_create` | `false` | Optional client launch/follow and eligible startup-instance joining. |
| `vrchat_client_recover_stalled_startup` | `false` | Bounded recovery of a verified Scarlet-owned automatic launch, not any manually started client. |
| `vrchat_client_exit_closed_empty` | `false` | Exits the qualifying local VRChat client after its monitored instance is already closed and empty of other players. Scarlet itself stays running. |
| `vrchat_client_force_stalled_startup` | `false` | Separate Windows-only forced-exit fallback for startup recovery. |
| `vrchat_client_force_closed_empty` | `false` | Separate Windows-only forced-exit fallback for closed/empty client shutdown. |

### Client shutdown is not instance closure

In the 0.6.1 previews, closed/empty client exit requires a matching closure audit,
a healthy caught-up local log session, known self with no other players, a full
30-second grace period and fresh exact-process/session checks. It no longer waits
for an independent API occupancy count. Delayed closure audits can still delay
exit; missing or unhealthy logs cannot prove that the instance is empty. An empty
but still-open instance does not qualify.

The only verified same-user client can qualify, including a manually launched
client; multiple clients block selection. Startup recovery remains stricter and
requires Scarlet launch ownership. Normal close is attempted first. Each separate
Windows force-close option requires fresh checks and at least 15 seconds for normal
exit, and can lose unsaved client state. Leave those options off unless that risk
is acceptable. Neither option authorizes a blanket process-name kill.

The idle **server-instance** close setting remains separate and retains its fresh
API occupancy check. A normal soft close prevents new joins but does not eject
existing occupants. Stable 0.6.0's older client-exit policy is documented in its own
release notes; do not apply a preview's changed process eligibility to that build.

### Health alerts and saved-history summaries

Use `/set-ops-alert-channel` to choose the optional operations/health destination.
It does not configure moderation, vote-to-kick or confidential audit routes. Health
alerts are best-effort notices, not proof that an event was delivered or that a
particular permission is missing. A log-stall warning can appear after more than
five minutes without observed log lines while VRChat is running; the known false
startup log-stall warning is not fixed by this documentation. Check the local log
and actual tracking state before resetting credentials or changing permissions.

`moderation-summary` reads saved structured audit history on the logger computer,
not Discord message history or diagnostic text logs. It runs in a dedicated
background worker, with one summary at a time. Unreadable records are skipped,
excluded from totals and counted in the result. Missing or never-collected history
cannot be reconstructed; safety/resource limits can still stop a scan. Other
commands, including `actor-moderation-summary` and `outstanding-moderation`, remain
API-backed.

### External avatar-provider consent

External provider lookups are off until the owner explicitly accepts the current
notice. Selected providers receive the lookup data and Scarlet's identifying
User-Agent, including configured group and primary signed-in account IDs plus
public support contacts. Consent covers manual searches and automatic
avatar-change/hydration/rescan lookups; enabling a provider is not anonymous.
Older consent keys do not substitute for the current consent-v2 decision.

Disabling consent or deselecting a provider also prevents reuse of its saved
results. It does not erase already saved metadata; use the clear-cache control
described under `avatar-provider-cache.json`. That preview feature stores metadata,
not avatar image files, and does not make its contents encrypted or safe to share.

### Windows rendering compatibility

In **Settings -> General -> Appearance**, enable **Windows rendering compatibility
(restart required)** only if Scarlet has display corruption that improves when
launched with `-Dsun.java2d.d3d=false`. The default is off. This option is hidden
and has no effect on Linux. It is also available in Staff Mode.

Click **Save Settings**, close Scarlet completely, and reopen it. Java chooses
its rendering pipeline early, so the checkbox does not change the current window.
The saved option disables Java Direct3D rendering for Scarlet on Windows; it does
not change the Windows display settings or other applications. It may change UI
rendering performance and is a compatibility workaround, not a graphics-driver repair.

If the display is difficult to use, launch this build once with
`java "-Dsun.java2d.d3d=false" -jar "scarlet-<version>.jar"` (replace `<version>` with
your downloaded build), enable the checkbox and save, then close it and launch
normally using Java 11 or newer, preferably Java 21. Double-clicking uses the
Windows JAR association, which may select a different Java installation. Explicit Java options
(including a `.bat` launcher's `-Dsun.java2d.d3d=false` or `true`) take precedence
over the checkbox. To return to normal rendering, turn it off, save and restart
without that explicit option.

The option is read from the resolved data home's `settings.json` before the UI
starts. When a startup folder chooser or legacy-transfer prompt has multiple
possible homes, a compatibility request from any offered/eligible home is used
for that launch so the prompt can be drawn correctly. No home's saved preference
is changed by that fallback. Missing, malformed, oversized or unsafe settings
leave the existing Java rendering options unchanged.

### Settings example

```json
{
    // Windows-only Java Direct3D compatibility workaround; save and restart to apply.
    "windows_rendering_compatibility": false,

    // VRChat credentials belong in Scarlet's setup UI and protected credential store.
    // Legacy vrc_username/vrc_password/vrc_secret fields are not configuration examples.

    // The VRChat groupId of the relevant group
    "vrchat_group_id": "grp_00000000-0000-0000-0000-000000000000",

    // Whether members are automatically invited to a VRChat group after they verify/link
    "auto_invite_group_on_verify": true,

    // Discord role snowflake that makes a linked member eligible for the auto-invite
    "verified_role_snowflake": "123456789123456789",

    // Optional Discord role snowflake required before Scarlet prompts a member to link their VRChat account
    "members_role_snowflake": "123456789123456789",

    // Optional VRChat group id to auto-invite verified members to.
    // Leave unset/default to use "vrchat_group_id". Scarlet's VRChat account must be a member
    // of the chosen group and must have permission to invite members.
    "auto_invite_group_id": "grp_00000000-0000-0000-0000-000000000000",
    
    // Whether the generated report link appends a footer containing the Group ID, Audit ID, and Scarlet version
    "vrchat_report_template_footer": true,
    
    // The voice in which TTS speaks.
    // On Linux, command-line engines are prefixed, e.g. "flite/slt", "pico2wave/en-US", "festival/default", or "espeak-ng/en".
    // Extra Linux engines can be installed from Settings -> Text-to-Speech -> Install Linux TTS voices.
    "tts_voice_name": "Microsoft Zira Desktop",
    
    // Whether to output TTS to the default system audio device
    "tts_use_default_audio_device": false,
    
    // Whether to announce watched users with TTS
    "tts_announce_watched_users": true,
    
    // Whether to announce watched groups with TTS
    "tts_announce_watched_groups": true,
    
    // Whether to announce watched avatars with TTS
    "tts_announce_watched_avatars": true,
    
    // Whether to announce new players with TTS
    "tts_announce_new_players": true,

    // Whether watched-user advisories appear in the Instance table.
    // When false, the matching join-time TTS callout is also suppressed.
    "advisory_show_watched_users": true,

    // Whether watched-group advisories appear in the Instance table.
    // When false, the matching join-time TTS callout is also suppressed.
    "advisory_show_watched_groups": true,

    // Whether watched-avatar advisories appear in the Instance table.
    // When false, the matching avatar TTS callout is also suppressed.
    "advisory_show_watched_avatars": true,

    // Whether new-account advisories appear in the Instance table.
    // When false, the matching join-time TTS callout is also suppressed.
    "advisory_show_new_players": true,

    // Whether mixed-character-name advisories appear in the Instance table.
    // When false, the matching join-time TTS cue is also suppressed.
    "advisory_show_mixed_character_names": true,

    // Whether vote-to-kick advisory audio is enabled.
    // When false, the matching vote-to-kick TTS callout is suppressed.
    "advisory_show_votes_to_kick": true,

    // Whether suspicious-pronoun advisories appear in the Instance table.
    // When false, the matching join-time TTS callout and row highlight are also suppressed.
    "advisory_show_suspicious_pronouns": true,

    // Whether to play a cue and announce when a joined user's display name mixes letter scripts
    "tts_announce_mixed_character_names": true,

    // Master switch for suspicious-pronoun detection. When false, Scarlet does not flag pronoun fields
    // at all: no Advisory entry, no TTS announcement, and no player-list highlight
    "tts_flag_suspicious_pronouns": true,

    // Whether to speak a TTS line when a pronoun field is flagged
    // (only applies when "tts_flag_suspicious_pronouns" above is enabled)
    "tts_announce_suspicious_pronouns": true,
    
    // Threshold for what account age qualifies as a 'new' player for the above (in days, 1 to 365, default 30)
    "tts_announce_players_newer_than_days": 30,
    
    // Whether to show a popup if a new version is availiable
    "ui_alert_update": true,
    
    // Whether still to show the above popup if a new preview version is availiable
    "ui_alert_update_preview": true,
    
    // Whether the UI becomes visible immediately or after loading is complete
    "ui_show_during_load": false,
    
    // Whether the CLI tab is shown in the main window (toggle live via the View menu)
    "ui_show_cli_tab": true,

    // The time, in seconds, between successive polls for audit events (10-300 inclusive)
    "audit_polling_interval": 60,

    // After Scarlet has been offline, how many days of missed audit log it catches up on (1-90 inclusive).
    // Anything older is skipped with a warning in the log, so a long-idle install doesn't spend hours
    // replaying months of old events before live moderation posts resume.
    "audit_catchup_max_days": 14,
    
    // Whether to only list staff with activity on a Moderation Summary
    "moderation_summary_only_activity": false,
    
    // Whether to issue a ping to the Discord user whose associated VRChat user issues an Instance Warn
    "discord_ping_instance_warn": false,
    
    // Whether to issue a ping to the Discord user whose associated VRChat user issues an Instance Kick
    "discord_ping_instance_kick": false,
    
    // Whether to issue a ping to the Discord user whose associated VRChat user issues a Member Remove
    "discord_ping_member_remove": true,
    
    // Whether to issue a ping to the Discord user whose associated VRChat user issues a User Ban
    "discord_ping_user_ban": true,
    
    // Whether to issue a ping to the Discord user whose associated VRChat user issues a User Unban
    "discord_ping_user_unban": false,
    
    // Whether to launch VRChat for newly created group instances and join an existing
    // open instance at Scarlet startup. When several are open, startup chooses the
    // newest using creation-audit timestamps, not the API list order. This runs in
    // the background even if fast instance polling is disabled. A pre-existing
    // VRChat client is left alone; an unverifiable choice is skipped with a log message.
    "vrchat_client_launch_on_instance_create": false,

    // Small Discord profile/avatar thumbnails on staff and regular instance join/leave
    // messages. Toggle in Settings; takes effect on subsequent messages without restart.
    // Uses cached public profiles only: an uncached/missing image is omitted without
    // delaying the log or making an additional VRChat request. Discord controls its size.
    "discord_instance_join_leave_thumbnails": true,
    
    // Whether evidence submission is enabled
    "evidence_enabled": false,
    
    // Explicit permission for identifying external avatar lookups (off until opted in).
    // Enabling allows automatic avatar-change/hydration/rescan requests as well as
    // manual searches; selected services receive lookup data plus the configured
    // group ID and signed-in primary account ID in the User-Agent, with public support contacts.
    // Older avatar_search_external_lookups_enabled / avatar_search_identified_lookups_enabled permission is not reused:
    // owners must opt in again after reviewing the updated provider notice.
    "avatar_search_identified_lookups_consent_v2": false,

    // Whether to use custom VRCX-compatible avatar search providers
    "custom_avatar_search_providers_enabled": false,
    
    // Array of VRCX-compatible avatar search providers
    "custom_avatar_search_providers": [
        "https://example.invalid/vrcx_search.php"
    ]
}
```

## file `pending_moderation_actions.json`

Saved pending moderation actions:
```json
{
    // Map of "{auditEntryType}:{targetUserId}" to actorUserIds
    "group.user.ban:usr_00000000-0000-0000-0000-000000000000": "usr_11111111-1111-1111-1111-111111111111"
}
```

## file `store.bin`

Legacy authentication-cookie storage, read for migration rather than written for
new sessions. Current credentials use the protected credential store. Do not
manually paste cookies here or share the file; cookies can grant account access.
Use Scarlet's authentication/setup flow instead.

## file `data/global.json`

Saved global user information:
```json
{
    // Map of Discord user snowflake ids to VRChat userIds
    "userSnowflake2userId": {
        "123456789123456789": "usr_00000000-0000-0000-0000-000000000000"
    }
}
```

## file `data/live.json`

Current live instance information:
```json
{
    // Map of VRChat locations of live group instances to VRChat group audit entry ids
    "location2AuditEntryId": {
        "wrld_00000000-0000-0000-0000-000000000000:00000~group(grp_00000000-0000-0000-0000-000000000000)~groupAccessType(plus)~region(us)": "gaud_00000000-0000-0000-0000-000000000000"
    },
    
    // Map of VRChat locations of live group instances to Discord messages
    "location2instanceEmbedMessage": {
        "wrld_00000000-0000-0000-0000-000000000000:00000~group(grp_00000000-0000-0000-0000-000000000000)~groupAccessType(plus)~region(us)": {
            "guildSnowflake": "123456789123456789",
            "channelSnowflake": "123456789123456789",
            "messageSnowflake": "123456789123456789"
        }
    }
}
```

## file `data/usr/usr_00000000-0000-0000-0000-000000000000`

User specific information:
```json
{
    // Discord user snowflake id
    "userSnowflake": "123456789123456789",
    
    // Array of group audit entry ids targeting this user
    "auditEntryIds": [
        "gaud_00000000-0000-0000-0000-000000000000"
    ],
    
    // Array of submissions of evidence
    "evidenceSubmissions": [
        {
            // Audit entry id this evidence targets
            "auditEntryId": "gaud_00000000-0000-0000-0000-000000000000",
            
            // Discord snowflake id of the submitting user
            "submitterSnowflake": "123456789123456789",
            
            // Discord display name of the submitting user
            "submitterDisplayName": "ProbablyNotTupper",
            
            // When the file was submitted
            "submissionTime": "2069-02-31T12:34:56.789Z",
            
            // The name of the file
            "fileName": "screenshot.png",
            
            // The url of the attachment when uploaded
            "url": "https://cdn.discordapp.com/attachments/123456789123456789/123456789123456789/screenshot.png",
            
            // The proxy url of the attachment when uploaded
            "proxyUrl": "https://media.discordapp.net/attachments/123456789123456789/123456789123456789/screenshot.png"
        }
    ]
}
```

## file `data/gaud/gaud_00000000-0000-0000-0000-000000000000`

Group audit log event information:
```json
{
    // Discord server snowflake id
    "guildSnowflake": "123456789123456789",
    
    // Discord channel snowflake id
    "channelSnowflake": "123456789123456789",
    
    // Discord message snowflake id (This message has the embed)
    "messageSnowflake": "123456789123456789",
    
    // Discord thread snowflake id
    "threadSnowflake": "123456789123456789",
    
    // Discord channel snowflake id (This message has the interactions)
    "auxMessageSnowflake": "123456789123456789",
    
    // The saved entry itself
    "entry": {
        // <GroupAuditLogEntry Object>, See return data of Groups API:
        // https://vrchatapi.github.io/docs/api/#get-/groups/-groupId-/auditLogs
    },
    
    // Array of custom moderation tags
    "entryTags": [
        "trolling"
    ],
    
    // Array of submissions of evidence
    "entryDescription": "Myriad Cirnos fill the sky",
    
    // User id of the actor who used automation/assistance to effect this event
    "auxActorId": "usr_00000000-0000-0000-0000-000000000000",
    
    // Display name of the actor who used automation/assistance to effect this event
    "auxActorDisplayName": "Somebody",
    
    // Audit entry id of the event that directly and necessarily caused this event
    "parentEventId": "gaud_00000000-0000-0000-0000-000000000000"
}
```

## file `data/ex/00000000-0000-0000-0000-000000000000`

Extended audit event information:
```json
{
    // Extended event id
    "id": "00000000-0000-0000-0000-000000000000",
    
    // Extended event type
    "typeEx": "groupex.instance.vtk",
    
    // Actor id
    "actorId": "vrc_admin",
    
    // Actor display name
    "actorDisplayName": "VRChat Admin",
    
    // Target id
    "targetId": "usr_00000000-0000-0000-0000-000000000000",
    
    // When this extended event occurred
    "timestamp": "2069-02-31T12:34:56.789Z",
    
    // Data
    "data": {
    }
}
```

## Legacy launcher files — not current JAR setup steps

Older launcher distributions used `scarlet.version.target`, `scarlet.home`,
`scarlet.home.java` and `java.options`. The currently published 0.6.0 and 0.6.1-a11
assets do not include that launcher. Running the standard JAR does not make those
files a supported way to select a build, Java installation or data profile.
`scarlet.version` in a source archive is build metadata, not a runtime update switch.

Download the desired JAR from its release, select a supported Java installation
and launch it directly. For example, after replacing `<version>` with the actual
filename:

```text
java -jar "scarlet-<version>.jar"
```

An explicit `-DSCARLET_HOME="<data-folder>"` goes before `-jar` when a separate
profile is intentionally required. Do not reuse the old example JVM-tuning flags:
some are obsolete or unsupported, and they are not required Scarlet settings.
See [BUILDING.md](BUILDING.md) for source-build requirements and runtime/platform limits.

## Preferences and protected credentials

Scarlet uses Java Preferences for some saved state, including UI and per-group
progress. On Windows that backing store is in the user registry. Treat it as
application-managed state, not a registry-editing setup recipe. A profile also
contains credential mirrors/recovery data; these are not disposable log files.

The normal current setup creates a random profile-specific `global-prefs.key` to
protect credential storage; it does not ask users to keep a shared default
password. Keep that key, credential files and backups private. Removing or changing
them can make saved credentials unreadable. Encryption does not protect secrets
from someone who can read both the saved ciphertext and its key. If Scarlet warns
that encrypted storage is unavailable, do not assume the fallback is encrypted.

Advanced deployments may override the encryption password with `SCARLET_GLOBAL_PW`
or `scarlet.global.pw`; the environment value takes precedence. Do not change an
existing override casually or place its value in a shared launcher, screenshot,
support post or command example. Preserve a verified private recovery backup and
use the supported export/restore or credential-reset UI instead of editing the
registry, cookie store or key file by hand.
