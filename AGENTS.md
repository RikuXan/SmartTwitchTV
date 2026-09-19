# AGENTS.md

Fork-local notes for building this app and deploying it to the Nvidia Shield TV at
`10.0.47.45`. Everything here is specific to this checkout; upstream has no such setup.

## What the repo contains

| Path | What it is |
| --- | --- |
| `app/` | The web app (HTML/JS) that runs inside the APK's WebView. Source of truth. |
| `apk/` | The Android wrapper (Java, Gradle). |
| `apk/app/src/main/assets/app/` | **Build input only** — a copy of `app/`, gitignored. |
| `apk/app/src/main/cpp/` | Signalsmith Stretch JNI bridge (needs the NDK). |
| `../media` (`~/Code/github/RikuXan/media`) | The patched Media3/ExoPlayer fork this build links against, a sibling checkout. |
| `.tmp/docker/` | Dockerfiles for the two build images. |
| `.tmp/logs/twitchll-live.log` | Continuous `TwitchLL` capture written by the `stv-logcat` container. |

Application ids: release is `com.fgl27.twitch`, the same id as the official app, debug is
`com.fgl27.twitch.debug` (`applicationId` + `applicationIdSuffix` in `apk/app/build.gradle`).
The release build therefore **replaces** the official app rather than sitting beside it; Android
refuses to install it over a Play Store copy, so uninstall that first.

## Files that must stay uncommitted

- `apk/app/google-services.json` — untracked local stub; the build fails without it.
- `apk/app/.cxx/` and `apk/.kotlin/` — native/Kotlin build artifacts, listed in
  `.git/info/exclude`. Never stage them; an earlier session committed 149 of these by accident.

## LTB measurement fixes

The sibling `../media` checkout contains the LTB fixes, applied from the tested patch at
`.tmp/ltb-browser/media-ltb.patch`. `apk/settings.gradle` uses that sibling directly. The
development history is the `codex/twitch-ltb-metadata` branch in that same checkout.

The fixes recognize Twitch's `urn:twitch:id3` EMSG scheme and correct double timestamp adjustment
of pending relative MP4 metadata. Three extractor regression tests cover zero-delay and 34 ms
relative cues plus pending absolute cues. These changes are required for playback-timed LTB.

For the focused LTB regression checks, run:

```bash
./gradlew :media3-lib-extractor:testReleaseUnitTest \
  --tests androidx.media3.extractor.mp4.FragmentedMp4EmsgTimestampTest \
  --tests androidx.media3.extractor.metadata.emsg.EventMessageDecoderTest \
  --tests androidx.media3.extractor.metadata.emsg.EventMessageEncoderTest \
  :app:testReleaseUnitTest --no-daemon
```

The full extractor test suite includes slow FLAC seek tests under amd64 emulation.

## The Media3 fork

The player is not a dependency, it is compiled from source alongside the app.

`~/Code/github/RikuXan/media` is a clone of `RikuXan/media` — a fork of `fgl27/media`, itself a fork
of `androidx/media`, base Media3 **1.8**. Build from its `main` branch, which is the repo default and
carries every merged feature. `release` stays a pristine mirror of upstream `4d0e670`. Remotes:
`origin` = `RikuXan/media`, `upstream` = `github.com/fgl27/media` (never push there).

It sits beside this repo, which is the layout `apk/settings.gradle` already expects — no local
modification of that file is needed.

Wiring, in `apk/settings.gradle`:

```groovy
gradle.ext.mediaRoot = "$rootDir/../../media"
gradle.ext.androidxMediaModulePrefix = 'media3-'
apply from: new File(gradle.ext.mediaRoot, 'core_settings.gradle')
```

`core_settings.gradle` `include`s every Media3 library as a source module (`:media3-lib-common`,
`:media3-lib-exoplayer`, `:media3-lib-exoplayer-hls`, …). So **editing a file in that clone is
picked up by the next `./gradlew assembleRelease`** — there is no publish, install or version-bump
step. The flip side is that a player edit recompiles those modules, so expect a full ~3 minute
build, and the change must be committed in that repo separately from the app repo.

### What is patched

Four upstream files differ from `4d0e670`, across six commits:

| File | Change |
| --- | --- |
| `libraries/exoplayer_hls/.../hls/playlist/HlsPlaylistParser.java` | Parses `#EXT-X-TWITCH-PREFETCH` (the unadvertised next segments Twitch exposes with `fast_bread=true`) and `#EXT-X-TWITCH-INFO`, and retains all `#EXT` tags on the parsed playlist so consumers can read them. |
| `libraries/exoplayer_hls/.../hls/playlist/DefaultHlsPlaylistParserFactory.java` | Factory wiring for the parser above. |
| `libraries/exoplayer_hls/.../hls/HlsMediaSource.java` | The low-latency surface: `Factory.setLowLatencyTargetMs`, the `OriginCushionProvider` interface plus `Factory.setOriginCushionProvider`, `REGEX_TWITCH_ORIGIN` to read `ORIGIN="…"` off the loaded multivariant playlist, `originCushionExtraMs()` with retry-while-unresolved, and `updateLiveConfiguration` computing target/max offset and the speed bounds. |
| `libraries/exoplayer/.../audio/DefaultAudioSink.java` | A `pitchFollowsSpeed` static toggle, left in place but **unused** — the pitch-modulation approach to catch-up audio was rejected as audible. |

