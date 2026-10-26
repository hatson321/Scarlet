# Scarlet Logger 0.6.1-a9 — Preview

An unpublished review candidate collecting the fixes since 0.6.0. This is a
pre-release, not the final stable 0.6.1. Back up your Scarlet data before testing.

## What changed

- **Import progress:** console stage/progress, heartbeat and completion messages
  make it clearer when an import is still running or has finished.
- **Optional avatar providers:** updated provider definitions and lookup behavior,
  while retaining explicit consent and provider selections.
- **Startup instance joining:** discover the newest eligible active group instance
  when Scarlet starts; handle age-gated links without bypassing access checks.
- **Responsive player list:** background avatar/profile lookups no longer hold up
  roster updates. Small Discord join/leave thumbnails are optional and cache-based;
  an uncached player may have no thumbnail.
- **Staff Mode alerts:** watched-avatar and vote-to-kick alerts can operate without
  a configured group. Speech fallback, advisory refresh and first-run prompts were
  improved. Applicable Advisory and TTS switches, provider consent and silent flags
  still apply; enabling Staff Mode does not turn every alert on automatically.
- **Windows appearance:** a saved rendering-compatibility option addresses the
  reported neon-color rendering problem and applies on restart.
- **VRChat shutdown:** improved process tracking, diagnostics and bounded retries.
  With the exit toggle enabled, the only verified same-user client can be closed,
  including one launched manually. Multiple clients block selection and produce an
  operations-channel warning.
- **Startup recovery:** optional bounded retries for stalled auto-launches and a
  specific repeated connection-failure loop. Recovery still requires Scarlet launch
  ownership, fresh log evidence and verified exit before another launch.

## Important shutdown behavior

Closed/empty-instance exit uses the matching closure audit and healthy local log
roster, known self, a full 30-second grace and final process/session checks. It no
longer waits for a separate API player-count confirmation. This reduces stale-count
delays but is not a claim that local logs cannot be wrong. Close-audit delays can
still delay shutdown. An empty, open instance does not qualify.

Normal close is attempted first. Startup recovery and closed/empty shutdown each
have a separate, default-off **Windows force-close option**. After at least 15
seconds and fresh checks, this may terminate the exact verified process and lose
unsaved client state. No blanket process-name kill is used. Startup target and
idle server-instance checks retain their API safeguards.

## Running the preview

Use Java 21 for this preview and run `java -jar scarlet-0.6.1-a9.jar` from its folder.
Close other Scarlet versions before import/export. Keep independent backups and
do not run two versions against the same data folder.

## Verification scope

The accompanying draft verification summary records the final artifact hashes and
automated test results. Live watched-alert speech/advisories, import/export and
instance/player-list behavior have received tester confirmations during this series.
Those confirmations do not cover every feature or failure scenario.

Separate live checks remain for vote-to-kick speech, multi-instance newest selection,
the persisted rendering setting and optional force-close paths. Linux desktop/audio
acceptance is not established by offline tests. Keep this candidate unpublished
until its review gates are resolved or the supported scope is explicitly limited.
