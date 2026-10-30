# Librespot on-device playback — progress log

Branch: `kotlin-librespot` (clean start from stable `kotlin` @ 453f97f)
Module: `audio-librespot/` (experimental — **wired into `:app` behind an opt-in flag** since M4 app layer)
Started: 2026-10-24

The Media3 30-second-preview experiment lives on the `kotlin-player` branch and is
**out of scope** here — this branch does not build on it and does not ship it.

## Goal

Full-track audio playback inside the dashboard app, using **librespot** (Rust)
embedded as a native library. Kotlin UI stays in charge; the Rust side owns the
Spotify session, decoding and PCM production; Kotlin owns audio output (AudioTrack)
and lifecycle.

## Constraints & accepted risks

- librespot reverse-engineers Spotify's private API — not endorsed by Spotify; the
  project itself warns it may violate the ToS. Accepted knowingly.
- Full tracks require a **Spotify Premium** account.
- `librespot` 0.8.0 has no refresh-token support (`Credentials::with_access_token`
  only) → the Kotlin side must hand in a freshly refreshed access token and
  re-login before/at expiry (~1 h).
- Experimental module stays isolated: main `app` keeps zero references until M4,
  and even then behind a flag.

## Architecture (decided)

- **JNI embedding** (cdylib `libspotify_librespot.so`) — not the CLI process and
  not the `pipe` backend: the CLI cannot realistically do OAuth on Android, and
  the process route loses tight lifecycle control.
- No desktop audio backends: a custom `Sink` implementation pushes decoded
  `f64` samples through `Converter::f64_to_s16` into a **bounded ring buffer**
  (~8 s cap, drop-oldest), which Kotlin drains into an `AudioTrack`
  (ENCODING_PCM_16BIT, 44 100 Hz, stereo, streaming mode).
- Volume is applied by the player via our `VolumeGetter` (atomic f64
  attenuation factor, settable from Kotlin).
- librespot 0.8 feature set: `default-features = false, features = ["rustls-tls-webpki-roots"]`
  — avoids `native-tls` (needs OpenSSL) and `rodio-backend` (needs ALSA/cpal).

## Toolchain (verified working)

| tool | version / path |
|---|---|
| rustc / cargo | 1.99.1 (`C:\Users\Damianos\.cargo\bin`) |
| cargo-ndk | 4.1.2 |
| Android NDK | 26.2.11394342 (+ 28.1.13356709 available) |
| rustup Android targets | `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android` — all installed |

Build command (from `audio-librespot/rust`):

```powershell
$env:PATH = "C:\Users\Damianos\.cargo\bin;$env:PATH"
$env:ANDROID_NDK_HOME = "C:\Users\Damianos\AppData\Local\Android\Sdk\ndk\26.2.11394342"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 --platform 24 -o ../src/main/jniLibs build --release
```

## Milestones

### M0 — toolchain spike — DONE (2026-10-24)
- Empty cdylib crate (`libspotify_librespot.so`) cross-compiled + copied to
  `jniLibs/` for all four ABIs; ELF verified (`llvm-readelf`: AArch64).

### M1 — librespot compiles for Android — DONE (2026-10-24)
- Added `librespot 0.8.0` (no default features; `rustls-tls-webpki-roots`).
- Wrote the bridge skeleton: `PcmBuffer` (ring buffer), `PcmSink` (custom `Sink`),
  `SharedVolumeGetter`, and a full end-to-end `_m1_compile_check` that type-checks
  session connect + player load against the real 0.8.0 API.
- aarch64 release build: **green in ~1 min** (≈350 crates), .so linked.
- Note: the built `.so` is only ~0.4 MB because the never-called compile-check is
  dead-code-eliminated by LTO — expected at this stage. Real JNI exports in M2 will
  keep the full code.
- **Fix applied:** crates.io resolution pulled `vergen 9.1.0` into librespot-core's
  build script while `vergen-gitcl 1.0.8` expects `vergen-lib 0.1.6` → type
  mismatch, build script failed. Pinned with `cargo update -p vergen --precise 9.0.6`
  (persisted via the committed `Cargo.lock`).

