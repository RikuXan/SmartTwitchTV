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
| `.tmp/logs/twitchll-live.log` | Continuous `TwitchLL` capture. The running `stv-logcat` container mounts this path **from the `fgl27` clone**, not from here — `docker inspect stv-logcat` names the source it actually writes to. |

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

Thirteen upstream files differ from `4d0e670`, across eight commits, in three groups:

| File | Change |
| --- | --- |
| `libraries/exoplayer_hls/.../hls/playlist/HlsPlaylistParser.java` | Parses `#EXT-X-TWITCH-PREFETCH` (the unadvertised next segments Twitch exposes with `fast_bread=true`) and `#EXT-X-TWITCH-INFO`, and retains all `#EXT` tags on the parsed playlist so consumers can read them. |
| `libraries/exoplayer_hls/.../hls/playlist/DefaultHlsPlaylistParserFactory.java` | Factory wiring for the parser above. |
| `libraries/exoplayer_hls/.../hls/HlsMediaSource.java` | The low-latency surface: `Factory.setLowLatencyTargetMs`, the `OriginCushionProvider` interface plus `Factory.setOriginCushionProvider`, `REGEX_TWITCH_ORIGIN` to read `ORIGIN="…"` off the loaded multivariant playlist, `originCushionExtraMs()` with retry-while-unresolved, and `updateLiveConfiguration` computing target/max offset and the speed bounds. |
| `libraries/extractor/.../metadata/emsg/EventMessage.java`, `.../mp4/FragmentedMp4Extractor.java` | Keeps the presentation timestamp on Twitch's timed metadata instead of discarding it, which is what `TwitchBroadcastLatency` measures broadcast delay from. Covered by `FragmentedMp4EmsgTimestampTest`. |
| `libraries/exoplayer/.../mediacodec/CodecTimingHistory.java` (new), `.../mediacodec/MediaCodecRenderer.java`, `.../source/SampleStream.java`, `.../video/MediaCodecVideoRenderer.java`, `libraries/exoplayer_hls/.../HlsSampleStream.java`, `.../HlsSampleStreamWrapper.java` | Per-track delivery and decoder timing diagnostics — the queued sample endpoint, decoder input/output timestamps and codec residence that the `TRACK-TIMING` log lines report. Covered by `CodecTimingHistoryTest`. |

`DefaultAudioSink.java`'s `pitchFollowsSpeed` toggle is **not** in `main`. The pitch-modulation
approach to catch-up audio was rejected as audible, and the change lives unmerged on
`exp/audio-pitch-follows-speed` in the fork.

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

The device ring buffer holds only a few seconds of `TwitchLL`, which is far too little to read
back a reproduction. Always read the captured file, never `adb logcat -d`.

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
loopback port to the Shield; run it from the app repository with `/usr/bin/python3` and keep it
running. Homebrew's `python3` runs as its own `Python.app`, which has no macOS Local Network
permission: its connections to the Shield fail with `No route to host`, and the container's
transport stays `offline`. The relay records its port and PID in `.tmp/adb/tv-relay.port` and
`.tmp/adb/tv-relay.pid`. Connect the existing
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
- `Play_CheckLiveThumb` decides what a window holds from `PlayExtraVod_InPP` plus the cell sitting in
  `PlayExtra_data`, and a VOD cell carries its channel at index 14 exactly like a live one. Anything
  that drops the VOD state has to run **after** that check, or the small window's VOD reads as a live
  stream of its channel and that channel's own live stream is refused as already playing.

## Chat in picture in picture

Both live chats of a pair run the whole time: chat 0 for the main stream, chat 1 for the small one,
including while the video is fullscreen and `chat_container1` is hidden. A switch never restarts them.
`ChatLive_Switch` swaps every per chat array, the lines in `chat_box0`/`chat_box1` and the chatters
label, so each chat keeps its history and socket and only changes slot. The send socket belongs to
slot 0 and is restarted by the switch.

Rules that keep this working:

- Anything that runs later (socket handlers, timers, request callbacks, delayed and paused lines)
  finds its chat with `ChatLive_Slot(id)` and must not trust a `chat_number` it captured, because a
  switch may have moved the chat. A new per chat array has to be added to the list in
  `ChatLive_Switch`, or it stays with the window instead of following the stream.