The app side connects to it in `Tools.buildMediaSource(...)`, which calls
`.setLowLatencyTargetMs(LowLatencyTargetMs)` and
`.setOriginCushionProvider(LowLatency == 1 ? originCushion : null)` on the HLS factory. The custom
speed control (`TwitchLivePlaybackSpeedControl`) lives in the **app** repo, not the fork, and is
attached per player via `ExoPlayer.Builder`.

## Before every build: sync the web app

```bash
rsync -a --delete app/ apk/app/src/main/assets/app/
```

`apk/app/src/main/assets/*` is gitignored, so a fresh clone or worktree has no assets directory at
all. **An APK built without this step silently falls back to the stock hosted web UI** — it runs,
looks almost right, and contains none of the local changes. This is the single easiest way to waste
a build.

## Build

Two images, both amd64 under Podman-backed Docker:

- `stv-android-build` — JDK 17, cmdline-tools, `platforms;android-35`, `build-tools;35.0.0`
  (`.tmp/docker/Dockerfile`).
- `stv-android-build-ndk` — the above plus `ndk;26.1.10909125` and `cmake;3.22.1`
  (`.tmp/docker/Dockerfile.ndk`). **Required**, because `apk/app/src/main/cpp` is part of the build.

```bash
R=/Users/philipp.holler/Code/github/RikuXan/SmartTwitchTV
docker rm -f stv-build 2>/dev/null
docker run -d --name stv-build \
  -v /Users/philipp.holler/Code/github/RikuXan:/work \
  -v stv-gradle-cache:/root/.gradle \
  -v stv-android-config:/root/.android \
  -w /work/SmartTwitchTV/apk stv-android-build-ndk ./gradlew assembleRelease --no-daemon
code=$(docker wait stv-build)
docker logs --tail 3 stv-build
[ "$code" = 0 ] || { echo "build failed (exit $code)"; exit 1; }
```

`docker wait` writes the container's exit code to stdout but exits 0 itself, so a script relying on
`set -e` alone will sail past a failed build and sign and deploy the **previous** APK. Check the
captured code.

The mount is the **parent** directory of both checkouts, not the app repo, because
`mediaRoot` resolves to a sibling of it. Mounting only `SmartTwitchTV` makes
`$rootDir/../../media` land on the image's own empty `/media`, and the build fails in settings
evaluation.

Detached plus `docker wait` rather than `docker run --rm` in the foreground: a full build takes
about three minutes and an interactive run tends to hit tool timeouts. Add `assembleDebug` to the
Gradle invocation when a debug APK is wanted; the release build alone is enough for the Shield.
The named volumes keep the Gradle cache and the SDK's `.android` state across runs — without them
every build re-downloads dependencies.

A container killed mid-build leaves its lock files in `stv-gradle-cache`, and the next build dies in
service creation with "Timeout waiting to lock journal cache". Clear them before retrying:

```bash
docker run --rm -v stv-gradle-cache:/root/.gradle alpine \
  sh -c 'find /root/.gradle -name "*.lock" -delete; rm -rf /root/.gradle/daemon'
```

## Sign

The release build type has no `signingConfig`, so Gradle emits `app-release-unsigned.apk`.

```bash
docker run --rm -v "$R":/project -v ~/.keystores:/keystores:ro stv-android-build-ndk bash -c '
cd /project/apk/app/build/outputs/apk/release &&
cp -f app-release-unsigned.apk app-release-signed.apk &&
/opt/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks /keystores/smarttwitchtv-ll.jks \
  --ks-pass file:/keystores/smarttwitchtv-ll.pass \
  app-release-signed.apk &&
echo signed-ok'
```

`apksigner` is **not on `PATH`** in either image — use the absolute build-tools path. The keystore
and its password file live in `~/.keystores/`; losing them breaks in-place updates of an already
installed build, so they are worth backing up.

## Deploy

The Shield is reached over TCP adb, and **only one adb client may talk to it**. A long-running
container named `stv-logcat` owns that client and continuously appends the app's `TwitchLL` output
to `.tmp/logs/twitchll-live.log`. Starting a second adb client (running `adb` on the host, or in
another container) silently kills that capture, so route every adb command through it:

```bash
cd "$R/apk/app/build/outputs/apk"
docker cp release/app-release-signed.apk stv-logcat:/tmp/
docker exec stv-logcat bash -c '
adb install -r /tmp/app-release-signed.apk &&
adb shell am start -n com.fgl27.twitch/com.fgl27.twitch.PlayerActivity'
```

`adb install -r` stops the running app without restarting it, hence the explicit `am start`. The
app resumes the last stream by itself, so a deploy costs roughly ten seconds of playback.

If `stv-logcat` is gone, recreate it — it reconnects on its own and survives the Shield rebooting:

```bash
docker run -d --name stv-logcat \
  -v stv-android-config:/root/.android \
  -v "$R/.tmp/logs":/logs \
  stv-android-build bash -c '
while true; do
  adb connect 10.0.47.45:5555 >/dev/null 2>&1
  sleep 2
  adb logcat -T 1 -s TwitchLL:V >> /logs/twitchll-live.log
  sleep 5
done'
```

### The capture can die silently