### M2 — JNI surface — DONE, device-verified (2026-10-24)
Delivered (committed on this branch):
- Rust: `pcm.rs` (ring buffer + `PcmSink` + `SharedVolumeGetter`), `engine.rs`
  (tokio runtime, session rotation per login, player event loop → shared state),
  `jni_api.rs` (13 `Java_com_spotifydashboard_librespot_LibrespotBridge_*`
  entry points, panic-guarded so a Rust bug can't abort the app).
- Cross-compiled **all four ABIs**: 4.4–6.2 MB per ABI; all 13 JNI symbols
  exported and verified with `llvm-nm`.
- Kotlin module `audio-librespot`: `LibrespotBridge` (raw externals) +
  `LibrespotEngine` (Closeable lifecycle wrapper). Compiles green;
  `assembleDebug` produces a 10.4 MB AAR packing all four `jni/*.so`.
- `android/settings.gradle` registers `:audio-librespot` — **`:app` does not
  depend on it yet** (no runtime pollution).

**Android-specific fixes found and applied during verification:**
- **No `/tmp` on Android:** with no cache, librespot-audio spools streamed files
  via `NamedTempFile::new_in(session.config().tmp_dir)` (`fetch/mod.rs`) and
  `SessionConfig::default()` points `tmp_dir` at `std::env::temp_dir()` (`/tmp`,
  which does not exist). `nativeCreate` now takes the app-private writable dir
  (Kotlin passes `context.cacheDir`); `Engine::create` rejects a non-directory
  path. The config is stored in the engine and reused for every login session.
- **Instrumented test APK needs `INTERNET`:** without it, Android resolves DNS
  through netd and native `getaddrinfo` fails with
  `No address associated with hostname` (verified: shell `ping` to
  `apresolve.spotify.com` works, app-level resolution failed). Added
  `src/androidTest/AndroidManifest.xml`.

JNI contract (implemented):
| function | notes |
|---|---|
| `nativeCreate(tmpDirPath): Long` | tokio runtime + librespot `Player` + event loop; 0 on failure |
| `nativeLogin(handle, token): Boolean` | fresh `Session::new` + `connect`, then `player.set_session` (token rotation) |
| `nativeLoad(handle, uri): Boolean` | `SpotifyUri::from_uri` + `load(uri, play=true, 0)` |
| `nativePlay/Pause/Stop/Seek/SetVolume` | player commands / attenuation factor |
| `nativeReadPcm(handle, out): Int` | ≤ 4096 interleaved S16 samples per call |
| `nativeState(handle): Int` | 0 idle / 1 loading / 2 playing / 3 paused / 4 unavailable |
| `nativePositionMs(handle): Int` | from Playing/Paused/PositionChanged events |
| `nativeLastError(handle): String` | login/load error text |
| `nativeDestroy(handle)` | frees engine (drop order: player → session → runtime last) |

Device verification — **15/15 green** (2026-10-24, T770H/Android 11, Neffos
C5a/Android 7.0, Lenovo TB-7305X/Android 9; 5 tests × 3 devices, 0 failures):
- `engineCreatesExercisesAndCloses` — create + state + volume + transport + bad-URI
  rejection; `closedEngineIsInert` — idempotent close, post-close calls inert;
  `pcmDrainOnIdleEngineReturnsZero`; `createWithNonExistentTmpDirFailsCleanly`.
- `garbageTokenLoginFailsCleanly` — **proves the on-device network stack**:
  server-side answer after a real round trip is
  `login failed: Permission denied { Login failed with reason: Bad credentials }`
  on all three devices (DNS → HTTPS/apresolve via rustls+webpki-roots → TCP
  access point → auth request → credential rejection). No crash, clean error.

librespot 0.8.0 API notes (verified against the registry source):
- `Player::new(config, session, Box<dyn VolumeGetter + Send>, sink_builder)` →
  `Arc<Player>`; spawns its own tokio runtime thread. We only own a runtime for
  `Session::new`/`connect` (`Session::new` requires `tokio::runtime::Handle::current()`).
- Sink: `write(packet: AudioPacket, converter: &mut Converter)`;
  `AudioPacket::Samples(Vec<f64>)`; `Converter::f64_to_s16(&[f64]) -> Vec<i16>`.
- Events: `player.get_player_event_channel()` → tokio mpsc of `PlayerEvent`.
- `SessionConfig::default()` on an Android build target auto-selects librespot's
  Android client id (`9a8d2f0ce77a4e248bb71fefcb557637`).

### M3 — Kotlin audio output — DONE, device-verified (2026-10-24)
- `PcmPlayer` (module): streaming `AudioTrack` (44 100 Hz, S16, stereo,
  `MODE_STREAM`, buffer sized `max(minBufferSize, 2 × chunk)`), drain thread at
  `THREAD_PRIORITY_AUDIO`, 2 ms idle poll. `pause()` keeps buffered audio for
  seamless resume; `stop()` unblocks a writer parked in `AudioTrack.write`,
  joins the thread, releases the track and drops buffered audio so a later
  `start()` begins clean. `close()` is idempotent — documented contract:
  close the `PcmPlayer` **before** the engine.
- Tests (PcmPlayerTest — no account needed): synthetic 440 Hz source drains
  fully and survives play/pause/restart/stop; idle source stays quiet; real
  engine-as-source wiring while idle.
- `LibrespotPlaybackTest.premiumTokenPlaysAndProducesPcm` — the real end-to-end
  harness (login → PLAYING → position advances → non-silent PCM), **skipped
  unless a token is passed**:
  `./gradlew :audio-librespot:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.spotifyToken=<token>`
  (token must be fresh, ~1 h lifetime; Premium + `streaming` scope).
- Result: **9 tests/device — 8 passed + 1 skipped** on T770H, Neffos C5a, Lenovo.

#### Real-token verification — first attempt (2026-10-24)

Ran the token-gated test directly on T770H via `am instrument` (single device on
purpose: one Premium account allows one concurrent stream):

```
adb -s 44133740 shell am instrument -w \
  -e class com.spotifydashboard.librespot.LibrespotPlaybackTest \
  -e spotifyToken <token> \
  com.spotifydashboard.librespot.test/androidx.test.runner.AndroidJUnitRunner
```

- Token source: the app's own PKCE refresh token (pulled from device prefs via
  `run-as`, refreshed via `accounts.spotify.com`). Verified valid + Premium via
  `GET /v1/me` (`product: premium`) before the device run.
