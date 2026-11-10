# reSpotIt — Android App (Kotlin)

## About

reSpotIt was built to fill gaps in Spotify's library management. It is an album-centric tool that lets you organize your library and playlists using rich filters, add entire artist discographies to your library or turn them into playlists, import album links from any website, and build queues from albums you are listening to right now. This is a work in progress — expect rough edges and things that may not work yet.

Native Android client written in **Kotlin + Jetpack Compose**. No web server, no Capacitor runtime, no backend required: the library lives in an on-device Room database and the app works fully offline.

- **Language:** Kotlin 1.9
- **UI:** Jetpack Compose (Material 3), single-activity architecture
- **Storage:** Room (SQLite) + OkHttp cache for artwork
- **Networking:** Retrofit 2 + Gson + OkHttp (logging interceptor)
- **Auth:** AppAuth (OAuth 2.0 PKCE) for Spotify, plain bearer tokens for the media server
- **Background work:** WorkManager (retention cleanup, queue switching)
- **Media:** Coil (artwork), AndroidX Media/ExoPlayer-compatible surfaces
- **Toolchain:** JDK 17, Gradle 8.2.1 (wrapper), AGP with `kotlin-kapt`, compileSdk 34, targetSdk 34, minSdk 24 (Android 7.0)
- **App id:** `com.spotifydashboard.app`

### reSpotIt Project Structure

reSpotIt is a multi-component project with several variants:

- **Local Server (standalone)** — Python/FastAPI backend + React web frontend + PostgreSQL. Runs entirely on your machine. No AI assistant. See `reSpotIt-local-server-app`.
- **Local Server + AI assistant** — Same server plus OpenWebUI, Keycloak, and MCP services for AI-powered library queries. See `reSpotIt-local-server-app` (full stack overlay) or `reSpotIt-local-server-app-clean-backup-2`.
- **Kotlin client** (this repo) — Native Android app (Kotlin + Jetpack Compose). Full feature parity with media-server mode.
- **React clients** — Capacitor/TypeScript Android apps. Offer better compatibility on older Android models and low-RAM devices than the Kotlin client. Two variants:
  - **Standard** — Web Playback SDK for browser-based playback (`reSpotIt-react-version`, branch `main`).
  - **Librespot** — Experimental on-device full-track playback via Rust/JNI (`reSpotIt-react-librespot-version`, branch `feat/librespot-player`).

> **Note:** All Android clients work fully offline without any server. Media-server mode is optional — enable it in Settings to sync, push/pull, and use the AI assistant.

**Repository:** https://github.com/damianoshar/reSpotIt-kotlin-version-stage (branch: `main`)

---

## 🛠 Build from Source

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform 34, build-tools 34.0.0 |
| Gradle | 8.2.1 (bundled wrapper — no local install needed) |
| Android Studio | Ladybug or newer (optional, CLI works fine) |

The Gradle wrapper is committed, so `./gradlew` works out of the box. The `android/` project is tracked in Git (124 files) — there is **no** `npx cap add android` step and **no** `android-patches/` copy step in this repo.

### 1 — Build a debug APK

```bash
cd android
./gradlew :app:assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

### 2 — Build a release APK

```bash
cd android
./gradlew :app:assembleRelease
# -> android/app/build/outputs/apk/release/app-release.apk
```

### 3 — Install on a device

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

The Kotlin app installs as `com.spotifydashboard.app`. The React clients install as `com.spotifydashboard.app.ts`, so both can be installed side by side on the same phone.

### 4 — Open in Android Studio

Open the repository root, then run the `app` configuration. The `audio-librespot` module is included automatically (`android/settings.gradle` maps `:audio-librespot` to `../audio-librespot`).

---

## 🔐 Release signing

Signing is opt-in through a **gitignored** properties file at `android/keystore.properties`:

```properties
storeFile=app-release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