`adb logcat` does not exit when the relay reconnects on a new port — it keeps running with no
device, so the `while` loop never restarts it and `twitchll-live.log` simply stops growing. A
capture that is hours stale with a live app is this, not an app fault. Kill the `adb logcat` pid
inside `stv-logcat` and the loop respawns it against the current connection.

Output to the file is block buffered, so an idle app produces no visible growth for a while even
when the capture is healthy. Confirm with `docker exec stv-logcat bash -c 'timeout 6 adb logcat
-T 1 -s TwitchLL:V | head'` rather than by watching the file.

### Host relay when the container cannot reach the Shield

After the September 9 reconnect, the host could reach `10.0.47.45:5555` but the container
received connection refused. `.tmp/adb/tv-relay.py` forwards a dynamically allocated host
loopback port to the Shield; run it from the app repository and keep it running. It records
its port and PID in `.tmp/adb/tv-relay.port` and `.tmp/adb/tv-relay.pid`. Connect the existing
container's adb client to `host.containers.internal:<port>` and accept the Shield prompt if
shown. This is a TCP relay, not another adb client; all adb commands still use `stv-logcat`.
The container's existing reconnect loop only retries the direct TV address, so reconnect the
relay alias manually if it disconnects. The relay currently runs in the task's terminal session.

## Reading the log

All instrumentation in this fork logs under the `TwitchLL` tag, in **release builds too** — gated
by `LL_DIAG` in `PlayerActivity.java`, deliberately left on.

```bash
docker exec stv-logcat adb logcat -d -s TwitchLL | tail -40      # recent
docker exec stv-logcat adb logcat -d -s TwitchLL | grep presence  # points/drops/watch events
```

Log lines carry the emitting pid. After a deploy the previous process keeps emitting for a few
seconds, so filter by the newest pid (`adb shell pidof com.fgl27.twitch`) before concluding
anything about a fresh build — otherwise you will read the old build's output and think your change
did nothing.

## On-device diagnostic files

Installed September 10 at about 23:53 Berlin time (initial PID 23110). All 47 app tests passed;
the first ADB pull confirmed identical session/sequence-tagged records in file and logcat.
Build and verification evidence is in `.tmp/file-logging/report.md`.

`TwitchDiagnosticLog` mirrors every app-side `TwitchLL` diagnostic to logcat and an asynchronous
file writer. `TwitchDiagnosticFile` stores up to 16 files of 8 MiB each (128 MiB total) under
`getExternalFilesDir(null)/logs`. The Shield path is
`/sdcard/Android/data/com.fgl27.twitch/files/logs/`. No extra permission or debuggable build is
needed. Files survive app updates/restarts; clearing app data or uninstalling removes them.

The bounded queue holds 1024 records. File writes and pruning run on one background thread;
flushes happen approximately once per second. Abrupt process/device shutdown can lose the tail.
Storage failures keep logcat alive and retry file output after 30 seconds. `diagLost` reports a
cumulative, conservative count of file-record losses (including unconfirmed buffered records).
Messages are limited to 3000 UTF-8 bytes before diagnostic fields to keep logcat records bounded.

Use `bash apk/pull-diagnostics.sh` from the host to retrieve a snapshot through the existing
`stv-logcat` client. It writes `.tmp/logs/device/<UTC-time>-<pid>/logs/` via the container's existing
`/logs` mount. Pulling does not stop playback or remove device files. A concurrent rotation could
remove a listed file; retry a failed pull. The active file may end mid-record; ignore an incomplete
last line and include it from the next pull.

File timestamps are UTC ISO-8601. Both outputs carry the identical diagnostic body with
`diagSession` (process UUID), `diagSeq` (process sequence), `diagWallMs`, `diagMonoMs`, and
`diagLost`. Deduplicate overlapping file pulls and logcat captures by `(diagSession, diagSeq)`.
Use monotonic timestamps for intervals within a session; device wall time can drift. File names
are unique per process/part and never renamed while rotating. Source identity still comes from
playback fields such as `ltbServerTimeMs`; one process can play multiple sources.

The Mac-side capture is only a live convenience: laptop sleep suspends its relay/container and
creates gaps. Read on-device files for playback during those gaps. Android's logcat ring buffer
cannot recover hours after the fact. File capture does not need the Mac or ADB connection alive.

## Building from a worktree

`apk/settings.gradle` resolves `mediaRoot` as `$rootDir/../../media`, which from a worktree under
`.claude/worktrees/<name>/apk` lands on `.claude/worktrees/media` rather than the sibling checkout.
Bind mount the media clone at that path instead of creating it on disk:

```bash
docker run -d --name stv-build \
  -v /Users/philipp.holler/Code/github/RikuXan:/work \
  -v /Users/philipp.holler/Code/github/RikuXan/media:/work/SmartTwitchTV/.claude/worktrees/media \
  -v stv-gradle-cache:/root/.gradle -v stv-android-config:/root/.android \
  -w /work/SmartTwitchTV/.claude/worktrees/<name>/apk stv-android-build-ndk ./gradlew assembleRelease --no-daemon
```

A fresh worktree also has no `apk/app/src/main/assets/` and no `apk/app/google-services.json`;
create the first with the rsync above and copy the second from the main checkout.

## VOD in picture in picture

`app/specific/PlayExtraVod.js` lets a VOD and a live stream play together, either way round, and
swaps them with the same DOWN press that swaps two live streams.