- Result: `login failed: Permission denied { Login failed with reason: Bad
  credentials }` — a real server-side rejection after a full network round trip.
- **Root cause: the token has no `streaming` scope.** librespot's access-token
  login requires it — official wiki (`Options#access-token`): *"Spotify
  access-token to sign in with. **Must include 'streaming' scope**"*. The app's
  existing authorization predates the scope change and refresh tokens keep the
  scopes from the original authorization, so the user must re-link.
- Fix path: `SpotifyAuthManager` now requests `streaming` → reinstall the app on
  the device → log out / log in (Spotify shows the added-permission consent) →
  pull + refresh the new token → re-run the test.

Diagnostics added because of this round (all committed):
- `android_logger` + a panic hook: librespot's `log` output and panics now reach
  logcat under tag `librespot-rs` (previously **nothing** was visible: no logger
  was installed and release-build stderr goes to `/dev/null` on Android).
- `nativeLogin` is no longer silent: unreadable-token failures are logged, and a
  panic inside `login` is caught at the JNI layer and stored in `lastError`
  instead of just returning `false`.
- The playback test built its assertion message *before* calling `login()`, so
  `lastError: null` was read pre-login — the real error had been discarded.
  Fixed: capture the result first, then read/log `lastError`.

#### Real-token verification — second attempt & the playback-auth pivot (2026-10-24)

After the re-link (the app's token now carries `streaming`; verified by
refreshing the new app token), the rejection moved deeper and became
diagnosable:

1. **AP auth succeeds**: `Authenticating with AP using
   AUTHENTICATION_SPOTIFY_TOKEN` → `Reconnect using stored credentials as token
   authed sessions cannot use keymaster` → `Authenticated as '313…' !`,
   Country GR.
2. **Client token / hashcash works on-device**: `Received a hash cash
   challenge, solving...` → `Received a granted token` (retires the old
   "unproven on Android" risk — no `os_version()` patch needed).
3. Playback then failed at the final step:
   `POST https://login5.spotify.com/v3/login` →
   `Unable to load audio item: Error { kind: FailedPrecondition, error:
   FaultyRequest(INVALID_CREDENTIALS) }`.

