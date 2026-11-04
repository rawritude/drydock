# Drydock

[![License](https://img.shields.io/github/license/rawritude/drydock)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B%20(API%2029)-3ddc84)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff)](#)

A small, modern download manager for Android — built to be safe by default, fast on
flaky connections, and quiet when idle.

Drydock is a from-scratch Kotlin/Compose app. Its segmented, resumable engine is
**inspired by aria2's design** (`--split`, `--min-split-size`, control files,
checksum verification, auto-renaming) but shares no code with it.

> **Status: 0.1.0, early.** Builds clean (57 JVM tests, lint passes, release APK
> 2.7 MB), but it has not been validated on a physical device yet and there are
> no signed release artifacts. See [Known limitations](#known-limitations).

## Features

- **Segmented downloads** — files are split across up to 8 parallel connections
  (`min-split-size`-style guarding, just like aria2) and written into a single
  pre-sized part file with positioned writes. No merge pass, minimal I/O.
- **Safe resume** — every resume is guarded by ETag / Last-Modified validators,
  `If-Range`, and strict `Content-Range` validation. If the remote file cannot be
  proven unchanged, the engine restarts instead of stitching mismatched bytes.
- **Optional SHA-256 verification** — hashed in the same streaming pass that
  publishes the file; a failing file never becomes visible.
- **Zero storage permissions** — finished files are published to the shared
  Downloads collection via MediaStore (`IS_PENDING` until complete). `minSdk 29`.
- **No background services when idle** — the foreground service exists only while
  downloads are running; it stops itself when the queue drains.
- **Link capture (opt-in, off by default)** — when enabled, Drydock offers itself
  as a handler for `http(s)` links (the "send to browser" path) and captures the
  ones matching your glob patterns (`*.zip`, `https://cdn.example.com/*`, …).
  Everything else is handed straight back to your browser. No polling, no
  service, no overhead while off.
- **Wi-Fi-only mode, concurrency and connection limits** — a couple of sane knobs,
  nothing more.
- **Material 3 UI** — single screen, dynamic color, edge-to-edge, dark mode.

## Usage

- **Add a link** — tap **+**, paste a URL (there's a paste shortcut), optionally
  set a file name and an expected SHA-256, then tap **Add**.
- **From another app** — share a link to Drydock; the add sheet opens prefilled.
- **Watch links (capture)** — *Settings → Watch links*. While on, Drydock appears
  in the system handler list for `http(s)` links. Links matching your patterns
  are queued instantly; everything else is forwarded to your browser.
  Patterns are glob-style, one per line, matched against the whole URL:

  ```
  *.zip
  *.iso
  https://cdn.example.com/*
  ```

  An empty list captures everything (while the toggle is on); `#` starts a
  comment. *Allow insecure HTTP in captured links* is a separate explicit opt-in.
- **Controls** — pause / resume / cancel per row or all at once. Completed rows
  can be opened or removed from the list; the file itself is never deleted.
- **Settings** — Wi-Fi only, connections per download (1–8), concurrent
  downloads (1–4).

## Safety model

| Concern | Policy |
| --- | --- |
| Transport | HTTPS only by default. Plain HTTP requires an explicit per-download (or per-capture) opt-in and shows a warning. HTTPS→HTTP redirect downgrades are always blocked. |
| Redirects | Followed manually with a hard limit, scheme allow-listing, and downgrade protection. |
| Credentials in URLs | Rejected — there is no UI to review what would be sent where. |
| Resume integrity | Validators + `If-Range` + strict `Content-Range` checks; ambiguity → restart, never mix bytes. |
| File names | Sanitized (no traversal, no control characters, no reserved names) before anything touches the media store or the UI. |
| Compression | `Accept-Encoding: identity` is requested; non-identity `Content-Encoding` is refused so ranged writes are always exact. |
| Storage | App-private part files; only completed, verified files are published. |
| Telemetry | None. No analytics, no accounts, no network calls except the downloads themselves. |

### Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | Downloading. |
| `ACCESS_NETWORK_STATE` | Detect metered networks for Wi-Fi-only mode. |
| `POST_NOTIFICATIONS` | Progress/completion notifications on Android 13+ (optional — downloads work without it). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep downloads alive while they run. |

No storage, location, contacts, or any other permissions. Finished files are
written through MediaStore.

## Known limitations

- **Not yet validated on physical devices.** Unit/engine tests pass and both APKs
  build, but on-device behavior (MediaStore publish, foreground service under OEM
  battery savers, capture UX) still needs a real-device pass.
- **Android 15's 6-hour foreground-service limit** (`dataSync`) is not handled
  yet; extremely long transfers can be stopped by the system mid-flight. Planned:
  `Service.onTimeout()` with clean state persistence.
- **Expiring/signed URLs** — if a token expires mid-transfer, segment retries
  fail until you press retry; retry re-probes the original URL and recovers when
  the server hands out a fresh link.
- **Chunked/unknown-length downloads cannot resume** — pausing restarts them.
- **Release is unsigned** — no keystore or CI publishing yet; build locally.
- **Capture is best-effort** — OEM browsers and other download managers may take
  precedence in the system handler list.

## Building

Requirements: JDK 17, Android SDK platform 35 + build-tools 35.

```bash
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # unsigned release APK (R8, ~2.7 MB)
./gradlew testDebugUnitTest   # 57 JVM tests
./gradlew lintDebug           # Android lint (no errors)
```

## Continuous integration

- **GitHub Actions** — `.github/workflows/ci.yml` builds, tests and lints every push; tags
  (`v*`) cut a pre-release with a debug APK (`release.yml`).
- **Patchweft** — `ci/patchweft/` defines the project's validation profile (tests, lint and
  packaging jobs with declared inputs) for agent-run validation on a Patchweft host; see
  [`ci/patchweft/README.md`](ci/patchweft/README.md).

## Architecture

```
app/src/main/java/io/github/rawritude/drydock/
├── engine/    Pure Kotlin, JVM-testable, no Android imports:
│              SegmentedDownloader, HttpFetcher, SegmentPlanner, ResumePolicy,
│              ContentRange parsing, control-file model
├── data/      Room (history), DataStore (settings), part-file store,
│              MediaStore publisher
├── service/   DownloadCoordinator (queue, concurrency, persistence),
│              DownloadService (foreground keeper), Notifier
├── capture/   LinkCatcherActivity + LinkCaptureManager (runtime component toggle)
└── ui/        Compose screen, dialogs, theme
```

The engine writes each segment through `FileChannel` positional writes, so
workers never share a file pointer and a crash can at worst repeat an idempotent
overwrite of the same bytes. Progress counters are persisted to a control file
once per second; a process death loses at most that second.

## Testing

The engine suite runs against a local, range-capable raw-socket HTTP server and
covers: byte-for-byte segmented downloads, servers without range support,
unknown-length (chunked) streams, interrupted → resumed downloads,
validator-change restarts, transient 5xx retries, corrupted `Content-Range`
rejection, and the insecure-scheme gate. The remaining tests cover URL policy,
file-name sanitization, Content-Disposition parsing, resume decisions, and link
pattern matching.

## License

Apache-2.0. See [LICENSE](LICENSE).