Only one VOD plays at a time, because `PlayVod_*`, `ChannelVod_*` and `Main_values.ChannelVod_*` are
a singleton that always describes whichever VOD sits in the **main** player. `PlayExtraVod_Store`
holds that same state while the VOD sits in the small player, and `PlayExtraVod_InPP` says which of
the two is the case. Swapping copies the state across and hands the player screen from one mode's UI
to the other (`PlayExtraVod_EnterVodMain` / `PlayExtraVod_EnterLiveMain`) without restarting either
playback — Java's `SwitchPlayer` already swaps `PlayerObj[0]` and `PlayerObj[1]` including their
`Type`, so seeking, duration and pause keep targeting slot 0 and need no slot parameter.

Choosing a feed cell while the pair is up targets a window by how long ENTER is held, the same for
either kind of content. `1` is the hold gesture on one key.

| big / small | short press a VOD | hold ENTER on a VOD |
| --- | --- | --- |
| live / live | VOD takes the big window | VOD takes the small window |
| live / VOD | refused, the big window would be a second VOD | replaces the VOD in the small window |
| VOD / live | replaces the VOD in the big window | refused, the small window would be a second VOD |

The two refusals are the same rule read from either end: a gesture is blocked only when **its own**
target slot would become the second VOD. `Play_OpenFeed` checks `PlayExtraVod_InPP`,
`PlayExtraVod_KeyEnter` checks `PlayVod_isOn`.

Consequences worth knowing before changing any of it:

- Live and VOD feed cells use **different array indices** (`[2]` title vs created-at, `[9]` logo vs
  language, `[10]` partner vs title, `[13]` viewers vs views). `PlayExtra_UpdatePanel` therefore
  renders the VOD side from `PlayExtraVod_Store`, never from the cell, and the channel logo is
  fetched from the users API because `Main_values.Main_selectedChannelLogo` holds the VOD duration in
  VOD mode.
- The panel control set for the combined mode is `ShowInVod` **and** `ShowInPP`
  (`Play_ControlsVodPP`); `Play_SetControlsVisibility` accepts an array for this.
- A VOD in the small window gets no chat. The VOD replay chat is a singleton bound to container 0 and
  the main player's position, so it only runs when the VOD is the big player.
- Multistream stays live only and refuses while a VOD is in the small window.
- `gettimePP` / `getsavedtimePP` expose slot 1's position so the small VOD keeps its place across a
  switch and a trip through the background. The bridge refreshes the live value every 500 ms, so a
  read right after a switch still reports the player that used to be there.
- **Both halves of the panel write the same elements.** A renderer that only writes a field when it
  has a value leaves the other half's content standing after a switch, which is how the live
  channel's logo ended up under the VOD. Every field is written unconditionally, with a placeholder
  when there is nothing to show.
- The logo fetch id lives **in** `PlayExtraVod_Store`. As a module level latch it outlived the store
  it belonged to, so replacing the VOD skipped the fetch forever and the panel kept the old image.
- The store takes its title, game, date and views from the **cell**, not from `ChannelVod_*`. Only
  `Main_OpenVodStart` fills those globals, so a VOD that reached the main player through a switch or
  a resume has them empty. The store keeps the game raw; `ChannelVod_game` is pre-formatted with
  `STR_STARTED + STR_PLAYING`.
- `Play_RefreshWatchingTime` drives both time lines and both of its `PlayVod.js` call sites were
  gated on `Play_isOn`, which is false whenever a VOD is the main player. They also need
  `PlayExtra_PicturePicture`, or the lines freeze holding the previous arrangement's text. The
  interval behind them only runs while the info panel is open, so `PlayExtra_UpdatePanel` repaints
  them too.
- Replacing the big window's source must not go through `PlayVod_PreshutdownStream`; it calls
  `stopVideo`, which resets every player. `PlayExtraVod_ReplaceVodMain` drops the VOD state and lets
  `Play_Start` hand slot 0 a new source, which is how the live only case already behaved.
- A stream that moves between the windows keeps its `watching_time`. Only a genuinely new stream
  restarts that counter.

## Branches

`RikuXan/SmartTwitchTV` is `origin` and the primary workspace. `upstream` is `fgl27/SmartTwitchTV`;
never push there. The Media3 fork is the same arrangement in `~/Code/github/RikuXan/media`.

The work that used to sit mixed together on `feat/low-latency-improvements-dev` was split into one
branch per feature and merged back into `main`, which is the default branch and what gets built and
deployed. `master` stays a pristine mirror of upstream. Branches stack where the code genuinely
depends on the branch below, so a stack merges bottom up.

App repo. Everything below is merged into `main` except the experiment:

```
docs/agents-notes               merged — this file
feat/settings-block-160p        merged — unrelated to low latency, rode along by accident
feat/presence                   merged — channel points, drops, watch streaks, minute watched
feat/pip-vod                    merged, complete — a VOD in the picture in picture player
feat/low-latency-core           merged — the controller, the cushion setting, the speed gate
 ├ exp/audio-pitch-follows-speed  NOT merged — evaluated alternative to time stretching, then reverted
 ├ feat/audio-signalsmith         merged — Signalsmith Stretch vendored, JNI bridge, renderer wiring
 └ feat/ll-player-readout         merged — speed and buffer target in the on-screen player info
    └ feat/ll-origin-cushion      merged — cushion scaled by measured origin RTT
       └ feat/ll-jitter-window    merged — jitter window arming, stall cushion hold
          └ feat/ll-speed-glide   merged — slew speed changes so the stretcher stays quiet
             └ feat/ll-diagnostics   merged — merges feat/audio-signalsmith, persisted logs
                └ feat/ltb-measurement  merged — broadcast delay from timed metadata, adaptive recovery
```

