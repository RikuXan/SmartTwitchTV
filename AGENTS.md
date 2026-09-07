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
| `.tmp/media/.claude/worktrees/twitch-low-latency` | The patched Media3/ExoPlayer fork this build links against. |
| `.tmp/docker/` | Dockerfiles for the two build images. |
| `.tmp/logs/twitchll-live.log` | Continuous `TwitchLL` capture written by the `stv-logcat` container. |

Application ids: release is `com.fgl27.twitch.ll`, debug is `com.fgl27.twitch.debug`
(`applicationId` + `applicationIdSuffix` in `apk/app/build.gradle`). Both install alongside the
official app.

## Files that must stay uncommitted

- `apk/settings.gradle` — points `gradle.ext.mediaRoot` at the local Media3 fork worktree. The
  committed value is upstream's relative path and does not resolve here.
- `apk/app/google-services.json` — untracked local stub; the build fails without it.
- `apk/app/.cxx/` and `apk/.kotlin/` — native/Kotlin build artifacts, listed in
  `.git/info/exclude`. Never stage them; an earlier session committed 149 of these by accident.

## The Media3 fork

The player is not a dependency, it is compiled from source alongside the app.

- `.tmp/media` is a clone of `fgl27/media` (a fork of `androidx/media`, base Media3 **1.8**),
  parked on its `release` branch at upstream commit `4d0e670`.
- `.tmp/media/.claude/worktrees/twitch-low-latency` is a **git worktree** of that clone on branch
  `feat/twitch-prefetch-low-latency-dev`, remote `fork` = `RikuXan/media`. This worktree is what the
  APK links against; the parent clone is only the upstream base.

Wiring, in `apk/settings.gradle`:

```groovy
gradle.ext.mediaRoot = "$rootDir/../.tmp/media/.claude/worktrees/twitch-low-latency"
gradle.ext.androidxMediaModulePrefix = 'media3-'
apply from: new File(gradle.ext.mediaRoot, 'core_settings.gradle')
```

`core_settings.gradle` `include`s every Media3 library as a source module (`:media3-lib-common`,
`:media3-lib-exoplayer`, `:media3-lib-exoplayer-hls`, …). So **editing a file in the worktree is
picked up by the next `./gradlew assembleRelease`** — there is no publish, install or version-bump
step. The flip side is that a player edit recompiles those modules, so expect a full ~3 minute
build, and the change must be committed in that worktree separately from the app repo.

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
R=/Users/philipp.holler/Code/github/fgl27/SmartTwitchTV
docker rm -f stv-build 2>/dev/null
docker run -d --name stv-build \
  -v "$R":/project \
  -v stv-gradle-cache:/root/.gradle \
  -v stv-android-config:/root/.android \
  -w /project/apk stv-android-build-ndk ./gradlew assembleRelease --no-daemon