- `Chat_Id` values must be unique across the two slots, which `ChatLive_SetOptions` guarantees.
- In a vod and live pair the vod's replay chat owns slot 0 and the live chat keeps slot 1. The vod
  chat's `Chat_Clear` runs on every start and seek, so it leaves slot 1 alone while
  `PlayExtra_PicturePicture` is set and `PlayExtraVod_InPP` is false. `PlayExtraVod_SwitchToLiveMain`
  therefore has to clear the vod chat before it flips `PlayExtraVod_InPP`.
- A vod in the small window still gets no chat, and multistream is unchanged: it still reinitialises
  chat 0 when its main stream changes.

## Presence

`app/specific/Presence.js` makes Twitch credit the account for what the app plays, which it
otherwise does not: channel points, watch streaks, drop progress and watch time all depend on the
web player reporting itself. Nothing here affects playback; it only reports.

A 60 s tick (`Presence_Tick`) drives everything, keyed by channel id so picture in picture and
multistream each report separately. Per tick it reconciles the watched set against
`Presence_WatchedChannels()` and drops the per-channel state of anything no longer playing.

Two endpoints are involved:

- `gql.twitch.tv/gql` for `ChannelPage_SetSessionStatus` (presence), `ChannelPointsContext` and
  `ClaimCommunityPoints` (balance plus bonus chests, every 120 s), `DropChannelCampaignsProgress`
  with `Inventory` and `DropsPage_ClaimDropRewards` (drops, every 300 s), and
  `updateUserViewedVideo` for VOD progress.
- `spade.twitch.tv/track` for the `minute-watched` event, which is what actually credits watch
  time and streaks.

Things that are easy to break:

- The spade event must carry `game` and `game_id`. Without the category it still credits points but
  **no drop progress**, because campaigns are keyed by game. Spade also only reads the event out of
  a batch array, never a bare object.
- `broadcast_id` and the category have to stay current, so `Presence_Info` refreshes them rather
  than caching once per channel.
- The inventory sweep runs whatever is playing, and an empty inventory is a valid answer, not a
  failure to retry.
- In a VOD and live pair only the live window is a watched channel, and `PlayExtraVod_Side()`
  decides which one that is. A VOD cell carries its creator at index 14 exactly like a live one,
  and `Play_data` still holds the replaced stream while a VOD is the big player, so neither can be
  read blindly. The VOD reports through `updateUserViewedVideo` from whichever window holds it,
  with its position from `gettime` / `gettimePP`, never from `Play_ShowVideoStatus`, which only
  runs while the status overlay or the VOD panel is open.

`OSInterface_PresenceLog` sends every ping to `TwitchDiagnosticLog`, so
`adb logcat -s TwitchLL | grep presence` shows the whole loop. That gating differs by branch — see
the note under Branches.

## Other app changes

`feat/settings-block-160p` adds 160p to the resolution block list in `app/specific/Settings.js`.
It is unrelated to everything else and rode along on the original mixed branch by accident.

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
 ├ fix/pip-vod-chat-resume        merged — vod chat restarts after pause and seek when the vod came from the pair
 └ fix/pip-vod-open-live-icon     merged — the vod's open live control stays off the live panel after a swap
fix/vod-chat-end-of-chat        merged — vod chat retries failed loads and keeps the end-of-chat page
fix/pip-chat-keep-both          merged — both live chats survive a picture in picture switch
fix/chat-recent-messages-bare-tags  merged — live chat backfill from recent-messages.robotty.de renders again
fix/presence-pip-vod            merged — presence in a vod and live pair; replay after feat/presence and feat/pip-vod
feat/low-latency-core           merged — the controller, the cushion setting, the speed gate
 ├ exp/audio-pitch-follows-speed  NOT merged — evaluated alternative to time stretching, then reverted
 ├ feat/audio-signalsmith         merged — Signalsmith Stretch vendored, JNI bridge, renderer wiring
 └ feat/ll-player-readout         merged — speed and buffer target in the on-screen player info
    └ feat/ll-origin-cushion      merged — cushion scaled by measured origin RTT
       └ feat/ll-jitter-window    merged — jitter window arming, stall cushion hold
          └ feat/ll-speed-glide   merged — slew speed changes so the stretcher stays quiet
             └ feat/ll-diagnostics   merged — merges feat/audio-signalsmith, persisted logs
                └ feat/ltb-measurement  merged — broadcast delay from timed metadata, adaptive recovery