`feat/presence` gates `PresenceLog` on `BuildConfig.DEBUG` because it branches from `master`, where
the low latency diagnostics do not exist. `main` carries a follow-up commit routing it through
`TwitchDiagnosticLog` instead — redo that whenever presence is merged forward again.

One branch is still live, so `main` carries work in progress:

```
feat/adaptive-buffer-sizing     live — the unbuilt control half of feat/ltb-measurement
```

`feat/pip-vod` was finished and `main` was rebuilt on 2026-09-19 so the whole feature arrives in a
single merge; `git revert -m 1` on that merge backs out all of picture in picture. The pre-rebuild
tip is `archive/main-before-pip-rebuild-2026-09-19`.

### Landing a live branch as one merge

`main` is a mechanical replay: `master` plus one `--no-ff` merge per branch, in dependency order,
plus the presence fixup and the docs. Nothing on it is hand-written except those two, so it can be
rebuilt from the feature branches at any time and the result compared tree-for-tree against the old
tip.

So when `feat/pip-vod` and `feat/adaptive-buffer-sizing` are finished, do not merge the remainder on
top. Rebuild `main` from `master` with the finished branches in place and force push it. Each
feature then enters `main` through exactly one merge commit and `git revert -m 1 <merge>` backs the
whole thing out.

Rebuild once, after both are done, not once per feature.

Do **not** instead revert the partial merge and re-merge the same branch later: git treats those
commits as already merged and a later `git merge` silently brings in only the new ones. Reverting
the revert or rebasing the branch first works, but rebuilding is simpler and leaves no trap.

`feat/ltb-measurement` landed the measuring half of adaptive buffer sizing and none of the control
half: `TwitchBufferShadow` computes a recommendation that nothing reads, and the conservative-start
controller does not exist. See "Remaining limits of cushion adaptation" and "September 19
measurement and recovery state" below for exactly what is and is not established. That work
continues on `feat/adaptive-buffer-sizing`, branched from `main` rather than from
`feat/ltb-measurement` because `main` already contains all of it.

Media3 fork. `release` mirrors upstream and never moves; `main` is the default branch and has every
branch below merged into it except the experiment:

```
fix/twitch-emsg-timestamps      merged — prerequisite of feat/ltb-measurement
feat/delivery-diagnostics       merged — prerequisite of feat/ll-diagnostics
feat/twitch-prefetch            merged — EXT-X-TWITCH-PREFETCH parsing
 └ feat/hls-low-latency-target  merged — server clock live offset, target tuning
    └ feat/hls-origin-cushion   merged — cushion from the multivariant playlist
exp/audio-pitch-follows-speed   NOT merged — the app side reverted it, mainline would carry dead code
```

Every merge into `main` is a `--no-ff` merge commit, so any single feature can be reverted on its
own. The feature branches are kept after merging; delete one only once its work is certainly not
needed as a base again.

Never move `feat/low-latency-improvements` or `feat/twitch-prefetch-low-latency` — they are frozen
snapshots referenced from a public upstream issue. The `archive/*` branches and
`origin/wip/pip-vod-live` are the pre-split state, kept for reference.

## Full cycle

```bash
rsync -a --delete app/ apk/app/src/main/assets/app/   # never skip
# build, sign, deploy as above
```

A web-app-only change still needs the whole cycle: the assets are baked into the APK, so there is
no way to push JS to the device without rebuilding and reinstalling.

# The low-latency feature

This fork's main purpose. Goal: sit as close to the live edge as the stream's delivery allows,
without buffer-underrun stutters. Latency currently runs ~1.3–1.6 s live-to-broadcast on an EU
stream with a 0.25–0.5 s cushion, which is ahead of the browser on the same stream.

## Why the control target is the buffer, not the live offset

Media3's stock `LivePlaybackSpeedControl` steers toward a *live offset* target. That cannot see
delivery-side shortfalls: the offset can look fine while the buffer is about to run dry, and the
right offset differs per stream and per origin, so it needs hand-tuning for each.

`apk/app/src/main/java/com/fgl27/twitch/TwitchLivePlaybackSpeedControl.java` replaces it and steers
the **windowed minimum of the buffered duration** toward a cushion. The buffer running dry is what
actually stalls playback, and the windowed minimum sizes the cushion from the stream's own delivery
jitter before the first stall happens. `getTargetLiveOffsetUs()` deliberately returns `TIME_UNSET`
and `setTargetLiveOffsetOverrideUs` is a no-op.

## How the controller works

The first controller iteration was installed on the Shield on 2026-09-08 (initial PID 22460).
The release build and all 37 focused tests passed. It retains the history correction and
catch-up safety guard, with the original fractional speed adjustments and cadence. Replay notes
and model limits are in `.tmp/ll-control/assessment.md`.

