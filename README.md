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
- [3. Build and install the Android app](#3-build-and-install-the-android-app)
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
cp .env.example .env      # optional; defaults point at http://localhost:8000
npm run dev
```

Open <http://localhost:5173>.

Other scripts: `npm run build` (typecheck + production bundle to `dist/`),
`npm run preview` (serve the built bundle), `npm run lint`.

---

## 3. Build and install the Android app

### Point the app at your backend

The phone cannot reach your laptop on `localhost`. Pick the right host:

| Where the app runs | Backend URL to use |
|---|---|
| Android **emulator** | `ws://10.0.2.2:8000` (the emulator's alias for your host) |
| **Real phone** on the same Wi-Fi | `ws://<your-computer-LAN-IP>:8000`, e.g. `ws://192.168.1.42:8000` |
| Production | `wss://watchwire.example.com` |

Find your LAN IP with `ipconfig` (Windows) or `ifconfig | grep inet` (macOS/Linux).

You can bake the URL in at build time:

```bash
cd android
./gradlew :app:assembleDebug -PbackendWsUrl=ws://192.168.1.42:8000
```

…or leave the default and **change it at runtime**: the app's pairing screen has a
`Server: … (tap to edit)` link. This is the easiest path — build once, retarget freely.

### Build

```bash
cd android

./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk    (~101 MB)
./gradlew :app:assembleRelease    # → app/build/outputs/apk/release/app-release-unsigned.apk (~44 MB)
```

> Windows note: use `gradlew.bat` from `cmd.exe`/PowerShell, or `./gradlew` from Git Bash.

The debug APK is larger because it also bundles the `x86_64` native libraries so it runs on
the emulator. The release APK ships only `armeabi-v7a` + `arm64-v8a`. OpenCV's native library
accounts for nearly all of the size.

If Gradle can't find your SDK, create `android/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

(That file is machine-specific and intentionally git-ignored.)

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it (you'll need to allow "install from unknown
sources"). The web app's **Download Android APK** button on the login page is wired to
`VITE_APK_URL` for exactly this — point it at wherever you host the built APK.

### Signing a release APK

`assembleRelease` produces an *unsigned* APK. To sign it:

```bash
keytool -genkey -v -keystore watchwire.jks -keyalg RSA -keysize 2048 -validity 10000 -alias watchwire

"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign \
  --ks watchwire.jks \
  --out watchwire-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

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

Generate a good secret:

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### Web — `web/.env`

| Variable | Default | Description |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8000` | Backend REST base URL |
| `VITE_WS_BASE_URL` | `ws://localhost:8000` | Backend WebSocket base URL (`wss://` in production) |
| `VITE_APK_URL` | `/watchwire.apk` | Target of the **Download Android APK** button |

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

**`SDK location not found`**
Create `android/local.properties` with `sdk.dir=/path/to/Android/sdk`.

**Gradle can't download its distribution**
The wrapper fetches Gradle 8.7 on first run. Behind a restrictive proxy, either install
Gradle 8.7 manually and run `gradle` instead of `./gradlew`, or drop the distribution zip
into `~/.gradle/wrapper/dists/gradle-8.7-bin/<hash>/`.
