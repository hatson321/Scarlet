
# Settings

All settings files for Hatson321/Scarlet Logger can be found in `%LOCALAPPDATA%\Hatson321\Scarlet Logger` on Windows or the corresponding XDG data directory on Linux.
When upgrading from an earlier Scarlet data folder, Hatson321/Scarlet Logger asks before copying existing data into the maintained folder and leaves the earlier folder unchanged for rollback.
Some internal volatile data can be found in the Windows registry.
These registry entries exist because of the use of classes in the [`java.util.prefs`](https://docs.oracle.com/javase/8/docs/api/java/util/prefs/package-summary.html) package.

If it isn't obvious, all nominally sensitive values listed are ficticious.

The illustrated objects below use JSON with full-line `//` comments for explanation.
Remove those comment lines before using a strict JSON editor or validator. Do not
copy the sample account IDs, tokens, or paths into a live configuration.

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

Settings for the Discord bot account
```json
{
    // The Discord bot token
    "token": "",
    // DEPRECATED: this value is read, but is no longer written. This value now persists in the encrypted store.
    
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
    "ticketToolNotifyRoleSf": "1479385524786696242",

    // Message the bot sends after pinging the inferred ticket opener
    "ticketToolAgeVerificationMessage": "If your ticket is regarding age verification;\n\nYou have 2 options.\n\nTake a photo of your ID with everything but your birthday covered, with a piece of paper next to it with todays date, the server name, your Discord username, and your VRChat username.\n\nIf you're an NSFW content creator and have a verified Fansly account, you can write within your bio the server name. Once that is done, take a screenshot and also send the link of your profile here.",
    
    // Optional. The voice channel snowflake id
    "audioChannelSf": "123456789123456789",
    
    // Optional. Path to store evidence submitted for certain audit events
    "evidenceRoot": "C:/Users/Scarlet/Desktop/evidence",
    
    // Map of Scarlet-internal permission to Discord role snowflake id
    "scarletPermission2roleSf": {
        "event.set_tags": "123456789123456789",
        "event.submit_evidence": "123456789123456789"
    },
    
    // Map of auxiliary webhook internal ids to Discord webhook urls
    "scarletAuxWh2webhookUrl": {
        "External Webhook One": "https://discord.com/api/webhooks/123456789123456789/IdzL4t3KvfQQ_CKYWd8Q2NM6Z7IaSOppoTTL4LS1Da_aMZ-eK1MbML-3ILnLjotWcDkp",
        "External Webhook Two": "https://discord.com/api/webhooks/123456789123456789/irNaGekj1zzccv__Uy4oC7d2GczNDbilnfBtcc5qL41bZ0Ikw1SXoYc7-bJST-yPdUQB"
    },
    
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
    
    // Array of queued actions
    "queuedActions": [
        {
            // The kind of action being taken
            "action": "ban",
            // The user who queued the action
            "actorId": "usr_00000000-0000-0000-0000-000000000000",
            // The target of the queued action
            "targetId": "usr_11111111-1111-1111-1111-111111111111",
            // The location of the queued action
            "location": "wrld_00000000-0000-0000-0000-000000000000:00000~group(grp_00000000-0000-0000-0000-000000000000)~groupAccessType(plus)~region(us)"
        }
    ]
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

General settings for Scarlet:

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
`java "-Dsun.java2d.d3d=false" -jar "scarlet-0.6.1-a9.jar"`, enable the checkbox and
save, then close it and double-click the JAR normally. Explicit Java options
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

    // The username used to authenticate with VRChat
    "vrc_username": "DefinitelyNOTtupper",
    // DEPRECATED: this value is read, but is no longer written. This value now persists in the encrypted store.
    
    // The password used to authenticate with VRChat
    "vrc_password": "password_cute_robot",
    // DEPRECATED: this value is read, but is no longer written. This value now persists in the encrypted store.
    
    // Optional. The TOTP secret used to two-factor authenticate with VRChat
    "vrc_secret": "YOUR_TOTP_SECRET",
    // DEPRECATED: this value is read, but is no longer written. This value now persists in the encrypted store.
    
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
        "https://website/vrcx_search.php"
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

DEPRECATED: this value is read, but is no longer written. This value now persists in the encrypted store.
Saved cookies for authentication with VRChat, one cookie per line:
```txt
auth=authcookie_00000000-0000-0000-0000-000000000000; expires=Sun, 31 Feb 2069 12:34:56 GMT; path=/; httponly
twoFactorAuth=REPLACE_WITH_YOUR_TWO_FACTOR_AUTH_COOKIE; expires=Sun, 31 Feb 2069 12:34:56 GMT; path=/; httponly
```

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

## file `./scarlet.version`

Contains th version of Scarlet to be run:
```txt
0.4.11-rc2
```

## file `./scarlet.version.target`

Contains the version of Scarlet to which to update (you can "update" backwards if necessary):
```txt
0.4.11-rc3
```

## file `./scarlet.home`

If present, specifies the directory in which to place the application's files:
```txt
C:\path\to\custom\data\dir
```

## file `./scarlet.home.java`

If present, specifies the root directory of the Java installation to use:
```txt
C:\path\to\jdk
```

## file `./java.options`

If present, prepends listed JVM options to the invoked command:
```txt
-Xms8G
-XX:+AggressiveOpts
-XX:+UnlockExperimentalVMOptions
-XX:+UnlockDiagnosticVMOptions
-XX:+AggressiveUnboxing
-XX:+AllowParallelDefineClass
-XX:+OptoBundling
-XX:+OptoScheduling
-XX:+UseFastAccessorMethods
-XX:+UseFastEmptyMethods
-XX:+ParallelRefProcEnabled
```

## registry

Volatile data:
```reg
[HKEY_CURRENT_USER\Software\JavaSoft\Prefs\net\sybyline\scarlet\grp_00000000-0000-0000-0000-000000000000]
; The version of Scarlet that had been run previously
"last/Run/Version"="0.4.9-rc2"
; The last time Scarlet queried the audit log
"last/Audit/Query"="2069-02-31/T12:34:56.789/Z"
; The last time Scarlet refreshed VRChat authentication
"last/Auth/Refresh"="2069-02-31/T12:34:56.789/Z"
; The last time Scarlet checked for updates
"last/Update/Check"="2069-02-31/T12:34:56.789/Z"
; The bounds of the UI window (x,y,width,height)
"ui/Bounds"="979,603,846,400"
```
As the Windows implementation of the `java.util.prefs` package uses the Windows registry, which is case-insensitive, `/` characters are used to "escape" uppercase characters to avoid clashes.

### Encrypted Store

Particular sensitive values are no longer stored in plain text, but instead are encrypted and stored in prefs.
You may specify the password used to encrypt/decrypt by either setting the environment variable `SCARLET_GLOBAL_PW` or the Java system property `scarlet.global.pw`.
It is highly recommended that you use a custom password instead of the default.
