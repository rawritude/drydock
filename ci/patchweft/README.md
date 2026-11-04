# Patchweft validation for Drydock

Drydock's revision validation runs through [Patchweft](https://github.com/rawritude/patchweft)
the same way the other projects' lanes do: immutable revisions, explicit compositions and
shared execution with recorded evidence. GitHub Actions stays the public CI and release path;
this directory is how an agent or operator validates a revision on a Patchweft host.

## Shape

- `profile.py` generates the host-specific profile. Profiles bind exact tool bytes and absolute
  paths, so one file per host; it resolves `java`, `bash`, the Android SDK and a persistent
  Gradle home, and refuses to emit a profile whose tools are missing.
- `drydock-inputs.json` declares what each job reads (`reuse: declared`): revisions that only
  touch Markdown, `LICENSE` or `.github/**` reuse a passing observation instead of rerunning.

| Job | Command | Reservation |
| --- | --- | --- |
| `test-lint` | `./gradlew testDebugUnitTest lintDebug --no-daemon` | 8 slots, 8 GiB, 30 min |
| `assemble` | `./gradlew assembleDebug assembleRelease --no-daemon` | 8 slots, 8 GiB, 30 min |

Jobs run with cleared environments and private `HOME`/`TMPDIR`; the profile passes everything
Gradle needs explicitly (`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`).
All attempts share the `--gradle-home` you generate with, so Gradle distributions, dependency
caches and the build cache survive across runs. The profile is exploratory (`certifying:
false`): it produces evidence, it does not advance a Patchweft frontier.

## Run it on this host

Declared reuse (`inputs_file`) needs a Patchweft binary built from current source; older
installs reject the field at registration. Build one with `cargo build --release --locked` in
the Patchweft checkout, or switch the jobs to the default `inflight` policy and drop
`inputs_file` before generating.

Import captures committed source only — commit first. One host, one binary, one store; every
executor that runs a profile's jobs must hold the identical tools at the same paths.

```bash
STATE=$HOME/.local/share/drydock/patchweft-state

patchweft --state "$STATE" init --slots 8 --memory-mb 12288
patchweft --state "$STATE" import --name drydock --repo "$PWD" --owner "$USER"

python3 ci/patchweft/profile.py \
    --java "$JAVA_HOME/bin/java" \
    --android-home "$ANDROID_HOME" \
    --gradle-home "$HOME/.gradle" > /tmp/drydock-profile.json
patchweft --state "$STATE" register /tmp/drydock-profile.json

# Use the revision and profile ids the previous commands printed.
patchweft --state "$STATE" submit --revision REV --profile PROF --owner "$USER"
patchweft --state "$STATE" worker --drain --no-loopback-check
patchweft --state "$STATE" status CANDIDATE
patchweft --state "$STATE" logs ATTEMPT      # when a job fails
```

`--no-loopback-check` is for a laptop whose loopback services may be reached by jobs; a worker
on a dedicated host runs without it.

## Shared service

The shared Patchweft store already used by the pw-ng lanes (service on mauve, credentials in
`~/.config/patchweft/`) can validate Drydock the same way: register the profile generated for
the executor host, grant the lane's principal access to the Drydock project, and drive
imports/submits through `patchweft remote --endpoint ... --credential-file ...`. See
`docs/connections.md` in the Patchweft repository. The profile's `tools` paths must exist on
every executor that will claim the work.

## Changing the jobs

A profile's job set is fixed at registration: edit `profile.py`, regenerate, and register a new
profile. Submit the revision again; `submit --retain-from PREVIOUS_CANDIDATE` keeps passes whose
job identity is unchanged (for example a pure reservation increase). Changing a command or the
environment is a new identity and reruns.