The September 9 LTB discrepancy was traced to device-clock drift and corrected; see
"Device clock drift and LTB" below. The latest buffer-controller assessment is in
`.tmp/ll-control/assessment-2026-09-09/report.md`. It found that 48 of 51 stalls associated with
main-player cushion increases occurred after the stall allowance returned to zero. Two long
stalls had confirmed manifest HTTP 500 errors. An in-flight prefetch request alone is not evidence
of a server fault. The adaptive cushion build passed all 43 app tests and was installed/restarted on the Shield
on 2026-09-09 at 21:52 Berlin time (initial PID 17786). The implementation and model results are recorded in
`.tmp/ll-control/implementation/report.md`. Analytics events now resolve the owning player's
current slot after preview promotion; older traces retain creation-slot labels.

- **Window**: 10 buckets × 1000 ms. Each sample is normalized by adding accumulated playback
  advance relative to 1×; the current advance is subtracted when reading the minimum. Thus buffer
  rebuilt by slowing down, or consumed by catch-up, is reflected immediately in historical lows.
  A gap of over one second between control samples discards the window and re-arms without charging
  a stall; elapsed time while paused cannot reliably represent playback advance.
- **Error and gain**: `error = windowedMin − (cushion + stallExtra)`, deadband ±50 ms, proportional
  factor `0.1 per second of error`, clamped to the `LiveConfiguration` speed range. Updates are rate
  limited to one per 1500 ms.
- **Arming**: samples from the startup fill ramp are not delivery jitter and must never enter the
  window. The controller stays at 1.0× until the buffer stops setting new maxima for 2000 ms *and*
  is within 200 ms of its peak, or a 10 s deadline expires.
- **Warmup asymmetry**: until one full window of real data exists, slowing below 1.0× is forbidden
  (`minSpeed = 1f`) while shaving above it is allowed — the window's early lows are fill artifacts.
- **Stall response**: `TwitchBufferCushion` provisionally adds 250 ms (cap 1500 ms) and
  re-arms the window. One ordinary stall gets temporary recovery allowance: held for 120 s,
  then decayed at 25 ms/s. Another ordinary stall within 30 minutes promotes the raised allowance
  to a learned extra; subsequent failures raise it. `ctlConfirmed` tracks this evidence separately
  from the numeric reserve: an origin seed alone does not qualify, so its first ordinary failure
  receives only temporary recovery allowance. Failed probes restore the previous reserve and
  confirmation state. The effective extra is the maximum of learned
  and temporary recovery allowance. Source reset clears both.
- **Downward probes**: after five minutes of eligible settled playback, reduce learned extra by
  50 ms. A failure during a probe restores the previous learned level and doubles the wait,
  up to 30 minutes. Eligible playback needs a warm window, speed within 0.005 of 1x, and a window
  minimum within 50 ms of the effective target. Pauses do not count. Probes stop at zero extra,
  preserving the configured user floor. The origin estimate seeds this same adaptive reserve once
  per source; successful probes can remove it entirely.
- **Excluded failures**: confirmed timeout, DNS, connection/no-route errors and HTTP 401/403/429/5xx
  media or manifest failures during the stall or within five seconds before it exclude that stall
  from learning and roll back its provisional allowance on recovery. Request age alone, generic
  EOF and prefetch 404 are not proof of an unusual failure. Audio-only underruns do not notify
  the cushion policy. Recovery/error callbacks work independently of diagnostic logging.
- **Speed output**: retains the existing 1500 ms cadence, up-to-0.01 glide, and 0.005 snap threshold;
  intermediate fractional speeds remain available. Catch-up stops immediately if instantaneous
  buffer reaches the effective cushion. The audio processor remains active at 1× to preserve continuity.
  The user reports Signalsmith speed changes are inaudible, so fewer changes are not an optimization
  goal by themselves. Evaluate LTB, stalls and recovery/settling time instead.

## Where the cushion comes from

The origin-as-adaptive-seed build was installed September 11 at about 00:12 Berlin time
(initial PID 24855). All 51 app tests passed. Device records confirmed a zero user floor and
a 473 ms origin estimate held entirely in adaptive reserve. Evidence: `.tmp/origin-seed/report.md`.

`HlsMediaSource.updateLiveConfiguration` (in the Media3 fork) computes, for `LowLatency == 1`:

```
targetOffsetMs = userTargetMs
adaptiveReserveMs initially = originCushionExtraMs()
maxOffsetMs    = targetOffsetMs + 1500
minPlaybackSpeed = 0.97   maxPlaybackSpeed = 1.05
```

`userTargetMs` is the `ll_target` setting (`0s, 0.25s, 0.5s, 0.75s, 1s, 1.5s, 2s, 3s`, default
`1s`). The `maxOffsetMs` cap exists because an uncapped target let latency ratchet upward over time.

`Tools.OriginCushion` resolves a startup estimate from the stream's origin:
`Tools.ProbeIngestRtts()` fetches Twitch's ingest directory at startup and TCP-connects to each
cluster twice, keeping the worst RTT per origin family. `Tools.OriginCushionExtraMs` then returns
`min(1000, round(2.5 × rtt))`, or `-1` while the probe has not answered (the fork retries on the
next playlist refresh rather than caching a wrong value), falling back to the worst known family for
an unseen origin. The origin comes from `ORIGIN="…"` in the multivariant playlist that was actually
loaded — reading it from a stale playlist once left a Tokyo stream running for two hours on an
EU-sized cushion.