feat/adaptive-buffer-sizing     merged — measured cushion sizing and the video output-gap grace; needs the media merges below
```

`feat/presence` gates `PresenceLog` on `BuildConfig.DEBUG` because it branches from `master`, where
the low latency diagnostics do not exist. `main` carries a follow-up commit routing it through
`TwitchDiagnosticLog` instead — redo that whenever presence is merged forward again.

No branch is live: `main` carries no work in progress.

`feat/pip-vod` was finished and `main` was rebuilt on 2026-09-19 so the whole feature arrives in a
single merge; `git revert -m 1` on that merge backs out all of picture in picture. The pre-rebuild
tip is `archive/main-before-pip-rebuild-2026-09-19`. `fix/pip-vod-chat-resume` and
`fix/pip-vod-open-live-icon` came later and are stacked on it in their own merges, so revert those
merges first.

### Landing a branch as one merge

`main` is a mechanical replay: `master` plus one `--no-ff` merge per branch, in dependency order,
plus the presence fixup and the docs. Nothing on it is hand-written except those two, so it can be
rebuilt from the feature branches at any time and the result compared tree-for-tree against the old
tip.

A branch that never reached `main` lands as one `--no-ff` merge on top. A branch that was merged
while unfinished must not get its remainder merged on top: rebuild `main` from `master` with the
finished branch in place and force push it, so the feature still enters `main` through exactly one
merge commit and `git revert -m 1 <merge>` backs the whole thing out.

Do **not** instead revert the partial merge and re-merge the same branch later: git treats those
commits as already merged and a later `git merge` silently brings in only the new ones. Reverting
the revert or rebasing the branch first works, but rebuilding is simpler and leaves no trap.

`feat/ltb-measurement` landed the measuring half of adaptive buffer sizing and none of the control
half. `feat/adaptive-buffer-sizing` adds the control half: `TwitchBufferLows` replaces
`TwitchBufferShadow`, and measured trims replace the step-down probes (see "How the controller
works"). It never reached `main` before it was finished, so it was rebased onto `main` and landed
as one merge on 2026-11-03.

Media3 fork. `release` mirrors upstream and never moves; `main` is the default branch and has every
branch below merged into it except the experiment:

```
fix/twitch-emsg-timestamps      merged — prerequisite of feat/ltb-measurement
feat/delivery-diagnostics       merged — prerequisite of feat/ll-diagnostics
feat/twitch-prefetch            merged — EXT-X-TWITCH-PREFETCH parsing
 └ feat/hls-low-latency-target  merged — server clock live offset, target tuning
    └ feat/hls-origin-cushion   merged — cushion from the multivariant playlist
exp/audio-pitch-follows-speed   NOT merged — the app side reverted it, mainline would carry dead code
feat/renderer-unread-duration   merged — prerequisite of feat/adaptive-buffer-sizing
 └ feat/video-output-gap-grace  merged — prerequisite of feat/adaptive-buffer-sizing