**Root cause (ecosystem-wide, not our bug):** since **2026-08-10** Spotify's
`login5` refuses playback credentials derived from an access token minted for a
*third-party* client id (librespot issue #1737; go-librespot maintainer:
*"Spotify killed the authentication flow via Login5 when using stored
credentials produced by the AP with an access token with a different client ID
than the desktop one"*). The dashboard's PKCE app id can therefore never work
for playback, regardless of scopes.

**Remedy (implemented):** authorize playback *separately* with librespot's own
desktop ("keymaster") client id — the flow of librespot's new
`examples/get_creds.rs` (PR #1744, merged to `dev` only; crates.io 0.8.0 is
still the latest release and already exposes everything needed):

- `engine.rs` pins `PLAYBACK_CLIENT_ID = 65b708073fc0480ea92a077233ca87bd` as
  `SessionConfig::client_id` — the OAuth token and the session must present the
  same identity.
- One-time interactive OAuth: loopback redirect
  `http://127.0.0.1:8898/login`, scope `streaming` only, PKCE S256, exchange at
  `accounts.spotify.com/api/token` (public client, no secret).
- The AP exchange turns the token into **reusable stored credentials**
  (`Session::username()` / `Session::auth_data()`) which do not expire like
  access tokens. New JNI surface: `nativeStoredCredentials` →
  `username\nbase64(auth_data)` and `nativeLoginStored(username, authDataB64)`
  (+ `LibrespotEngine.storedCredentials` / `loginStored`).
- New test `storedCredentialsReloginPlays`: token login → extract credentials →
  **fresh engine connects with only those** (no token) → PLAYING.

**Third attempt — `BAD_REQUEST` and the identity chain (2026-10-24):** with the
keymaster token, AP login accepted it and even the stored-credential relogin
worked (`AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS` → `Authenticated as
'313…'`), but playback still failed at login5 — now with
`FaultyRequest(BAD_REQUEST)` instead of `INVALID_CREDENTIALS`. Diagnosis
(on-device `librespot-rs` logcat + librespot-core source):

- 0.8.0 hardcodes the *mobile* identity in the steps around login5 even when the
  session client id is pinned: the client-token request always uses
  `ANDROID_CLIENT_ID` + `Pixel`/`api_version 31` platform data
  (`spclient.rs` — it deliberately ignores `SessionConfig::client_id` off
  macOS/Windows), the User-Agent is `Spotify/<mobile 8.9.x> Android/<ver>`
  (`http_client.rs` + `version.rs`), while only the login5 request itself
  carries the keymaster id. The server cross-checks the whole chain; an
  inconsistent one is `BAD_REQUEST` (distinct from `INVALID_CREDENTIALS`,
  which is for badly-derived credentials — that's what we saw pre-pin).
- librespot has **no public knob** for this: `OS` is a compile-time const used
  in exactly five places, all inside librespot-core (`http_client`, `spclient`,
  `version`, `login5`, `connection/mod.rs`), and the `dev` branch is identical
  (its `get_creds.rs` remedy targets desktop builds).

**Fix (vendored patch):** `librespot-core` is vendored at
`rust/vendor/librespot-core` (unmodified registry source, 37 files) and pinned
via `[patch.crates-io]`; the single change is in `config.rs`:
`pub const OS: &str = "linux"`. Every surface then follows librespot's own
Linux/desktop profile — `User-Agent Spotify/124200290 Linux/0 (librespot-…)`,
desktop version strings, client-token with the keymaster id + `desktop_linux`
platform data, AP auth `Os::OS_LINUX`, keymaster at login5 — which is the
configuration the documented OAuth remedy (`get_creds.rs`, PR #1744) is
validated against. The AP handshake already presented `PLATFORM_LINUX_*` on
Android (PR #1403), so this only aligns the remaining surfaces. Keep the
vendored diff at exactly this one line.

**Playback pipeline — the sink must apply backpressure (2026-10-24):** after the
identity patch both logins reached `login5`, the track loaded
(`<Never Gonna Give You Up - 2022 Remaster> (213560 ms) loaded`) and the state
became PLAYING — but `positionMs` stayed at 0 and no PCM was ever asserted.
Diagnosis from librespot-playback 0.8.0:

- `PlayerInternal::poll` busy-loops on `Decoder::next_packet()` while playing;
  there is **no wall-clock pacing in the player**. Real-time behaviour comes
  from the audio backend: `Sink::write` is expected to block (that is how
  rodio/ALSA pace librespot). `next_packet()` blocks only on I/O, never
  "not ready".
- Our `PcmSink` was non-blocking (it dropped the oldest samples when full), so
  the player decoded the whole 213 s track at CPU speed in ~1 s, reached EOF,
  emitted `EndOfTrack` (→ position reset to 0) and left only the tail in the
  8 s ring buffer. Also note `PlayerEvent::Playing` is emitted by
  `start_playback()` *before* the first packet is decoded, so PLAYING alone
  never proved that audio flows.

Fix: `PcmBuffer::push` now **blocks** while the buffer is full (Mutex +
Condvar, 100 ms timed waits so a lost notify cannot wedge the player thread).
`read`/`clear`/`close` notify the condvar; `Engine::stop()` closes the buffer
before `player.stop()` and `Engine::drop` closes it before the player's
join — otherwise a blocked sink write would deadlock stop/destroy. The test no
longer sleeps a fixed 2 s: it drains PCM continuously (like the app's player
loop) and waits (≤30 s) for `positionMs > 0` **and** ≥0.5 s of non-silent PCM,
logging state/position/sample counts every 2 s.

**Result — playback verified on-device (2026-10-24 16:03, T770H):** both tests
pass in 12.2 s (`OK (2 tests)`), with no auth errors:

- token login → PLAYING: `positionMs=8749`, `pcmSamples=73728`, non-zero, no error;
- stored-credential relogin (fresh engine, no token): `positionMs=8698`,
  `pcmSamples=69632`, non-zero, no error.

The position runs ahead of the audible output by up to the ring-buffer depth
(~8 s): it reports the decoded stream position, not what `AudioTrack` has
played. Fine for the tests; the UI should compensate later (see risks).
Teardown logs a one-off `Streamer error requesting range … IncompleteMessage`
when `stop()` aborts an in-flight range fetch — benign.

Auth design (M4): one interactive OAuth in the browser → persist the stored
credentials app-privately → silent `loginStored` on every start; re-run the
browser flow only if Spotify invalidates the credentials.

### M4 — app layer — DONE (module + `:app` wiring, 2026-10-24)

**Implemented (module; `:app` does not depend on it yet):**

- `LibrespotAuth.kt` — on-device authorization-code + PKCE (S256) with
  librespot's keymaster client id and scope `streaming`:
  - `LibrespotLoopbackServer` binds `127.0.0.1` (port 8898, ephemeral fallback),
    answers non-callback probes with 404, validates `state` (CSRF), reports
    `error=access_denied`, and times out cleanly;
  - the authorize URL is opened with `Intent.ACTION_VIEW` (device browser);
  - `LibrespotTokenExchange` POSTs to `accounts.spotify.com/api/token`
    (`HttpURLConnection`, public client, no secret) and parses `access_token`;
  - nothing is logged: no code, token, verifier or auth blob.
- `LibrespotCredentialsStore.kt` — `username` + base64 auth blob in app-private
  `SharedPreferences` (`librespot_credentials`), never in the DB/logs/exports.
- `LibrespotPlayback.kt` — app-facing session: stored credentials → `loginStored`;
  otherwise interactive OAuth → `login` → persist `storedCredentials`; owns the
  engine + `PcmPlayer` (drain starts before `play()`, drain stops before the
  engine closes); `connect(allowInteractive=false)` for silent-start checks;
  `forgetAccount()` clears the store; attach/close are race-free.
- `src/main/AndroidManifest.xml` declares `INTERNET` (merged into consumers).
- Tests (no account): `LibrespotAuthTest` (RFC 7636 vector, URL parameters,
  loopback callback, state mismatch, denial, timeout, token exchange success/
  error against a fake loopback endpoint) and `LibrespotPlaybackSessionTest`
  (credential round-trip, incomplete entry, silent connect without credentials,
  inert after close). Full module suite: **24 tests green** on T770H (the live
  test is skipped unless `-e liveAuth true`); real playback regression still
  green (`OK (2 tests)`, 7.8 s).
- `LibrespotAuthLiveTest` — manual, gated with `-e liveAuth true`: starts the
  loopback listener, logs the authorize URL and waits; the host opens the URL in
  the device browser and approves the consent page. **Verified on T770H
  (2026-10-24): `OK (1 test)` in 32 s; librespot authenticated the stored
  credentials as account `313v3jzv5tis57fvks6bxtjbljau`.** The browser launch is
  host-driven because the instrumentation process has no foreground activity (a
  background `VIEW` intent opens the tab behind the current app, and holding
  UiAutomation blocks the host's `uiautomator`); the real app launches the
  browser from a foreground screen and needs neither workaround.

**Known caveat:** the OAuth callback is delivered to a loopback socket inside
the app process. If Android kills the app while the browser is in the
foreground, the listener dies and the flow must be restarted (keep the app in
the foreground for the first login). Stored credentials make every later start
browser-free.

**Loopback probe fix (2026-10-25, Neffos C5a / Android 7.0):** Chromium
pre-connects to `127.0.0.1:8898` and may leave that socket idle before sending
the real redirect. The accepted socket inherited the listener's short
`SO_TIMEOUT`; reading the request line then failed with `Try again`, the
exception escaped `awaitCode()` and the listener closed. Chrome's actual
`GET /login?code=…` then hit a closed port (`ERR_CONNECTION_REFUSED`) even
though the app process was alive and the FGS notification was showing. Fix:
set a 5 s read timeout on each accepted client and swallow read failures from
idle/aborted probes, so the accept loop keeps waiting for the real callback.
Verified on the Neffos: `authorization code received; exchanging it for a
token` appears and the token exchange runs; the previous
`interactive authorization failed: Try again` is gone. The remaining Neffos
native-login failure in that test was environmental: its Wi-Fi had no IPv4
default route/DNS (`getaddrinfo: ap.spotify.com … No address associated with
hostname`), while the dashboard sync only worked through a temporary PC HTTP
proxy; `apresolve.spotify.com`/`ap.spotify.com` still need a working device
uplink. **User-confirmed 2026-10-25:** after the Wi-Fi recovered, the installed
build works end-to-end on the Neffos; the earlier failure was environmental,
not a code defect.

**Implemented (app layer, 2026-10-24):**

- `:app` now depends on `:audio-librespot` behind `BuildConfig.LIBRESPOT_ENABLED`
  and the `SharedPreferences("local_playback"/"enabled")` opt-in (default off).
- `LocalPlaybackController` (coroutine-driven, Dispatchers.IO for native work):
  queue handling + seek buffer flush, ticker, auto-advance, audio focus, and
  foreground service start at the play tap (before browser OAuth, to satisfy
  Android 12+'s FGS start rules).
- `LocalPlaybackService`: foreground service, MediaStyle notification with 4
  actions (Play/Pause/Seek/Stop), and the `onDestroy` cleanup fix that uses
  `stopForeground(STOP_FOREGROUND_REMOVE)` + `NotificationManager.cancel(NOTIFICATION_ID)`.
- `MainActivity` + `MainViewModel` (`localPlayback` StateFlow + playAlbum/pause/seek/stop
  / setLocalPlaybackEnabled / forgetAccount), `DashboardScreen` mini-player in the
  bottom bar, album-sheet **Play album** button, Player-tab `LocalPlaybackCard`.
- Compose crash fix in `LocalPlaybackCard`: replaced `return@Column` with if/else.
- Position compensation: audible = decoded position − (native PCM buffer + AudioTrack
  queue depth); `EndOfTrack` keeps the final decoded position and auto-advance waits
  until state is IDLE **and** both buffers are empty.

**3-device matrix (full app):**

- T770H (`44133740`, Android 11, arm64-v8a): first-run browser OAuth; `Čistka`
  (4:13) played with audible progress; pause/resume via card/notification/mini-player;
  seek (`command=Seek(193126)`, UI 3:18); auto-advance to `Šlapeto` (4:58);
  Stop removed notification + service; force-stop/restart used
  `AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS` with no browser.
- Neffos C5a (`B50040XE3R81150488`, Android 7.0, armeabi-v7a): app launches,
  library re-synced to 6899 albums, Player-tab card renders, toggle enables and
  persists. Play album binds `127.0.0.1:8898`, the Spotify consent page returns
  the code, and the token exchange runs after the loopback-probe fix below; the
  browser no longer sees `ERR_CONNECTION_REFUSED`. Full native playback in that
  run was blocked by the device's Wi-Fi losing its IPv4 default route/DNS
  (`getaddrinfo: ap.spotify.com … No address associated with hostname`); the
  dashboard HTTP sync only worked through a temporary PC proxy, which native
  TCP does not use. **User-confirmed 2026-10-25:** after the Wi-Fi recovered, the
  installed build works end-to-end, so the Neffos row is green for this
  milestone.
- Lenovo TB-7305X (`HPV3ADTJ`, Android 9, armeabi-v7a): see the fix below.

**Android-specific native fixes:**

- Android 9 32-bit OEM seccomp (`HPV3ADTJ`, syscall 397 = `statx`) blocked the Rust
  `rustix::fs::fstat` path used by `tempfile`'s `NamedTempFile::reopen()`, which
  librespot calls to fetch a track into the audio buffer → SIGSYS in `DefaultDispatch`.
  Fix: vendored `tempfile` 3.27.0 at `rust/vendor/tempfile` with a 32-bit-Android-
  only patch in `src/file/imp/unix.rs` `reopen()` that skips the identity check
  (calls `rustix::fs::fstat` only on non-32-bit-Android targets), plus
  `[patch.crates-io] tempfile = { path = "vendor/tempfile" }`.
  After this, `Čistka` loaded and played on the Lenovo with no SIGSYS (state:
  `AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS`, PLAYING; notification channel
  `local_playback` id 4242, foreground-service shown). All four ABIs rebuilt and
  the new .so repackaged into the debug APK.

**Instrumented tests after the fix:**

- `:audio-librespot` connected (T770H): BUILD SUCCESSFUL — 25 module tests
  (22 run + 3 skipped without a token); 3 live/browser-auth skipped as designed.
- `:audio-librespot` connected (Neffos C5a, Android 7.0, armeabi-v7a,
  2026-10-25): BUILD SUCCESSFUL — 25 module tests, same skips; the loopback
  auth/engine suite also passes on the Android 7 32-bit OEM.
- `:app` connected (T770H): the `AppLaunchSmokeTest` failures are environmental
  — the instrumented launch starts with `Initial token check: NO TOKEN` (device lost
  its dashboard session), so the app lands on the login screen instead of the Library
  and the bottom-nav assertions can't resolve. With an authenticated session
  present (as during the manual `6899 albums` verify), the Library/Playlists tabs and
  `Grid view`/`List view`/`Compact grid` compose nodes render normally. This is not a
  regression from the local-playback changes; the `DashboardScreen` compose tree was
  verified to launch and render the Library with the bottom nav manually before and
  after the change.
- `:app` connected (Neffos C5a, Android 7.0, 2026-10-25): 5 tests — 3 passed,
  1 skipped by design, 1 failed. `bottomTabsRender`,
  `viewModeTogglePresentOnLibrary` and `logDeviceSpec` pass with the authenticated
  6899-album library. `navigateToPlaylistsAndSortRowRenders` cannot find
  `Search playlists...` because that device has no synced playlists; it is a
  data-dependent UI assertion, not a local-playback regression.

## Open risks / things to watch next

1. ~~**Client-token / hash-cash on Android:**~~ RESOLVED (2026-10-24): the real
   token run shows hashcash solved on-device (`Received a hash cash challenge,
   solving...` → `Received a granted token`); no `os_version()` patch needed.
2. ~~**Token scopes:**~~ RESOLVED / superseded: the app's token carries
   `streaming` now, but playback auth moved to librespot's own client id
   entirely (see the pivot section) — the dashboard token is never used for
   playback.
3. ~~**Token expiry (~1 h):**~~ Superseded by reusable **stored credentials**
   (no expiry); refresh-token rotation no longer applies to playback.
4. **APK size:** one fat AAR with 4 ABIs ≈ tens of MB. Consider abiFilters for
   experimental builds.
5. **Background survival:** AudioTrack + foreground service; MIUI/Xiaomi aggressive
   killers are a known hazard on this project's device set.
6. **Vendored librespot-core patch:** `[patch.crates-io]` pins a full copy of
   librespot-core 0.8.0 carrying the one-line `OS = "linux"` identity patch. Any
   librespot upgrade must re-apply it (or drop it once upstream grows a config
   knob for the identity/OS spoof).
7. **Position vs audible audio:** `positionMs` is the decoded stream position,
   which runs up to the `PcmBuffer` depth (~8 s) ahead of what `AudioTrack` has
   played. Either report `positionMs − buffered` (expose the buffered sample
   count from the engine) or shrink the buffer for the UI. Also add a
   seek-while-buffered strategy (flush the ring buffer).
8. **Sink backpressure is load-bearing:** `PcmBuffer::push` blocks when full —
   that is what paces librespot's decode loop. Do not make the sink drop
   samples again (whole-track decode → instant `EndOfTrack`); if the buffer
   policy changes, keep backpressure and keep `close()` before stop/destroy.

## Files

- `audio-librespot/rust/Cargo.toml` — crate + librespot dep pin + release profile
- `audio-librespot/rust/Cargo.lock` — **committed** (contains the vergen 9.0.6 pin)
- `audio-librespot/rust/vendor/librespot-core/` — vendored librespot-core 0.8.0 (registry
  source; single patch in `config.rs` → `OS = "linux"` identity), pinned via
  `[patch.crates-io]`
- `audio-librespot/rust/vendor/tempfile/` — vendored tempfile 3.27.0 (single 32-bit-Android
  patch in `src/file/imp/unix.rs` `reopen`: skip `rustix::fs::fstat`/`statx` on 32-bit
  Android), pinned via `[patch.crates-io]` (apps no change to the librespot OS patch)
- `audio-librespot/rust/src/lib.rs` — crate root (module wiring + M0 marker)
- `audio-librespot/rust/src/pcm.rs` — ring buffer (blocking push = decode pacing), `PcmSink`, `SharedVolumeGetter`
- `audio-librespot/rust/src/engine.rs` — runtime/session/player, state, tmp_dir config,
  `PLAYBACK_CLIENT_ID` pin, stored-credential login/extraction
- `audio-librespot/rust/src/jni_api.rs` — 15 JNI entry points, panic-guarded
- `audio-librespot/rust/src/logging.rs` — android_logger + panic hook (tag `librespot-rs`)
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/LibrespotBridge.kt` — raw externals
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/LibrespotEngine.kt` — Closeable facade
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/PcmPlayer.kt` — streaming AudioTrack drain
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/LibrespotAuth.kt` — on-device PKCE + loopback OAuth + token exchange
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/LibrespotCredentialsStore.kt` — app-private stored credentials
- `audio-librespot/src/main/java/com/spotifydashboard/librespot/LibrespotPlayback.kt` — high-level session (loginStored → OAuth fallback, engine + player)
- `audio-librespot/src/androidTest/` — smoke, PcmPlayer, auth/session, token-gated playback and live-OAuth tests
- `audio-librespot/src/main/jniLibs/<abi>/libspotify_librespot.so` — build output (git-ignored)

## Spotify Connect refinement (2026-10-25, branch `kotlin-librespot-next`)

- The on-device librespot player now also registers as a Spotify Connect device through `librespot-connect` (`Spirc`) instead of the bare `Player` + manual `load` path. Other Spotify clients (phone/desktop) can now see it in the device picker and transfer playback to it.
- One fresh `Session` is created per login and handed to both `Player` and `Spirc`; `Spirc::new` performs the AP connect, so `Engine::connect_session` is gone. Re-auth uses `Spirc::shutdown()` plus teardown of the spawned task (2 s timeout + abort) before creating a new session.
- A new `SharedMixer` (custom `Mixer` + `VolumeGetter`) replaces `SharedVolumeGetter`: it keeps the Connect volume (0..=65535) and the app's audio-focus ducking factor separate and multiplies them into the attenuation the player reads, so a remote volume slider change and local ducking no longer stomp on each other.
- `Engine::{load,play,pause,stop,seek}` now route through `Spirc` commands (with `activate` first so the app can take over from another device). `next`/`previous` and a `currentTrack` snapshot (`TrackInfo` from `PlayerEvent::TrackChanged`, carrying title/artist/album/duration/art url) were added on top, with JNI entry points `nativeNext`, `nativePrevious`, `nativeCurrentTrack`.
- `LibrespotPlayback` adds a state-monitor thread that starts/pauses the `PcmPlayer` when the engine (possibly driven remotely) enters `PLAYING`/`PAUSED`, so audio from another device is actually audible. Device name is stable via `sha1(device_name)` as the librespot binary does.
- Kotlin controller: ticker now reflects remote track metadata for UI/remote detection, auto-advance only runs for the local queue, and `next()`/`previous()`/`resume()` route to the Connect device when the current track is not from the app queue.
- Build/toolchain state: cargo ndk release rebuilt for all 4 ABIs and repackaged; `:audio-librespot:connectedDebugAndroidTest` builds clean; app installs on Neffos `B50040XE3R81150488`. End-to-end manual detection check is pending (app has stored credentials and local playback enabled on the Neffos, but the run needed a foreground play tap that we have not automated).


## Android app progress (2026-10-27)

- Kotlin now requests a configurable PCM buffer duration (2-15 s) through 
ativeCreate(tmp_dir, device_name, buffer_seconds) into Engine::create; default 8 s.
- Device name and audio-focus behavior remain persisted client-side; Rust PCM capture remains 44.1 kHz stereo S16.
- Quality/bitrate and disk-cache size controls were not added; the current Rust SessionConfig/PlayerConfig do not expose them.

