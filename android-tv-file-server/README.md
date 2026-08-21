# TV File Server

A local-network file server for Android TV. It turns a set-top box — the reference target is
a Xiaomi Mi Box 3 (Android 8.0, quad-core 1.5 GHz, 2 GB RAM) — into a NAS for the household,
reachable from a browser, from macOS Finder, from Kodi and from anything that speaks FTP.

Everything runs on the device. Nothing is uploaded anywhere, and the app makes no outbound
network requests of its own.

---

## What it does

- **Web interface** — browse, upload by drag-and-drop (files *and* folders), download,
  download a selection as a ZIP, rename, move, copy, delete, and preview images, video,
  audio and text. Works on a phone; it is what an iPhone will use.
- **WebDAV** — mount the device read-write in macOS Finder, Windows Explorer, Kodi or rclone.
- **FTP** — for Kodi, VLC, FileZilla, Cyberduck and older TV apps.
- **Android TV screen** — start/stop toggle, the three connection URLs, the credentials, a
  QR code that logs a phone straight in, live transfer progress and free space per volume.
  Fully navigable with the remote; no touchscreen assumed anywhere.
- **Foreground service** — the server keeps running with the app closed and the screen off,
  and can restart itself after a reboot.
- **Bonjour / mDNS** — the share advertises itself, so it turns up on its own in the Finder
  sidebar and in network scanners.

---

## Client compatibility — read this before choosing a protocol

| Client | Use | Notes |
|---|---|---|
| Any browser (desktop, phone, tablet) | `http://<ip>:8080` | Full read/write. The best experience on iOS. |
| **macOS Finder** | **WebDAV**: Go ▸ Connect to Server ▸ `http://<ip>:8080/dav` | Native, read/write. Mounts as a normal disk. |
| macOS Finder over `ftp://` | ✗ not possible | Apple removed FTP mounting from Finder; this is not something the server can fix. |
| **iOS / iPadOS Files app** | **Web interface** (or a third-party WebDAV/FTP client) | The Files app's "Connect to Server" speaks **SMB only**. See below. |
| Windows Explorer | WebDAV: map `http://<ip>:8080/dav` | Enable WebClient; Windows prefers a non-8080 port in some configurations. |
| Kodi | WebDAV or FTP source | Both work; WebDAV seeks better. |
| VLC | FTP or HTTP | |
| FileZilla, Cyberduck, rclone | FTP or WebDAV | rclone: `rclone config` → `webdav`, vendor `other`. |
| Linux (GNOME Files, Dolphin) | `dav://<ip>:8080/dav` | |

### Why there is no SMB

The original requirement asked for native integration with the iOS Files app, which means
SMB — the Files app's "Connect to Server" accepts nothing else. **SMB2 is deliberately not
implemented here.** There is no lightweight embeddable SMB2 server for Android, and a
from-scratch implementation (SPNEGO/NTLMSSP authentication, session setup, tree connect, the
create/read/write/close command set, oplocks, notify) is thousands of lines of security
sensitive protocol code that could not be maintained or tested to the standard of the rest
of this project. Shipping a half-working SMB stack would be worse than shipping none.

What iOS gets instead: the web interface is built mobile-first, supports drag-and-drop and
multi-file upload with real progress, and can be added to the home screen. For Files-app
integration specifically, a WebDAV-capable third-party app (Documents by Readdle, FE File
Explorer, and others) mounts `http://<ip>:8080/dav` and then appears inside Files.

---

## Architecture

```
android-tv-file-server/
├── core/                    Pure Kotlin/JVM. No Android dependencies.
│   ├── http/                HTTP/1.1 server, multipart parser, ranges, chunked framing
│   ├── webdav/              WebDAV class 1 + 2 handler and lock manager
│   ├── ftp/                 RFC 959 + 2428 + 3659 server
│   ├── vfs/                 Virtual filesystem with path containment
│   ├── web/                 REST API, ZIP streamer, JSON writer
│   ├── auth/                Sessions, Basic auth, login throttling
│   └── transfer/            Progress accounting
└── app/                     Android TV front end
    ├── server/              Foreground service, server manager, NSD, notifications
    ├── ui/                  Leanback-adjacent TV screen, guided-step settings, QR
    ├── storage/             Volume discovery across TV firmware quirks
    └── assets/web/          The embedded single-page web interface
```

```
                    ┌──────────────── one process ────────────────┐
  browser ─HTTP────▶│                                             │
  Finder  ─WebDAV──▶│  HttpServer ──▶ Router ──┬─▶ WebDavHandler ─┼──┐
  Kodi    ─FTP─────▶│  FtpServer ──────────────┴─▶ WebInterface ──┼──┤
                    │                                             │  ▼
                    │  ServerManager ◀── ServerSettings           │ VirtualFileSystem
                    │       │                                     │  │
                    │       └──▶ FileServerService (notification, │  ▼
                    │            wake/Wi-Fi locks, Bonjour)       │ /internal /usb1 /sdcard
                    └─────────────────────────────────────────────┘
```

