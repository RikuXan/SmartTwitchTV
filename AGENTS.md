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