- If the file exists, the `release` build type uses it.
- If the file does not exist, Gradle falls back to **debug signing** — the APK still installs, but it is not publishable to Google Play and its key differs from the signed release on the device.
- `android/keystore.properties`, `android/app/app-release.keystore` and `*.keystore` are gitignored. Never commit them.
- CI (`.github/workflows/release.yml`) decodes the keystore from the `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets into the same file, then runs `:app:assembleRelease` and publishes the APK to the GitHub release for `v*` tags.

---

## 🎧 Spotify Developer Setup (do this first)

1. Go to https://developer.spotify.com/dashboard and create an app.
2. In your app settings, add this **Redirect URI** (exact string):
   ```
   com.spotifydashboard.app://callback
   ```
3. Under "APIs used", enable **Web API**.
4. Either paste your **Client ID** into the app (Settings → Spotify credentials) or use the built-in reSpotIt client id.

> **Note:** Since Nov 2025 Spotify requires PKCE — implicit grant is removed. This app uses the PKCE Authorization Code flow via AppAuth.

**Single callback owner:** AppAuth's `RedirectUriReceiverActivity` is explicitly removed from the merged manifest (`tools:node="remove"`) because it claims the same custom scheme as `MainActivity`; Android then shows an app chooser for the redirect, and some ROMs crash on that chooser. `MainActivity` is the only handler of `com.spotifydashboard.app://callback`. The CI `tests.yml` workflow asserts this invariant on every build.

---

## 🗺️ App architecture

```
android/app/src/main/java/com/spotifydashboard/app/
├── MainActivity.kt              ← single activity, Compose host, AppAuth redirect receiver
├── api/                         ← Retrofit services + DTOs
│   ├── DashboardApiService.kt   ← media-server /api/v1 client (albums, playlists, LLM, jobs)
│   ├── SpotifyApiService.kt     ← Spotify Web API client
│   ├── SpotifyPlaylistApiService.kt
│   ├── DashboardApiClient.kt    ← OkHttp/Retrofit factory (long-timeout LLM client)
│   └── SpotifyApiClient.kt
├── auth/SpotifyAuthManager.kt   ← AppAuth PKCE flow, token refresh, persistence
├── data/                        ← Room
│   ├── AppDatabase.kt
│   ├── Entities.kt              ← albums, playlists, tracks, links, history
│   ├── Daos.kt
│   └── ViewMode.kt              ← grid/list/compact view modes
├── playback/                    ← experimental on-device librespot playback
│   ├── LocalPlaybackController.kt
│   └── LocalPlaybackService.kt  ← foreground media service
├── repository/                  ← data access used by the ViewModels
│   ├── DashboardRepository.kt   ← local + remote library, LLM scans, sync
│   ├── SpotifyRepository.kt
│   ├── PlaylistRepository.kt
│   └── SyncCancel.kt
├── ui/                          ← Compose screens and dialogs
│   ├── DashboardScreen.kt       ← root scaffold, tabs, Tools/Settings
│   ├── MainViewModel.kt         ← library, playlists, AI scans, queue
│   ├── LibraryViewModel.kt
│   ├── AddFromSiteDialog.kt     ← in-app browser (WebView) + page snapshot
│   ├── AiUrlScanDialog.kt       ← "Scan page with AI (albums)"
│   ├── ConcertsDialog.kt / ConcertsListDialog.kt
│   ├── AiAssistantDialog.kt / AiAssistantState.kt
│   ├── DuplicatesDialog.kt
│   ├── RetentionDialog.kt
│   ├── SpotifyCredentialsScreen.kt
│   └── …
├── utils/                       ← pure helpers (CSV, duplicates, queue planner, AI chat)
└── workers/                     ← WorkManager: RetentionWorker, QueueSwitchWorker
```

Native module (outside `android/`): `audio-librespot/` — Rust librespot core + Kotlin JNI bridge (`LibrespotBridge.kt` → `LibrespotEngine.kt` → `PcmPlayer.kt`), prebuilt `.so` files under `audio-librespot/src/main/jniLibs/<abi>/`.

---

## 🎮 Features

### Tabs

| Tab | What it does |
|---|---|
| **Library** | Album grid/list/compact views, search, rich filters (artist, title, year, genre, date added), sort, selection toolbar, album track sheet, stats bar |
| **Playlists** | Create/edit/delete Spotify playlists, add or remove albums, reorder, visibility toggle, playlist expiry (retention), local queue playlist |
| **Tools** | Stats, Add from site, Scan page with AI (albums), Concerts calendar, AI assistant, Playlist from Spotify search, Artist discography playlist, Find duplicate albums, Follow library artists |
| **Settings** | Media-server mode (server URL, sign-in, sync, push/pull), backup (auto-backup + subfolder), album grid size, sync photos, autohide bottom nav / search bar, CSV import & export, hidden (duplicate) albums, retention, sign out |
| **Player** | Now playing, queue, Web Playback SDK surfaces, experimental on-device librespot player |