docker wait stv-build
docker logs --tail 3 stv-build
```

Detached plus `docker wait` rather than `docker run --rm` in the foreground: a full build takes
about three minutes and an interactive run tends to hit tool timeouts. Add `assembleDebug` to the
Gradle invocation when a debug APK is wanted; the release build alone is enough for the Shield.
The named volumes keep the Gradle cache and the SDK's `.android` state across runs — without them
every build re-downloads dependencies.

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
adb shell am start -n com.fgl27.twitch.ll/com.fgl27.twitch.PlayerActivity'
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

## Reading the log

All instrumentation in this fork logs under the `TwitchLL` tag, in **release builds too** — gated
by `LL_DIAG` in `PlayerActivity.java`, deliberately left on.

```bash
docker exec stv-logcat adb logcat -d -s TwitchLL | tail -40      # recent
docker exec stv-logcat adb logcat -d -s TwitchLL | grep presence  # points/drops/watch events
```

Log lines carry the emitting pid. After a deploy the previous process keeps emitting for a few
seconds, so filter by the newest pid (`adb shell pidof com.fgl27.twitch.ll`) before concluding
anything about a fresh build — otherwise you will read the old build's output and think your change
did nothing.

## Branches

- `feat/low-latency-improvements-dev` on remote `fork` (`RikuXan/SmartTwitchTV`) is where work
  lands. `origin` is upstream `fgl27/SmartTwitchTV`; never push there.
- Never push to `feat/low-latency-improvements` or `feat/twitch-prefetch-low-latency` — those are
  frozen snapshots referenced from a public upstream issue.
- The Media3 fork has its own remote and branch (`feat/twitch-prefetch-low-latency-dev` on
  `RikuXan/media`). Changing player internals means committing in that worktree as well, and the
  APK build picks it up through `mediaRoot`.

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

- **Window**: 10 buckets × 1000 ms, each holding that second's minimum buffered duration; the
  control variable is the minimum across all buckets.
- **Error and gain**: `error = windowedMin − (cushion + stallExtra)`, deadband ±50 ms, proportional
  factor `0.1 per second of error`, clamped to the `LiveConfiguration` speed range. Updates are rate
  limited to one per 1500 ms.
- **Arming**: samples from the startup fill ramp are not delivery jitter and must never enter the
  window. The controller stays at 1.0× until the buffer stops setting new maxima for 2000 ms *and*
  is within 200 ms of its peak, or a 10 s deadline expires.
- **Warmup asymmetry**: until one full window of real data exists, slowing below 1.0× is forbidden
  (`minSpeed = 1f`) while shaving above it is allowed — the window's early lows are fill artifacts.
- **Stall response**: `notifyRebuffer()` adds a 250 ms bump to `stallExtra` (cap 1500 ms), and
  re-arms the window, because a post-stall refill is another fill ramp and leaving the empty buffer
  in the window would charge the same stall twice — that double-charge used to make the player slow
  *down* right after a stutter instead of catching up. Repeat stalls escalate the hold before decay
  (`120 s × min(streak, 8)`), then `stallExtra` decays at 25 ms per second back toward the user's
  floor.
- **Speed output**: glides by 0.01 per update and snaps to exactly 1.0×, where the audio stretcher
  is bypassed. Each speed change reconfigures the stretcher audibly, so the number of changes
  matters as much as their size.

## Where the cushion comes from

`HlsMediaSource.updateLiveConfiguration` (in the Media3 fork) computes, for `LowLatency == 1`:

```
targetOffsetMs = userTargetMs + originCushionExtraMs()
maxOffsetMs    = targetOffsetMs + 1500
minPlaybackSpeed = 0.97   maxPlaybackSpeed = 1.05
```

`userTargetMs` is the `ll_target` setting (`0s, 0.25s, 0.5s, 0.75s, 1s, 1.5s, 2s, 3s`, default
`1s`). The `maxOffsetMs` cap exists because an uncapped target let latency ratchet upward over time.

`originCushionExtraMs` scales the cushion by how far away the stream's origin is:
`Tools.ProbeIngestRtts()` fetches Twitch's ingest directory at startup and TCP-connects to each
cluster twice, keeping the worst RTT per origin family. `Tools.OriginCushionExtraMs` then returns
`min(1000, round(2.5 × rtt))`, or `-1` while the probe has not answered (the fork retries on the
next playlist refresh rather than caching a wrong value), falling back to the worst known family for
an unseen origin. The origin comes from `ORIGIN="…"` in the multivariant playlist that was actually
loaded — reading it from a stale playlist once left a Tokyo stream running for two hours on an
EU-sized cushion.

The factor 2.5 was fitted against logged drawdowns across eun/euw/use/usw/apn: the observed
worst-case shortfall was ≈ `131 ms + 2.24 × RTT`, so 2.5 covers it with a small margin.

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

## Instrumentation

`LL_DIAG` in `PlayerActivity.java` is `true` and gates all `TwitchLL` logging **in release builds
too**, deliberately. The per-tick heartbeat is the main data source:

```
raw= shown= edgeDist= buf= pp= pos= ctlMin= ctlStallX= ctlSpeed= target= lowLat= targetMs= origin= extraMs= speedAdj=
```

`ctlMin` is the windowed minimum, `ctlStallX` the current stall extra, `ctlSpeed` the controller's
output, `target`/`targetMs` the effective and configured cushions. Event lines carry a `p<slot>`
prefix:

- `STALL pos= buf= inFlight= loadAgeMs= loadUri= sinceMediaDoneMs= sinceManifestMs=` — enough to
  tell a delivery stall (a load in flight, aged) from a local one (nothing in flight).
- `STALL-RECOVERED durMs=`
- `LOAD-RETRY`, `LOAD-COMPLETED (SLOW-LOAD >4s)`, `LOAD-CANCELED`, `LOAD-ERROR`
- `AUDIO-UNDERRUN bufMs= sinceFeedMs=` — the audio path starving, which is *not* a buffer
  underrun and has a different cause.
- `DROPPED-FRAMES count= elapsedMs=`

## Findings that constrain any redesign

- **Stalls are usually not our fault.** Most observed stutters were Twitch-side delivery gaps
  (visible as an aged in-flight load) or, on this device, the Shield's Dolby MS12 path: with HDMI
  audio output set to Dolby, an `AUDIO_FORMAT_E_AC3` direct-output thread burned ~19 % of a core and
  produced regular audio underruns. Switching the Shield's HDMI output to PCM took HAL CPU to zero
  and audio underruns to zero. Any latency experiment must control for that setting.
- **Approaches already tried and rejected**: instantaneous buffer as the control variable (too
  noisy); a windowed *average* instead of the minimum (hides the drawdowns that actually stall);
  magnitude-based outlier filtering of the jitter window (fragile — startup and post-stall ramps are
  excluded by causal markers instead); pitch modulation for crackle-free catch-up.
- **PiP/multi**: each player slot runs its own controller; two 1080p streams on this device can
  starve each other.

## Open question: converging on the optimal cushion

The cushion floor is still a user setting (`ll_target`) plus a static origin term. 0.25 s works on
an EU stream, 0.5 s on a US one, and neither number is derived — the user picked them by
observation. What is missing is a mechanism that *searches* for the lowest cushion a given stream
sustains without underruns, and that re-searches when conditions change. The stall bump is only the
reactive half of such a loop: it raises the cushion after a stall and decays back to the floor, but
nothing ever probes below the floor, so a stream that could run at 0.1 s never gets there, and the
decay makes a genuinely bad stream stall again every couple of minutes.