The earlier notes cite a fit of roughly `131 ms + 2.24 × RTT`; the raw fit has not been
revalidated, and `2.5 × RTT` alone does not cover its intercept at ordinary RTTs. The factor
remains an initial heuristic, not a permanent minimum. The resolver seeds a source-owned
`TwitchBufferCushion` and returns zero additive offset to Media3 once resolved. Negative values
still mean unresolved and trigger retry. A fresh resolver/policy is created for each media source;
attaching it to the speed controller preserves an early seed, and old-source resolution cannot
seed the new source. Repeated seeding cannot undo a successful probe. A late estimate is merged
using the maximum of the estimate and existing learned reserve, preserving prior evidence.

Each player slot owns its own controller and cushion resolver, and both must travel with the player
in `ReUsePlayer` — a promotion that swapped the player but not the controller once left the on-screen
readout showing a dead controller's speed.

## Audio during catch-up

Sonic's time stretching crackles at the splice points when speed changes. Pitch-follows-speed was
tried and rejected — audible and annoying on music. Instead the fork uses **Signalsmith Stretch**
through JNI (`apk/app/src/main/cpp/`, MIT, vendored under `third_party/`), wired up by
`audio/SignalsmithAudioProcessor.java` and friends: constant pitch, no crackle, ~5 % of one core,
100 ms block / 40 ms interval, and it logs `audio-stretcher=signalsmith` or `=sonic-fallback` at
startup.

`DefaultAudioSink` drains and flushes on every playback-parameter change, which reset the stretcher
and produced a microstutter per speed step. The processor distinguishes a parameter-change flush
(preceded by `queueEndOfStream`) from a seek flush, keeps native state across the former, and stays
active at 1.00×.

## On-screen readout

The player info line shows a quadruplet: `1.54 | 1.32+0.15 | 1.00x` — measured live-to-broadcast,
then the effective cushion target plus current jitter, then the applied speed. Rendered in
`Play.js` from `getVideoStatus` elements 10 (adjusted speed), 11 (effective target =
`targetOffsetMs + stallExtra`) and 12 (jitter EMA of `buffer − windowedMin`).

LTB comes from playback-timed Twitch ID3 `segmentmetadata` (`cmd=ld_lat_data`): server-corrected
current time minus `transc_r`, cached until the next accepted cue. `TwitchBroadcastLatency` anchors
the server clock per fresh source using either `EXT-X-TWITCH-INFO:SERVER-TIME` or
`EXT-X-SESSION-DATA` with `DATA-ID="SERVER-TIME"`. `TwitchNetworkClock` refreshes Media3's SNTP
reference every 30 seconds while live playback is active. LTB adds only the change in that
network-to-device offset since its initial reference, preserving Twitch's original clock alignment
while correcting subsequent device drift. Failed refreshes retain the last successful reference.
It rejects backwards receive/send timestamps.
There is no buffer term or live-edge estimate in this readout. An unavailable or negative sample
renders a dash. The older timeline-derived calculation is retained for chat synchronization only.

## Device clock drift and LTB

The September 9 diagnostic trace demonstrated clock drift, not early cue delivery: 713 cues over
4.85 hours showed LTB relative to buffer falling by about 156 ms/hour. Independent host/Shield
clock comparisons agreed; playback cues were delivered within about 10 ms of their scheduled
position, and the buffer was backed by audio/video samples. A single startup clock anchor let
roughly 720 ms of latency display as 40 ms after several hours. The network-clock correction
above addresses this drift without clamping latency to the buffer. Four additional regression
tests cover five-hour drift in both directions, preservation of Twitch's original clock offset and
late network-clock initialization. Media playlists' `playlist-creation` / `X-SERVER-TIME` values
can remain unchanged for a whole session, and master-playlist playback tokens expire after about
20 minutes; neither is a dependable ongoing clock refresh source. The final network-clock build
was installed September 9 at about 20:57 (PID 14170). All 30 app tests pass. Live verification
confirmed successful initial SNTP synchronization and subsequent 30-second refreshes correcting
+2 ms then +1 ms; settled readings were about 0.49–0.50 s LTB with 0.40–0.44 s buffered.
The runtime capture is `.tmp/ltb-browser/network-clock-runtime.log`.


## Instrumentation

`LL_DIAG` in `PlayerActivity.java` is `true` and gates all `TwitchLL` logging **in release builds
too**, deliberately. The per-tick heartbeat is the main data source:

```
raw= shown= edgeDist= buf= pp= pos= ctlMin= ctlStallX= ctlLearned= ctlConfirmed= ctlProbe= ctlProbeWaitMs= ctlSpeed= target= lowLat= targetMs= origin= extraMs= speedAdj=
```

`ctlMin` is the windowed minimum, `ctlStallX` the current stall extra, `ctlSpeed` the controller's
output, `ctlLearned` the adaptive reserve in ms (initially the origin seed, subsequently learned/probed), `ctlProbe` the active probe state, and
`ctlProbeWaitMs` the required eligible time between probes. `target` is the Media3 base target
(user floor in this mode), `targetMs` the user setting, and `target + ctlStallX` the effective
cushion. `extraMs` records the initial origin estimate and must not be added again. Event lines carry a `p<slot>`
prefix:

- `STALL pos= buf= inFlight= loadAgeMs= loadUri= sinceMediaDoneMs= sinceManifestMs=` — enough to
  correlate a stall with media request progress and playlist completion. No media request in flight
  can also mean delayed playlist discovery; it does not establish a local renderer fault.
