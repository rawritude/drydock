# Drydock

A small, modern download manager for Android — built to be safe by default, fast on
flaky connections, and quiet when idle.

Drydock is a from-scratch Kotlin/Compose app. Its segmented, resumable engine is
**inspired by aria2's design** (`--split`, `--min-split-size`, control files,
checksum verification, auto-renaming) but shares no code with it.

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

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools 35).

```bash
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest  # unit + engine integration tests
```

The engine test suite runs against a local range-capable HTTP server and covers
segmented downloads, resume after interruption, validator changes, retries,
protocol violations, and unknown-length streams.

## Architecture

```
engine/    Pure Kotlin, JVM-testable, no Android imports
           SegmentedDownloader, HttpFetcher, SegmentPlanner, ResumePolicy,
           ContentRange parsing, control-file model
data/      Room (history), DataStore (settings), part-file store,
           MediaStore publisher
service/   DownloadCoordinator (queue, concurrency, persistence),
           DownloadService (foreground keeper), Notifier
capture/   LinkCatch + runtime component toggling
ui/        Compose screen, dialogs, theme
```

The engine writes each segment through `FileChannel` positional writes, so
workers never share a file pointer and a crash can at worst repeat an idempotent
overwrite of the same bytes. Progress counters are persisted to a control file
once per second; a process death loses at most that second.

## License

Apache-2.0. See [LICENSE](LICENSE).