```

`feat/renderer-unread-duration` adds `MediaCodecRenderer.getUnreadQueuedDurationUs()`.
`feat/video-output-gap-grace` stacks on it and adds
`MediaCodecVideoRenderer.setOutputGapGraceMs(long)`. The app calls both, so app `main` only builds
against a media checkout that contains both merges.

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
without buffer-underrun stutters during normal segment delivery. Stalls caused by abnormal delivery,
such as hard server errors or a pause inside a segment's byte stream, are acceptable. Buffer
sizing must ignore them rather than grow the cushion for them. Latency currently runs ~1.3–1.6 s live-to-broadcast on an EU
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
- **Start**: each source starts with a 200 ms adaptive reserve above the user floor
  (`TwitchBufferCushion.START_RESERVE_US`). The origin estimate can only raise it, once per source.
- **Measured sizing**: `TwitchBufferLows` takes the speed-corrected minimum of the aggregate
  buffer for each 10 s bucket. A bucket counts as covered after 5 s of samples. The level is the
  median low of the last 6 covered buckets, with at least 3 required. Each bucket's depth is that
  level minus its own low, and the depths are kept for 20 minutes. The recommended cushion is
  `150 ms + deepest depth`, the target that would have kept the worst normal-delivery dip at the
  150 ms margin while the controller holds the typical 10 s low at the target. Measuring against
  the level just before a dip keeps a slow drift of the buffer from deepening old dips. Raises
  apply as soon as one depth is known, including the bucket in progress. A trim needs 18 covered
  depths (3 minutes), 3 minutes since the last change and a 25 ms difference, and it goes
  straight to the recommendation. The user floor is never undercut.
- **Stall response**: a stall provisionally adds 250 ms (cap 1500 ms) and re-arms the window.
  The measured depths survive it; only the level restarts, and a new source clears everything.
  After a normal-delivery stall the raised reserve is kept, and trims are held for 20 minutes.
- **Abnormal delivery**: `TwitchDeliveryProgress` learns each stream's normal delivery from its
  last 20 completed media requests that lasted at least 1 s. The pause limit is
  `max(250 ms, 3.5 × median of those requests' longest pause)`, and 250 ms until 5 requests have
  completed. With fewer, one slow startup request put a US stream's limit at 1491 ms. That is about 560 ms on US origins, whose requests normally pause for about
  280 or 420 ms, and 250 ms on EU origins. A media request that goes longer than the pause limit
  without bytes, including the first-byte wait, is abnormal. So is a confirmed trickle.
  - **Flagging a trickle**: while it is the only media request in flight, the request delivers
    less than 30% of the stream's usual byte rate over a short window, or less than 50% over a long
    one. The usual rate is the median of the last 5 requests. The windows are 500 ms and 1 s
    scaled by pause limit / 250 ms (at most 3×) and checked in 50 ms slots.
  - **Confirming it**: a low-rate window counts only once the request catches up to 90% of the
    usual rate since the window started, or finishes later than the stream's median request
    duration plus the pause limit. A quiet, low-bitrate scene does neither.
  - **Consequences**: confirmed timeout, DNS, connection/no-route errors and HTTP
    401/403/429/5xx failures count as well. A stall during an abnormal delivery or within 15 s
    after one keeps no bump. Buffer lows from the abnormal delivery's start until 15 s after its
    end are not measured. Samples are committed two long windows plus 150 ms late
    (`detectionDelayMs()` + 100 ms, 2150 ms at the base scale) so that the undetected onset and
    the confirmation delay are covered.
  - **Not abnormal**: request age alone, generic EOF and prefetch 404. Delayed playlist responses
    with no media request in flight are not detected yet.
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
reference every 30 seconds while any player holds a live stream. LTB adds only the change in that
network-to-device offset since its initial reference, preserving Twitch's original clock alignment
while correcting subsequent device drift. An offset older than 60 s counts as unavailable. A
source anchored without a current offset takes its reference from the first refresh after the
anchor. While no current offset is available, LTB keeps the last drift it applied.
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

### Stale reference after idle (fixed 2026-11-08)

Until `fix/ltb-stale-network-clock`, the offset never aged. Refreshes stopped whenever no live
stream played, and the first live source after a long pause anchored on the offset from the end of
the last session. Its first refresh then counted all the drift since that session, about
160 ms per idle hour, as broadcast delay. The error stayed for the whole source. Reloading the
stream re-anchored it on a current offset and cleared it. Example: November 8, 15:49–22:14 on
`eun13`, after 14 h idle. LTB read buffer + 2.35 s while the stream was in sync with a browser tab
showing 1.1 s. The first refresh had logged `changeMs=2229`. Every bad source from October 31 to
November 8 began after such a pause, and the LTB excess matched the first refresh's `changeMs`:
2334 (November 1), 1264 (November 2), 2028 (November 7) and 82 after a 30-minute pause
(November 8, 01:18). `LTB-CUE` lines now log `refOffsetMs` and `driftMs`. `driftMs` stays near zero
for a source's first minutes.


## Instrumentation

`LL_DIAG` in `PlayerActivity.java` is `true` and gates all `TwitchLL` logging **in release builds
too**, deliberately. The per-tick heartbeat is the main data source:

```
raw= shown= edgeDist= buf= pp= pos= ctlMin= ctlUnreadMin= unreadLow= ctlStallX= ctlLearned= lowsBuckets= lowsLevelMs= lowsDepthMs= lowsTargetMs= ctlTrimHoldMs= abnormalAgoMs= ctlSpeed= target= lowLat= targetMs= origin= extraMs= speedAdj=
```

`ctlMin` is the windowed minimum, `ctlUnreadMin` the same speed-corrected 10 s minimum of the
unread video (see "September 24 stall point" below), `unreadLow` the raw unread-video minimum
since the previous heartbeat, `ctlStallX` the current stall extra, `ctlSpeed` the controller's
output, and `ctlLearned` the adaptive reserve in ms. `lowsBuckets` counts the covered 10 s
buckets with a measured depth. `lowsLevelMs` is the current level at the current speed trajectory,
-1 while fewer than 3 buckets back it. `lowsDepthMs` is the deepest depth in the last 20 minutes
and `lowsTargetMs` the recommended cushion, both -1 until a depth is known.
`ctlTrimHoldMs` is the time left before trims resume after a normal-delivery stall.
`abnormalAgoMs` is the age of the latest abnormal delivery, byte gap or trickle, 0 while one is
ongoing and -1 if none has happened. `target` is the Media3 base target
(user floor in this mode), `targetMs` the user setting, and `target + ctlStallX` the effective
cushion. `extraMs` records the initial origin estimate and must not be added again. Event lines carry a `p<slot>`
prefix:

- `STALL pos= buf= inFlight= loadAgeMs= loadUri= sinceMediaDoneMs= sinceManifestMs=` — enough to
  correlate a stall with media request progress and playlist completion. No media request in flight
  can also mean delayed playlist discovery; it does not establish a local renderer fault.
- `STALL-RECOVERED durMs= ignoredForLearning= learnedMs=`
- `DELIVERY-END deliveryId= request= media= outcome= durationMs= openMs= firstByteMs= bytes= maxByteGapMs= maxReadWaitMs= lowRateMs= slowMs=`
  — `lowRateMs` is how long the request ran below the trickle thresholds, `slowMs` how much of
  that was confirmed as a trickle.
- `LOAD-RETRY`, `LOAD-COMPLETED (SLOW-LOAD >4s)`, `LOAD-CANCELED`, `LOAD-ERROR`
- `AUDIO-UNDERRUN bufMs= sinceFeedMs=` — the audio path starving, which is *not* a buffer
  underrun and has a different cause.
- `DROPPED-FRAMES count= elapsedMs=`
- `VIDEO-OUTPUT-GRACE rendererId= durationMs= outcome=resumed|expired sourceReady= outputAvailable= buffersInCodec=`
  — one record per gap the video readiness grace covered. `expired` means it did not bridge the
  gap and a stall followed.

### Delivery progress diagnostics

`TwitchProgressDataSource` wraps live HLS reads and feeds a per-source `TwitchDeliveryProgress`.
Heartbeat and stall snapshots include `deliveryId`, `mediaRequests`, `mediaBytes` (sum on active
requests), `mediaNoByteMs`, `mediaReadWaitMs`, `mediaOpenWaitMs` and `mediaLowRateMs` (maxima
across active media requests; -1 means unavailable; `mediaLowRateMs` is the age of an ongoing,
not yet confirmed low-rate stretch), and `pauseLimitMs`, the stream's current pause limit. `.ts`, `.m4s` and `.mp4` paths count as media; other paths still
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

## Measured sizing results

The step 2 build ran on September 26 with the video output-gap grace. It covered 6.1 h of live
`eun11` playback: 15:45–18:31, 18:32–18:57 and 20:27–23:55, because the Mac capture missed
18:57–20:27. The effective cushion had a median of 273 ms, the buffer 387 ms, and the hourly
median LTB was 440–505 ms. `lowsTargetMs` had a median of 270 ms (p10 199, p90 330), so the
250 ms `ll_target` floor capped the cushion about a quarter of the time. Rare deep dips raised
it by 25–100 ms, and about 20 minutes later, once the dip left the window, it trimmed back.
All nine stalls with byte gaps of 338–531 ms were ignored for sizing. Two stalls had normal
delivery. At 21:33 the cushion was 283 ms, two minutes after an abnormal stall had reset the
lows. At 23:54 3.4 s were still buffered right before a channel switch, so it was not a
delivery stall. For comparison, the grace build with probes (September 24 evening) had a
median cushion of 350 ms, a buffer of 465 ms, an LTB of 534 ms and 0.2 normal-delivery stalls
per hour. The build without the grace had 473 ms, 583 ms, 656 ms and 1.3 per hour.

From 15:45 to 18:23 the LTB read about 2.3 s while the buffer (about 390 ms), `raw` (about
1.17 s) and `edgeDist` (about 3.1 s) matched later sessions of the same channel. Reloading the
stream brought LTB back to about 0.5 s. The extra 1.8 s was a misreading, not real latency: it was
the stale clock reference after idle described in "Stale reference after idle" (the first refresh
logged `changeMs=1875`).

### With `ll_target` at 0

`ll_target` was set to 0 s on September 27 at 00:22 (same build, PID 8381). The evaluation uses
the single-stream `eun12` evening, 15:45–22:32 without the 16:37–17:06 picture in picture
session: 6.3 h. The median effective cushion was 390 ms, the buffer 526 ms and the LTB 616 ms.
`lowsTargetMs` had a median of 364 ms (p10 267, p90 456) and was below 250 ms only 7.7% of the
time, so dropping the floor changed little. The day's delivery made the difference.

Five stalls had byte gaps and were ignored. Six passed as normal delivery, about one per hour:

- 16:03, 17:14, 17:44 and 19:20 were trickles. The media request's bytes fell to 118–257 KB per
  500 ms heartbeat, against a median of about 510 KB and a 1st percentile of 273 KB. Media stopped
  arriving and the buffer fell 350–470 ms within about a second. No single pause exceeded
  250 ms, so `ABNORMAL_BYTE_GAP_MS` did not flag them.
- 18:48 and 20:44 happened at a segment boundary. The next segment's first frames arrived
  350–450 ms after the previous segment completed; at 18:48 the first byte took 127 ms (normally
  30–55). The 20:44 stall came two minutes after a trim to 214 ms. That trim followed the
  abnormal stall at 20:39, which had cleared the lows while they recommended 407 ms.

Buffer drops of at least 200 ms within one second during trickling bytes happened 3.5 times per
hour on this evening, and 0.3–1.3 times per hour on September 26's `eun11`.

The recommendation also creeps upward without new dips. At 1.0× the buffer drifts upward inside
the controller's ±50 ms deadband. The window's median follows the drift, but the worst low keeps
the level it had when it happened, so median − worst grows. From 21:36 to 22:04 the cushion rose
from 425 to 501 ms in 25 ms steps with no deeper dip. At 22:05 the 21:35 dip left the window and the
cushion trimmed to 337 ms. Of the day's 1333 ms of raises, 413 ms came from this creep.

### Local depths and trickle detection

Installed September 27 at 23:05 (PID 12031); four app runs through September 28 gave 2.2 h of
live playback. On `eun12` (1.2 h) the median LTB was 458 ms, the buffer 416 ms and the cushion
295 ms, with no normal-delivery stall. All three stalls there were ignored correctly: two came
during flagged trickles, and one was a 3.7 s stall behind a playlist request that hung for 6 s
before failing with an HTTP error. The cushion no longer crept. It held 393 ms for 20 minutes
after a 243 ms dip, then trimmed to 295 ms, and the ignored stalls did not reset it. Of five
trickle flags, three matched real buffer drops of 530–640 ms.

The fixed thresholds do not fit other origins. On US origins (`usw22`, `usw24`, `use23`), a
media request normally pauses for about 280 or 420 ms, two or three round trips of about 140 ms,
and never longer than 447 ms in about 1550 requests. 8–9% of their requests exceed the 250 ms
byte gap, which predates this build, and the trickle rule flags another 4–6%. On `euc11`, which has 4 s segments,
the trickle rule flagged 6% of requests with little buffer movement. On those streams an
abnormal flag arrives every 20–30 s. Together with the 15 s exclusion, it keeps the measurement
off most of the time and ignores every stall, so the cushion stays at the origin estimate. A
relative limit of 1.5 × the 95th percentile of the last 50 requests only halves the US flags,
because the 420 ms pauses make up about 4% of requests.

### Pauses come from upstream, and their rate varies by day

A September 29 experiment (`exp/shadow-segment-download`, abandoned) downloaded every segment a
second time on its own connection. Of 33 player pauses over 290 ms, the second connection paused at the same time and for
the same length in 32, on all four addresses of each of two CloudFront sites. The pauses start
upstream of the CDN, so a second connection or another edge cannot avoid them.

Comparing days by media requests with a pause over 290 ms:

| | long pauses | stalls | stalled, by load-start buffer minus pause: <0 / 0–100 / 100–200 / ≥200 ms |
| --- | --- | --- | --- |
| Sep 26 `eun11`, old sizing (5.9 h, buffer 387 ms) | 2.2/h | 1.9/h | 5/5, 4/7, 0/1, 0/0 |
| Sep 27 `eun12`, old sizing (7.1 h, 533 ms) | 2.1/h | 1.8/h | 2/2, 4/7, 0/3, 0/3 |
| Sep 29 `eun11`, per-stream limits (4.0 h, 419 ms) | 6.0/h | 2.8/h | 4/4, 4/10, 0/9, 0/1 |
| Sep 29 `eun11`, with shadow (3.8 h, 449 ms) | 8.8/h | 4.7/h | 10/10, 5/13, 0/5, 0/6 |

A pause stalls when it is longer than the buffer, about half the time when it is shorter by less
than 100 ms, and never with more room, in every build. The September 29 stall rate follows three
to four times as many long pauses, not a change in how they turn into stalls.

## Remaining limits of cushion adaptation

The 150 ms margin, the 20-minute memory, the 3-minute trim spacing, the 20-minute hold after a
normal-delivery stall and the 250 ms byte-gap cut-off are first choices. They are calibrated
against one evening of `eun12` playback with the video output-gap grace active. The trickle
thresholds come from the September 26–27 heartbeats. The 3.5× pause factor comes from about
45 minutes of US-origin and 20 minutes of `euc11` playback on September 28. Replayed on
those requests, it flagged 0% of US requests (instead of 8.4%), 1.8% of `euc11` requests
(instead of 6.7%) and kept both September 27 `eun12` stalls with 502 and 602 ms pauses abnormal.
A factor of 4 would have put `eun11`'s limit at about 330 ms, just under the shortest abnormal
pause seen there (338 ms). The trickle confirmation is not replayed; heartbeats only sample the
byte count every 500 ms. After a stall the level needs 3 covered buckets again,
so no new depths are measured for about 30 s. The sizing never goes below the user setting
(`ll_target`), so a floor above the recommendation decides the cushion by itself.


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

At that time `TwitchBufferShadow` only observed: a roughly two-minute speed-corrected buffer
history, a one-minute minimum observation period and a 150 ms margin. `TwitchBufferLows` on
`feat/adaptive-buffer-sizing` replaced it.

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

## September 24 stall point

The first analysis of `TRACK-TIMING` against real stalls used the September 19–20 capture: about
23 hours of the `fgl27` clone's `twitchll-live.log`, starting at its September 19 01:32 records.
About 23 of 31 ordinary main-player stalls had the same shape. The video sample queue had drained
into the decoder (`queuedAheadUs − inputAheadUs` at 0–67 ms, median 16), but aggregate `buf` still
read 220–320 ms. That remainder sat inside the Shield's video decoder, which held 10–14 buffers
(an `inputAheadUs` of about 230–290 ms). Audio was not the track that ran out; roughly 300 ms of
decoded audio sat downstream of its decoder. Just before several of these stalls the shadow
reported 119–224 ms of headroom, so a trim based on the aggregate minimum would have trimmed into
stalls.

The decoder does not refuse to drain; the September 24 data below corrects that reading.
`MediaCodecRenderer.isReady()` needs an unread sample, a dequeued output frame, or the codec
hotswap grace. With nothing unread, the renderer is ready only while the decoder has the next
frame out on time. A 10–20 ms output gap with a full decoder then drops the player into
buffering, and the recovery threshold turns it into a 250–450 ms stall.

About 7 other stalls had a shallow decoder input lead (about 50 ms). In those, `buf` fell from
300–580 ms to about 80–100 ms in under a second with no media request in flight. They look like
codec reset or discontinuity events, not delivery shortfalls, and are not yet classified.

`MediaCodecRenderer.getUnreadQueuedDurationUs()` (Media3 branch `feat/renderer-unread-duration`)
returns the largest queued sample timestamp of the renderer's track minus the renderer's reading
position. That is the queued media the renderer has not yet read into the decoder. It is
`TIME_UNSET` without a stream, without queue data, or after the stream was read to its end. Each
player binds its video renderer to its `TwitchLivePlaybackSpeedControl` at build time.
`getAdjustedPlaybackSpeed` samples the value on the playback thread on every call, which happens
on every `doSomeWork` iteration while playing. The controller keeps it in a second
speed-corrected 10 s window next to the aggregate one and in an unread-video low for the
heartbeat. Neither affects speed, cushion or recovery yet. Only the main slot's heartbeat takes the
low, so the first `unreadLow` after a picture-in-picture swap also covers the time the player
spent in the small window.

This quantity, not aggregate `buf`, is the intended input for trimming the target from observed
minima.

This build was installed September 24 at about 15:46 Berlin time (PID 20196). All 75 app tests and
the 15 `MediaCodecRendererTest`/`CodecTimingHistoryTest` cases passed. In the first minute of
settled playback, `unreadLow` sat at 83–166 ms against a `buf` of about 430 ms. That is never
above the 500 ms-sampled `TRACK-TIMING` `queuedAheadUs − inputAheadUs` (150–183 ms), and the
video decoder input lead was about 240–280 ms.

The first 91 minutes of that process (15:48–17:19, one `eun12` source, no sequence gaps) had four
main-player stalls, and each began with unread video at 0. They came in two shapes:

- 16:04 and 16:41: delivery was normal (`loadAgeMs` 371 and 492). The decoder still held 13–14
  frames when video readiness dropped, and readiness returned 8–21 ms later. The stalls still lasted
  423 and 313 ms.
- 15:59 and 17:08: slow segments (`loadAgeMs` 1440 and 920) held unread at 0 for about 200 ms.
  The decoder then drained to 1–4 frames with `buf` at 77 and 131 ms, and the stalls lasted
  266–298 ms.

Unread reaching 0 is necessary but not sufficient. 66 heartbeats more than 10 s from any stall
also had `unreadLow=0`, and it is censored at 0, so it cannot say how deep a shortfall went. At an
effective cushion of 473 ms, `unreadLow` reached 0 in most 5-minute blocks. At 523–573 ms
(16:08–16:28) its block minimum stayed at 150–183 ms. Three of the four stalls happened during a
50 ms step-down probe, from a level where unread had already been reaching 0.

## Video output-gap grace

`MediaCodecVideoRenderer.setOutputGapGraceMs(long)` (Media3 `feat/video-output-gap-grace`) keeps
the video renderer ready for up to that long after its last processed output buffer, while the
decoder still holds frames and nothing is unread. `PlayerActivity` sets 100 ms
(`VIDEO_OUTPUT_GAP_GRACE_MS`) on every player's video renderer. It targets the full-decoder stalls
from "September 24 stall point": the 10–20 ms wait for the decoder's next frame no longer counts
as a rebuffer. A drained decoder, a codec flush or a longer gap still reports not ready. During a
covered gap the playback clock and audio continue and the video frame is late or dropped.
Playback position is never held back, so this adds no latency. A long grace would hide real video
freezes behind continuing audio. The unit tests cover the covered gap, its expiry and the
drained-decoder case.

Installed September 24 at about 18:12 Berlin time (PID 28159). The Mac capture lost most of
18:17–20:08, so the evaluation uses the device files, which are complete. Compared over the same
`eun12` channel:

| | before (PID 20196) | with grace (PID 28159) |
| --- | --- | --- |
| playing time | 2.4 h (15:48–18:12) | 4.9 h (18:12–23:08) |
| main-player stalls | 5 (2.1/h) | 7 (1.4/h) |
| full-decoder stalls | 2 (13–14 frames in codec) | 0 |
| drained-decoder stalls | 3 | 7, all with `buf` 39–93 ms |
| median LTB | 656 ms | 534 ms |
| dropped frames | 0 | 5, in 2 events |

The main player logged 107 `resumed` grace episodes: median 10 ms, 90th percentile 32 ms,
maximum 102 ms. 108 ended because new media arrived and 62 because the decoder produced a frame,
counting the small-window player's episodes too. Every `expired` episode had 1–2 frames left in
the decoder and was followed by a stall, so drains are still reported. Because stalls stopped,
the step-down probes walked the effective cushion from 473 ms down to the 250 ms user floor.
From 22:42 it ran there with a median LTB of 404 ms, 39 bridged gaps and no stall. With the grace,
the stall point in aggregate terms is 40–90 ms, not about 250 ms. The evening and afternoon
deliveries are not a controlled comparison.

A `loadAgeMs` of about 2 s is normal and does not mark a slow load. Twitch streams each 2 s
prefetch segment while it is produced, so every media request lasts about 2000 ms (p99 2025 ms).
Within a request, bytes normally arrive with gaps of about 83 ms: `maxByteGapMs` has a median of
83 ms and a p99 of 105–165 ms. Eight of the twelve stalls across both builds had a byte gap of
357–501 ms in the request in flight, which is abnormal delivery. The other four had 66–100 ms:
16:04 and 16:41 (full decoder, now bridged by the grace), 18:10 and 21:04 (drained). At a
250 ms cut-off, 0.46% of media requests count as abnormal. The active request's time without bytes
is live in `TwitchDeliveryProgress` (`mediaNoByteMs`).