- `STALL-RECOVERED durMs= ignoredForLearning= learnedMs=`
- `DELIVERY-END deliveryId= request= media= outcome= durationMs= openMs= firstByteMs= bytes= maxByteGapMs= maxReadWaitMs=`
- `LOAD-RETRY`, `LOAD-COMPLETED (SLOW-LOAD >4s)`, `LOAD-CANCELED`, `LOAD-ERROR`
- `AUDIO-UNDERRUN bufMs= sinceFeedMs=` — the audio path starving, which is *not* a buffer
  underrun and has a different cause.
- `DROPPED-FRAMES count= elapsedMs=`

### Delivery progress diagnostics

`TwitchProgressDataSource` wraps live HLS reads and feeds a per-source `TwitchDeliveryProgress`.
Heartbeat and stall snapshots include `deliveryId`, `mediaRequests`, `mediaBytes` (sum on active
requests), `mediaNoByteMs`, `mediaReadWaitMs` and `mediaOpenWaitMs` (maxima across active media
requests; -1 means unavailable). `.ts`, `.m4s` and `.mp4` paths count as media; other paths still
emit completion records. No URL/token is included in the added completion record.

`DELIVERY-END` records first-byte/open delay and maximum blocked-read duration, plus gaps between
positive reads. The latter can include consumer inactivity. A blocked upstream read is evidence
of a data-source wait, not proof of a server fault. These are not decoder-ready A/V buffer
measurements. The new metrics do not change exclusion rules; collect observations first.
The implementation/test/deployment notes are `.tmp/tentative-reserve/report.md`.

## Findings that constrain any redesign

- **Attribute stalls from evidence.** Aged requests indicate delivery waits but do not locate
  the cause in Twitch, the network, or the client. Renderer-readiness and recovery-fill events
  also contribute to visible interruptions. An earlier device issue involved the Shield's Dolby MS12 path: with HDMI
  audio output set to Dolby, an `AUDIO_FORMAT_E_AC3` direct-output thread burned ~19 % of a core and
  produced regular audio underruns. Switching the Shield's HDMI output to PCM took HAL CPU to zero
  and audio underruns to zero. Any latency experiment must control for that setting.
- **Approaches already tried and rejected**: instantaneous buffer as the control variable (too
  noisy); a windowed *average* instead of the minimum (hides the drawdowns that actually stall);
  magnitude-based outlier filtering of the jitter window (fragile — startup and post-stall ramps are
  excluded by causal markers instead); pitch modulation for crackle-free catch-up.
- **PiP/multi**: each player slot runs its own controller; two 1080p streams on this device can
  starve each other.

## Remaining limits of cushion adaptation

The adaptive policy searches the whole reserve, including its initial origin estimate, above the
configured user floor. It does not probe below the user setting. Its five-minute initial probe interval,
50 ms step, and 30-minute failure memory are initial experimental choices. Unit tests and synthetic
closed-loop simulations cover learning, exclusion, rollback and eventual reduction after delivery
improves; these do not establish real-device stutter-free operation or an optimal latency.


## September 19 measurement and recovery state

The source changes are grouped in commits in both repositories. The app requires the companion
Media3 metadata and timing diagnostic changes; building against an unpatched Media3 checkout is
unsupported. The latest release build and all 71 app tests passed. Four standalone codec timing
history tests also passed, and the installed Shield process emitted valid audio/video timing data.

`TwitchLoadControl` uses a low-latency recovery threshold of
`min(previous recovery threshold, max(500 ms, effective cushion + 150 ms))`.
A second stall within 30 seconds or an explicit unusual delivery error restores the previous
threshold for 60 seconds. Source reset clears this fallback. Startup and other modes retain their
existing behavior. `RECOVERY-FILL` records the selected threshold and current buffer.

`TwitchBufferShadow` only observes: a roughly two-minute speed-corrected buffer history, a
one-minute minimum observation period, and an experimental 150 ms margin. It does not control
playback. `shadowReady` must be checked before interpreting its recommendation. Aggregate HLS
buffer duration can exceed the available runway of one track, so the shadow recommendations are
not yet validated as safe reductions. The proposed conservative-start controller is not implemented.

`RENDERER-READY` identifies audio/video readiness transitions. `VIDEO-READINESS` adds source/output
readiness and codec buffer counts. Opt-in `TRACK-TIMING` diagnostics report each HLS track's queued
sample endpoint, last decoder input/output timestamps relative to renderer position, sample ages,
and matched input-to-output residence time. They use renderer IDs and monotonic timestamps for
correlation; epochs invalidate timing history across stream replacement and codec flushes.
Periodic timing records are sampled at 500 ms, with readiness changes sampled no faster than
100 ms. They do not capture every brief minimum. Missing timestamps use Media3 `TIME_UNSET`;
missing ages/residence use -1. Queued endpoints include already-read samples, and decoder output
timestamps are not a complete measure of audio sink or display-ready coverage. Codec residence
includes scheduling and output dequeue timing, not only hardware decode time.

Local evidence is in `.tmp/track-timing/report.md` and `.tmp/adaptive-recovery/`.
Next work is validating these track-level signals against actual stalls before using them for
conservative-start sizing and staged reductions. Do not substitute raw sample endpoints for
proven uninterrupted playback duration.