### AI page capture (albums)

`Tools → Scan page with AI (albums)` opens a dialog where you paste a URL (or page text). Press **Open in browser and scan** to load the page in the app's own WebView (`AddFromSiteDialog`), then press **Scan this page for albums (AI)** to snapshot the rendered DOM. The captured text is posted to the media server (`POST /api/v1/llm/extract-albums`), which chunks long pages, extracts `(artist, album)` pairs with the local LLM and resolves them against Spotify. The URL typed in the dialog is preserved when the browser closes.

The same browser offers **Scan this page for concerts (AI)** for concert pages.

### Data modes

- **Local mode (default)** — everything on the device: Spotify PKCE login, saved albums synced into Room, CSV import/export, no server needed.
- **Media-server mode** — point the app at a reSpotIt server (`https://<host>:8000`), sign in with the server account, and sync/push/pull the library. Unlocks the AI assistant, AI page scans and concerts, because the LLM runs on the server.

---

## 🔐 Auth flow (PKCE)

```
User taps "Connect with Spotify"
        ↓
Chrome Custom Tab opens Spotify login
        ↓
User approves → Spotify redirects to:
  com.spotifydashboard.app://callback?code=XXX&state=YYY
        ↓
MainActivity (singleTask) catches the deep link → App resumes
        ↓
AppAuth exchanges code + verifier for access + refresh tokens
        ↓
Tokens stored on the device (encrypted app storage)
        ↓
Auto-refresh when the token expires (55 min cycle)
```

The PKCE verifier/state survive process death: they are written to disk before the browser opens, so an OS kill in the background does not break the callback.

---

## 🗺️ Data flow

```
Spotify Auth (PKCE)
      ↓
SpotifyApiService → GET /me/albums (paginated) + /artists genre enrichment
      ↓
SyncService → CSV stage → artwork download (Filesystem cache)
      ↓
Room: albums / playlists / tracks
      ↓
MainViewModel → Compose UI

Media-server mode:
  Server (FastAPI)  ⇄  DashboardApiService  →  Room (push/pull)
                              ↓
                    POST /api/v1/llm/extract-albums (AI page scan)
```

---

## 🧪 Tests & CI

```bash
cd android
./gradlew :app:testDebugUnitTest          # 16 JVM unit-test files
./gradlew :app:connectedDebugAndroidTest   # 3 instrumented Compose/Espresso tests (needs a device/emulator)
```

Unit tests cover the API contract (`DashboardLlmContractTest`, `SaveAlbumsRequestTest`, `AddTracksRequestTest`, envelope deserializers), data helpers (`CSVHelperTest`, `DuplicateFinderTest`, `ActiveQueuePlannerTest`, `QueueSnapshotTest`, `QueueSnapshotQueueTailTest`, `PlaylistListOptionsTest`, `PlaylistRetentionStoreTest`, `SyncHistoryTest`, `AiChatTest`, `ViewModeTest`, `PlaybackDeviceTest`).

CI (`.github/workflows/`):

| Workflow | Trigger | What it does |
|---|---|---|
| `tests.yml` | every push / PR | JDK 17 + Android 34: runs `:app:testDebugUnitTest`, assembles the debug APK, and asserts the merged manifest keeps a single OAuth callback handler; plus a non-blocking instrumented-test job on an API 34 emulator |
| `release.yml` | `v*` tags or manual | decodes the release keystore, runs `:app:assembleRelease`, publishes the APK to the GitHub release |

---

## 🔒 Security, CA trust & LAN access

- The app talks to a self-hosted media server over **HTTPS on the LAN** (for example `https://192.168.1.11:8000`). The server's certificate is usually issued by a private CA.
- `android/app/src/main/res/xml/network_security_config.xml` trusts the **system** and **user** CA stores and permits cleartext, so a user-installed CA (mkcert or your own) works out of the box:
  - Install the CA once on the phone: **Settings → Security → Install a certificate → CA certificate**, pick `rootCA.pem`.
  - Because the CA is long-lived, this survives every server-cert reissue and every app update.