**Why `:core` is a separate, Android-free module.** It makes the whole protocol stack
testable on any JVM — no emulator, no SDK — which is how the 205 tests in this repository
run. It also keeps the protocol code honest: it cannot quietly reach for an Android API.

**Why no server framework.** Ktor's engines cost several megabytes of APK and a lot of heap
on a 2 GB device. NanoHTTPD spools each request body to a temporary file before the handler
sees it, so a 4 GB upload would need 4 GB of scratch space the box does not have. The HTTP
engine here is about 500 lines, streams both directions through one fixed buffer per
connection, and has no dependency beyond kotlinx-coroutines.

---

## Building

Requires JDK 17 and the Android SDK (compileSdk 34). No other setup.

```bash
cd android-tv-file-server
./gradlew :app:assembleDebug          # build the APK
./gradlew :core:test                  # run the protocol test suite
./gradlew :app:assembleRelease        # minified, resource-shrunk build
```

`:core:test` needs **no Android SDK** — it is a plain JVM module, so the protocol stack can
be tested on a build machine that has never seen an emulator.

### Installing on a Mi Box 3 (or any Android TV box)

```bash
# On the box: Settings ▸ Device Preferences ▸ About ▸ tap "Build" seven times,
# then Developer options ▸ USB debugging / ADB debugging = on.
adb connect 192.168.1.50:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p ch.genedis.tvfileserver -c android.intent.category.LEANBACK_LAUNCHER 1
```

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Bind the listening sockets, report the LAN address. |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS/Bonjour advertising. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep serving with the UI closed. |
| `POST_NOTIFICATIONS` | The status card on Android 13+. Refusing it only hides the card. |
| `RECEIVE_BOOT_COMPLETED` | Optional "start when the TV boots". |
| `WAKE_LOCK` | Stop a long upload being suspended mid-file. |
| `READ_EXTERNAL_STORAGE` | Read shared storage on API 26–32. |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+. A file server cannot work through scoped storage; its whole job is to expose the user's own files. |

**Android 11+ caveat on TV hardware.** Many TV builds ship without the
`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` settings screen. The app detects this, explains
where to look manually, and keeps working against its own app folder (which needs no
permission at all) so it is never completely dead. Enable *Share the app folder too* in
Settings if that is the only storage you can reach.

---

## Ports and defaults

| | Default | Change in |
|---|---|---|
| Web + WebDAV | `8080` | Settings |
| FTP control | `2121` | Settings |
| FTP passive data | `2130–2160` | source (`CoreConfig`) |
| WebDAV mount | `/dav` | source (`CoreConfig`) |

Ports below 1024 cannot be bound by a normal Android app, which is why the defaults are 8080
and 2121 rather than 80 and 21.

---

## Security

This is a **LAN appliance**, and it is honest about that:

- Traffic is **cleartext**. HTTP Basic credentials and FTP passwords cross the network in the
  clear. Run it on a network you trust; do not port-forward it to the internet.
- A password is generated on first run from an alphabet with no look-alike characters and
  shown on the television. Change it any time with *New password*, which also invalidates
  every open session and rotates the QR token.
- Failed logins are throttled per client address (10 per minute).
- Passwords are compared in constant time.
- Every path is canonicalised and checked against its storage root before use — including
  paths whose file does not exist yet — which blocks `..` traversal and symlinks pointing
  outside the shared area. There are tests for both.
- WebDAV XML is parsed with DOCTYPE and external entities disabled; a test asserts a crafted
  XXE payload cannot read a local file.
- Cookie-authenticated writes require a custom header, so a cross-site form post cannot
  trigger one. Basic-authenticated scripts are exempt because they are not cookie-driven.
- The FTP server refuses an active-mode data connection to any address other than the
  control connection's peer, closing the FTP bounce hole.
- Read-only mode and anonymous-read mode are both available for a shared household setup.

---

## Performance notes for 2 GB devices

- One 64 KiB buffer per connection; nothing is buffered whole. A 40 GB file transfers in
  constant memory.
- Connections are capped (24 HTTP, 8 FTP by default) and excess connections are refused with
  `503` rather than queued.
- Transfer progress is coalesced to four updates a second per transfer before it reaches any
  UI, so a fast LAN copy cannot flood the render loop.
- The ZIP endpoint stores rather than deflates content that is already compressed (video,
  images, archives), which on a 1.5 GHz CPU is the difference between saturating the network
  and being CPU-bound.
- Free-space queries happen on an IO dispatcher, never on the main thread.
- Release builds are minified and resource-shrunk.

---

## Tests

```bash
./gradlew :core:test
```

205 tests, all on the JVM. Notably the socket-level ones: the HTTP engine is driven by a
hand-written client so framing bugs cannot hide behind `HttpURLConnection`, the FTP server is
driven by raw `Socket` conversations, and the multipart parser is verified against a 3 MiB
payload pushed through a 64-byte window so every boundary straddles a refill.

The `:app` module has no instrumentation tests: its logic lives in `:core`, and what remains
is view wiring that an emulator test would assert less usefully than a five-minute run on
real hardware.
