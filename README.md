# WatchWire

Turn a spare Android phone into a motion sensor you can watch from any browser.

The phone runs motion detection **locally** using CameraX + OpenCV frame differencing and
sends only a tiny `{score, timestamp}` event over a WebSocket. **No camera frames are ever
recorded, stored, or transmitted** — the video never leaves the device.

```
Android Camera → Local Motion Detection → WebSocket → FastAPI Backend → WebSocket → React Web App
```

| Component | Stack | Location |
|---|---|---|
| Camera device | Kotlin, CameraX, OpenCV, Compose, Foreground Service | [`android/`](android/) |
| Relay server | Python, FastAPI, WebSockets, in-memory sessions | [`backend/`](backend/) |
| Remote viewer | React, TypeScript, Vite | [`web/`](web/) |

---

## Table of contents

- [How it works](#how-it-works)
- [Prerequisites](#prerequisites)
- [1. Run the backend](#1-run-the-backend)
- [2. Run the web app](#2-run-the-web-app)
- [3. Install the Android app](#3-install-the-android-app)
- [End-to-end test](#end-to-end-test)
- [Configuration reference](#configuration-reference)
- [Production deployment](#production-deployment)
- [Security notes](#security-notes)
- [Running the tests](#running-the-tests)
- [Troubleshooting](#troubleshooting)

---

## How it works

1. The Android app opens a WebSocket to `/ws/camera`. The backend immediately issues a
   short-lived, random 6-character **Client Code** (e.g. `9FCAY4`) which the phone displays.
2. You log into the web app and type that code. The backend pairs the two sockets — one
   camera to one viewer — and the code is consumed.
3. You tap **Start Monitoring** on the phone. A camera-type **foreground service** takes
   over the CameraX pipeline, so detection keeps running with the screen off.
4. Each analyzed frame is downsampled to 160×120, blurred, and compared against the previous
   frame with OpenCV `absdiff` + `threshold`. The fraction of changed pixels becomes a motion
   score in `[0, 1]`.
5. When the score crosses the sensitivity threshold (and the debounce window has elapsed) the
   phone sends a `motion_event`. The backend relays it to the paired browser **immediately** —
   there is no polling anywhere in the system.

**Session state is in-memory.** All of it lives behind `SessionStore` in
[`backend/app/sessions.py`](backend/app/sessions.py), which is the only place that touches
session data — swapping in Redis later means reimplementing that one class and nothing else.

---

## Prerequisites

| Tool | Version used | Needed for |
|---|---|---|
| Python | 3.12 | backend |
| Node.js | 20+ | web app |
| JDK | 17 or 21 | Android build |
| Android SDK | API 34 + build-tools | Android build |

The Android build downloads Gradle 8.7 automatically via the committed wrapper — you do
**not** need Gradle installed.

---

## 1. Run the backend

```bash
cd backend

python -m venv .venv
# Windows (Git Bash):  .venv/Scripts/python.exe -m pip install -r requirements.txt
# Windows (PowerShell): .venv\Scripts\Activate.ps1 ; pip install -r requirements.txt
# macOS / Linux:        source .venv/bin/activate  ; pip install -r requirements.txt

cp .env.example .env      # optional; sensible defaults work for local dev
```

Start it, bound to `0.0.0.0` so your phone can reach it over the LAN:

```bash
# Windows (Git Bash)
.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# macOS / Linux
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Verify: <http://localhost:8000/api/health> → `{"status":"ok"}`
Interactive API docs: <http://localhost:8000/docs>

**Default login is `admin` / `changeme123`.** Change it before exposing the server —
see [Configuration reference](#configuration-reference).

---

## 2. Run the web app

```bash
cd web
npm install
cp .env.example .env      # optional; the defaults need no configuration
npm run dev -- --host     # --host exposes it on your LAN so a phone can reach it
```

Open <http://localhost:5173>, or from a phone on the same Wi-Fi,
`http://<your-computer-LAN-IP>:5173`. The app derives the backend address from whichever
host served the page, so both work with no configuration.

Other scripts: `npm run build` (typecheck + production bundle to `dist/`),
`npm run preview` (serve the built bundle), `npm run lint`.

---

## 3. Install the Android app

### You do not need to rebuild the app to point it at your backend

The backend URL is **set inside the app at runtime** — the pairing screen has a
`Server: … (tap to edit)` link. So one APK works against any backend: install it once, tap
the server line, type your URL. There is a compiled-in *default* (`ws://10.0.2.2:8000`, the
emulator's alias for your host machine), but it is only a starting value.

Pick the URL for your situation — the phone can never reach your laptop on `localhost`:

| Where the app runs | Backend URL to enter |
|---|---|
| Android **emulator** | `ws://10.0.2.2:8000` (already the default) |
| **Real phone** on the same Wi-Fi | `ws://<your-computer-LAN-IP>:8000`, e.g. `ws://192.168.1.42:8000` |
| Production | `wss://watchwire.example.com` |

Find your LAN IP with `ipconfig` (Windows) or `ifconfig | grep inet` (macOS/Linux). Your
laptop's firewall must allow inbound port 8000, and the backend must be started with
`--host 0.0.0.0`.

### Install

**Easiest: download it from the web app, on the phone itself.**

1. Put an APK in the repo's `dist/` folder (the release build writes them to
   `android/app/build/outputs/apk/release/` — copy them across, or point
   `WATCHWIRE_APK_DIR` at that directory).
2. Start the web app with `npm run dev -- --host` so it's reachable from the LAN.
3. On the phone's browser open **`http://<your-computer-LAN-IP>:5173`** (e.g.
   `http://192.168.1.6:5173`) and tap **Download Android APK**, then open the downloaded
   file.

The backend serves the APK at `GET /download/apk` with the
`application/vnd.android.package-archive` content type, which is what makes Android offer to
install it. It picks `arm64-v8a` if present, then `armeabi-v7a`, then `universal` — and never
hands out the emulator-only `x86_64` build. The endpoint is intentionally unauthenticated:
it's the client you need *in order* to log in.

The web app infers the backend from whatever host served the page, so opening it by LAN IP
just works — no rebuild, no env vars. Override with `VITE_API_BASE_URL` / `VITE_WS_BASE_URL`
if the backend lives elsewhere.

**Alternative:** copy the APK across by USB/cloud and open it, or with adb:

```bash
adb install -r watchwire-arm64-v8a-release.apk
```

Then: open WatchWire → grant camera + notification permissions → tap `Server:` → enter your
backend URL → the pairing code appears.

**Which APK?** `arm64-v8a` covers essentially every phone made in the last decade. If it
refuses to install, use `universal`.

| APK | Size | Use it for |
|---|---|---|
| `arm64-v8a` | ~29 MB | **Almost certainly this one** — any modern phone |
| `armeabi-v7a` | ~23 MB | Older 32-bit-only devices |
| `x86_64` | ~61 MB | Android emulators |
| `universal` | ~137 MB | Installs anywhere; use if unsure |

OpenCV's native library accounts for nearly all of the size, which is why the app ships one
slim APK per CPU architecture instead of a single fat one.

To host the APK somewhere else instead (a GitHub Release asset, a CDN), point
`VITE_APK_URL` at it and the login page's button will use that.

### Building it yourself (optional)

```bash
cd android
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/
```

> Windows note: use `gradlew.bat` from `cmd.exe`/PowerShell, or `./gradlew` from Git Bash.

To bake in a different default backend URL: `-PbackendWsUrl=wss://your-host`.

If Gradle can't find your SDK, create `android/local.properties`:

```properties
# macOS / Linux
sdk.dir=/absolute/path/to/Android/sdk

# Windows — use forward slashes (or escaped \\ backslashes); a path with single
# backslashes is silently mangled by the .properties format and fails with
# "The filename, directory name, or volume label syntax is incorrect".
sdk.dir=C:/Users/you/AppData/Local/Android/sdk
```

(That file is machine-specific and intentionally git-ignored.)

### Signing a release build

Without a keystore, `assembleRelease` still succeeds but produces **unsigned** APKs, which
Android refuses to install. Create a keystore once:

```bash
keytool -genkeypair -v -keystore watchwire-release.jks \
  -alias watchwire -keyalg RSA -keysize 2048 -validity 10000
```

Then pass it to the build:

```bash
./gradlew :app:assembleRelease \
  -PwatchwireKeystore=/absolute/path/to/watchwire-release.jks \
  -PwatchwireKeystorePassword=... \
  -PwatchwireKeyAlias=watchwire \
  -PwatchwireKeyPassword=...
```

Put those four properties in `~/.gradle/gradle.properties` to avoid repeating them. **Never
commit the keystore or its passwords** — keep them outside the repository. Verify a build
with `apksigner verify --print-certs <apk>`.

---

## End-to-end test

With the backend and web app running:

1. **Launch WatchWire** on the phone and grant the camera (and notification) permission.
2. The phone shows a **6-character Client Code** and `Connected to server – waiting for pairing`.
   If it says `Disconnected – retrying…`, tap the `Server:` line and fix the URL.
3. **Open** <http://localhost:5173> and **log in** (`admin` / `changeme123`).
4. **Type the code** on the Connect page and press **Connect**.
5. Both devices flip to their monitoring views. The browser shows `Camera: Connected`.
6. Tap **Start Monitoring** on the phone. A persistent notification appears, and the browser's
   `Monitoring` badge turns green and reads **LIVE**.
7. **Wave your hand in front of the camera.** Within about a second the browser shows a large
   flashing red **Motion Detected!** banner with the score and timestamp, plays an alert tone,
   and appends the event to the history list.
8. **Press the phone's power button to turn the screen off.** Wave at the camera again —
   events keep arriving in the browser. This is the foreground service doing its job.

> **Audio:** browsers block sound until you interact with the page. Click **🔊 Enable Sound**
> on the monitoring page once, and alerts will play from then on. If you deny notification or
> audio permission entirely, the in-page banner and history still work — they never depend on
> either permission.

This exact flow is automated and passing. All 12 steps were verified against a real Pixel
emulator, the real backend, and the real web app in a headless browser — including new motion
events arriving in the browser while the device was provably asleep (`mWakefulness=Asleep`).

---

## Configuration reference

### Backend — `backend/.env`

All variables are prefixed `WATCHWIRE_`. See [`backend/app/config.py`](backend/app/config.py).

| Variable | Default | Description |
|---|---|---|
| `WATCHWIRE_ADMIN_USERNAME` | `admin` | Web app login username |
| `WATCHWIRE_ADMIN_PASSWORD` | `changeme123` | Web app login password — **change this** |
| `WATCHWIRE_JWT_SECRET` | random per start | HS256 signing key. **Set a fixed random value in production**, otherwise every restart invalidates all tokens |
| `WATCHWIRE_JWT_EXPIRE_MINUTES` | `720` | Access token lifetime |
| `WATCHWIRE_CORS_ORIGINS` | `http://localhost:5173,…` | Comma-separated allowed origins |
| `WATCHWIRE_CODE_TTL_SECONDS` | `300` | Pairing code lifetime before rotation |
| `WATCHWIRE_PAIRED_SESSION_GRACE_SECONDS` | `120` | How long a paired session survives a disconnect |
| `WATCHWIRE_SESSION_MAX_LIFETIME_HOURS` | `12` | Hard cap on any session |
| `WATCHWIRE_LOGIN_MAX_ATTEMPTS` | `5` | Failed logins before lockout |
| `WATCHWIRE_LOGIN_LOCKOUT_SECONDS` | `300` | Lockout duration |
| `WATCHWIRE_CORS_ALLOW_PRIVATE_LAN` | `true` | Also accept any private-LAN origin, so the web app works when opened by IP from a phone. Set `false` in production |
| `WATCHWIRE_APK_DIR` | `dist` | Directory searched for the APK served at `/download/apk` (relative paths resolve from the repo root) |

Generate a good secret:

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### Web — `web/.env`

| Variable | Default | Description |
|---|---|---|
| `VITE_API_BASE_URL` | *page host* `:8000` | Backend REST base URL. Defaults to the host that served the page, so opening the app by LAN IP from a phone works unchanged |
| `VITE_WS_BASE_URL` | *page host* `:8000` | Backend WebSocket base URL (`wss://` when the page is HTTPS) |
| `VITE_APK_URL` | `<API_BASE_URL>/download/apk` | Target of the **Download Android APK** button |

### Android

| Setting | Where | Description |
|---|---|---|
| `-PbackendWsUrl=…` | Gradle property at build time | Compiled-in default backend URL |
| `Server: … (tap to edit)` | Pairing screen | Runtime override, persisted on the device |
| Sensitivity slider | Monitoring screen | Higher = smaller movements trigger events |

---

## Production deployment

1. **Set a fixed `WATCHWIRE_JWT_SECRET`** and a real admin password.
2. **Terminate TLS in front of the backend** (nginx, Caddy, or a managed load balancer) and
   make sure it forwards WebSocket upgrade headers. Minimal nginx location block:

   ```nginx
   location / {
       proxy_pass         http://127.0.0.1:8000;
       proxy_http_version 1.1;
       proxy_set_header   Upgrade $http_upgrade;
       proxy_set_header   Connection "upgrade";
       proxy_set_header   Host $host;
       proxy_read_timeout 3600s;   # don't cut idle monitoring sockets
   }
   ```

3. **Point the clients at `https://` / `wss://`** — set `VITE_API_BASE_URL`, `VITE_WS_BASE_URL`,
   and build the APK with `-PbackendWsUrl=wss://your-host`. Over `wss://` the app's cleartext
   network-security exception is irrelevant, since that traffic is TLS.
4. **Restrict `WATCHWIRE_CORS_ORIGINS`** to your actual web origin.
5. Serve the web app's `dist/` from any static host.
6. Run the backend as a single process. Session state is in-process, so **multiple workers
   would not share pairings** — scale out only after moving `SessionStore` to Redis.

---

## Security notes

- **Passwords** are hashed with PBKDF2-HMAC-SHA256 (260k iterations, per-user salt). No
  plaintext password is ever stored.
- **Web endpoints and the client WebSocket require a valid JWT.** The camera socket is
  unauthenticated by design — a camera doesn't represent a logged-in user; pairing is what
  binds it to one, and it can't send events until a client has paired.
- **Client Codes** are 6 characters drawn from a 31-symbol unambiguous alphabet via
  `secrets.choice`, carry no information about the user or session, expire after 5 minutes,
  and are consumed on first successful pairing.
- **Reconnect tokens are role-scoped.** The camera and the viewer get *different* secrets, so
  a compromised web client can't reclaim the camera's socket and inject fake events.
- **Every inbound WebSocket message is validated** against a Pydantic discriminated union.
  Anything malformed gets an `error` reply and the connection stays up.
- **Login is rate-limited** per username+IP.
- **Camera frames are never stored or transmitted.** Only `{score, timestamp}` leaves the phone.
- A background sweep expires stale sessions, rotates unused codes, and cleans up after
  disconnects.

**Known MVP limitations:** accounts come from environment variables rather than a user
database; there's no refresh-token rotation or CSRF token (the JWT is held in `localStorage`
and sent explicitly, not as a cookie); the login rate limiter is per-process and in-memory.

---

## Running the tests

**Backend** — 17 tests covering auth, rate limiting, pairing, event relay, message validation,
the concurrent-pairing race, and disconnect/resume on both sides:

```bash
cd backend
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt   # once
.venv/Scripts/python.exe -m pytest -q                             # all tests
.venv/Scripts/python.exe -m pytest tests/test_flow.py -v          # one file
.venv/Scripts/python.exe -m pytest -k resume -v                   # by name
```

(On macOS/Linux, activate the venv and just use `pytest`.)

**Web** — typecheck, production build, and lint:

```bash
cd web
npm run build
npm run lint
```

**Android** — compile and lint:

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:lintRelease
```

---

## Troubleshooting

**Phone says `Disconnected – retrying…`**
The URL is wrong or unreachable. Tap `Server:` on the pairing screen and check it. Use
`10.0.2.2` on the emulator, your LAN IP on a real phone — never `localhost`. Confirm the
backend was started with `--host 0.0.0.0`, and that your firewall allows port 8000.

**You get signed out whenever you restart the backend**
Expected with the default config, and the app now tells you so instead of hanging. The JWT
secret is randomly regenerated on every start, which invalidates tokens already stored in
the browser. To keep sessions across restarts, set a fixed one in `backend/.env`:

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"   # put the result in WATCHWIRE_JWT_SECRET
```

**Backend won't start: `[Errno 10048] only one usage of each socket address`**
Port 8000 is already taken by an earlier backend. Find and stop it:

```powershell
Get-NetTCPConnection -LocalPort 8000 -State Listen | Select-Object OwningProcess
Stop-Process -Id <that-id> -Force
```

**`That code is invalid or has expired`**
Codes last 5 minutes and are single-use. Read the current code off the phone screen — it
rotates automatically.

**Web app shows `Camera: Disconnected` right after pairing**
The phone lost its socket. It reconnects automatically with backoff; watch its status line.

**No motion events**
Make sure you pressed **Start Monitoring** (the browser badge must read **LIVE**). Raise the
sensitivity slider — it can only be changed while monitoring is stopped. Very dark scenes
produce little frame-to-frame difference.

**No sound in the browser**
Click **🔊 Enable Sound** once. Browsers require a user gesture before audio can play.

**Monitoring stops when the screen turns off**
Check that the persistent notification is present. Some vendors (Xiaomi, Huawei, Samsung,
OnePlus) aggressively kill background apps — exempt WatchWire from battery optimization in
system settings. Note that monitoring intentionally does **not** survive a force-stop.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "signatures do not match"**
You're installing a differently-signed build over an existing one (e.g. release over debug).
Uninstall first: `adb uninstall com.watchwire.app`.

**`INSTALL_FAILED_NO_MATCHING_ABIS`**
Wrong CPU architecture for that device. Use the `universal` APK, or the `x86_64` one for an
emulator.

**`App not installed` when tapping the APK on the phone**
The APK is unsigned. A release build only gets signed if you pass the keystore properties —
see [Signing a release build](#signing-a-release-build).

**`SDK location not found`**
Create `android/local.properties` with `sdk.dir=/path/to/Android/sdk`.

**`The filename, directory name, or volume label syntax is incorrect` during the Android build**
Your `sdk.dir` in `android/local.properties` uses single backslashes. `.properties` files
treat `\` as an escape character, so `C:\Users\…` silently becomes `C:Users…`. Use forward
slashes (`C:/Users/…`) or double backslashes.

**Gradle can't download its distribution**
The wrapper fetches Gradle 8.7 on first run. Behind a restrictive proxy, either install
Gradle 8.7 manually and run `gradle` instead of `./gradlew`, or drop the distribution zip
into `~/.gradle/wrapper/dists/gradle-8.7-bin/<hash>/`.