- **Personal/CI builds:** `android/app/src/personal/res/` (gitignored) overlays a trust anchor for the owner's local CA — `res/raw/respotit_ca.pem` — so the app reaches the self-hosted HTTPS server with no user CA install. Public/CI builds omit that overlay.
- The certificate must cover the PC's LAN IP. Reissue the leaf with the current IP in the SAN list (`scripts/local-certs.sh <ip>` in the server repo) — no app rebuild needed.
- **Firewall/port:** allow inbound TCP on the chosen port, and forward Docker's published port to the LAN if it is bound to loopback only (`0.0.0.0:8000 → 127.0.0.1:18000` via `netsh interface portproxy`).
- Credentials, tokens and keys are never written to logs or committed: `keystore.properties`, `*.keystore`, `.env` and `src/personal/` are gitignored.

---

## 🩺 Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "Server unreachable" / media-server actions fail | Phone and PC are on different networks, or the PC firewall blocks the port | Confirm both are on the same Wi-Fi; `adb shell nc -w 3 <pc-ip> 8000` from the phone; add a firewall rule for the port |
| `SSLHandshakeException` / cert warning | The server cert does not cover the LAN IP, or the CA is not trusted on the phone | Reissue the leaf with the LAN IP in the SAN list; install the CA as a user certificate (or build with the `src/personal/res` overlay) |
| AI scan spins for minutes | Album extraction is synchronous and chunked server-side; a local LLM on CPU is slow | Wait it out — the client uses a 15-minute read timeout for LLM calls. Check the server logs for `/api/v1/llm/extract-albums` |
| AI scan never reaches the server | The phone cannot open the port | See the first two rows; verify with `adb logcat \| grep OkHttpClient` that the POST is sent |
| Login returns to the app but nothing happens | The OAuth deep link was intercepted by another app | Ensure nothing else registers `com.spotifydashboard.app://callback`; `MainActivity` is the single owner by design |
| App killed in the background while logging in | Low-RAM device, OS reclaimed the process | The PKCE state is persisted before the browser opens, so re-opening the app completes the flow |
| Empty library after a sync | The Spotify account has no saved albums, or the sync was cancelled | Open the sync sheet for the stage log; check `Settings → Media server → Sync` |
| Album page scan returns nothing | The page renders its list lazily | Scroll the page before snapshotting, or use **Scan** to extract the links that are already present |
| Layout buttons hidden behind the scroll area | Older Compose sizing on small screens | Dialog actions are pinned outside the scroll area; update to the latest build if the action row is still clipped |

---

## 🏷️ Versioning & changelog

- Release tags in this repo: `0.1.0`, `v0.1.0`, `v0.1.1`, `v1.0.0`, `v1.1.0`.
- Tagging `v*` runs `release.yml` and publishes a GitHub release with the APK.
- `versionName` / `versionCode` live in `android/app/build.gradle`.
- Per-feature notes live in the commit history (`git log --oneline`) and in the server repo's `EXPERIMENTAL_FEATURES.md`.

---

## 📦 Repository layout

```
android/                  ← the Gradle project (tracked, 124 files)
├── app/                  ← application module (Compose UI, Room, Retrofit)
├── gradle/wrapper/       ← Gradle 8.2.1 wrapper
└── settings.gradle       ← includes :app, :audio-librespot, capacitor plugins
audio-librespot/          ← Rust librespot core + Kotlin JNI bridge (module :audio-librespot)
android-patches/         ← reference copies of the manifest/MainActivity/network config
public/, index.html       ← web landing page (not part of the app build)
docs/                    ← landing-page assets
```

> **Legacy leftovers:** `package.json`, `capacitor.config.json`, `vite.config.ts`, `tsconfig.json` and `android/app/src/main/assets/public/` came from the earlier Capacitor prototype. This app is fully native — the app does **not** load a WebView bundle, and those files are not part of the Gradle build.

---

## 📋 Notes

- `/api/v1` is a frozen contract: additive changes only.
- Playlist deletion is always explicit and confirmed; hiding a duplicate is reviewable and reversible.
- No Spotify logo is used anywhere — the app is branded reSpotIt.
- The `audio-librespot` module is experimental: seek accuracy, gapless playback and background audio focus still have rough edges, and full tracks require Spotify Premium. The first play opens Spotify's consent page with the librespot desktop client id; the reusable credentials are stored in private SharedPreferences and later starts connect silently.
