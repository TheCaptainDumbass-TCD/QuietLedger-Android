# Security auditing

```bash
./scripts/security-audit.sh release
```

Runs against a built APK and exits non-zero if anything is flagged.

## What it checks, and why each one is there

**Exported components in the merged manifest.** The most valuable check, because
libraries add components you never wrote and manifest merging is invisible in
your own source. It found `androidx.profileinstaller.ProfileInstallReceiver`,
which is exported, and which nobody declared on purpose.

**Permissions in the merged manifest.** Same reasoning. Adding
`androidx.biometric` silently introduced `USE_BIOMETRIC` and `USE_FINGERPRINT`.

**Application flags.** Backup, debuggable, cleartext traffic. A release build
that is debuggable, or one that permits cleartext, is a real finding.

**Bundled files.** Whether a keystore, a `.properties` file, a broker CSV or a
`.qlbk` backup has accidentally been packaged.

**Secret-shaped strings in the dex.** API key and private key patterns.

**Dependency advisories.** Every resolved dependency queried against the OSV
database, which is the same data source Google's own tooling uses.

## Current result

Nothing flagged. 150 resolved dependencies, no known advisories.

Two things it reported that are expected rather than wrong:

- `MainActivity` is exported, because it is the launcher.
- `ProfileInstallReceiver` is exported but protected by
  `android.permission.DUMP`, which only the shell and system hold. Benign.

## A bug this found in itself

The first version used a line-based `grep` for permissions and reported "none
requested" while three were present. `apkanalyzer` prints each attribute on its
own line, so the pattern never matched.

That is worth recording because it is the worst failure mode for a security
tool: a confident all-clear that is simply wrong. The check is now parsed rather
than grepped. The lesson generalises, which is why it is written down: verify a
security check against a case you know should fail, or you have no evidence it
works.

## MobSF, self-hosted

Installed and working:

```bash
python3 -m venv ~/mobsf-venv && ~/mobsf-venv/bin/pip install mobsf
~/mobsf-venv/bin/mobsf          # prints the REST API key, listens on :8000

MOBSF_API_KEY=<key> ./scripts/mobsf-scan.sh release
```

From PyPI rather than by cloning the repository. The package needs no Docker
and no root.

### The important caveat: MobSF reported a clean result it had not earned

The first APK scan returned 73/100, zero high severity, and **zero code analysis
findings**. The log explained why:

```
[ERROR] Executable/Library Tampering Detected
[ERROR] Decompiling with JADX failed
[INFO]  Code Analysis Started on - java_source     <- an empty directory
```

MobSF downloads jadx itself, then its own integrity check rejects the binary it
just fetched, because that jadx build is not in its known-hashes list. Code
analysis then runs over nothing and finds nothing.

So the score was computed without any code having been read. Taken at face value
it would have been a false all-clear. jadx itself is fine: run by hand on the same
APK it decompiled 3,358 files without complaint.

The workaround is to upload the Kotlin **source** as a zip, which MobSF analyses
directly with no decompilation step. `scripts/mobsf-scan.sh` does both scans and
fails loudly if the APK scan reports no code findings, since that now means the
decompiler broke rather than that the code is clean.

### Findings, and the triage of each

APK scan, release build:

| Finding | Verdict |
|---|---|
| `ProfileInstallReceiver` exported, protected by a permission MobSF cannot resolve | Benign. `android.permission.DUMP` is `signature\|privileged`, so no third-party app can hold it. |
| "May contain hardcoded secrets" | False positive, all four traced to source. |
| No privacy trackers | Confirmed. |
| 0 network findings, 0 domains referenced | Confirmed by a tool with no stake in the claim. |

The "secrets" were traced rather than assumed:

- `0123456789ABCDEF0123456789abcdef` is a hex lookup table building `int[256]`
  arrays, found by decompiling with jadx directly.
- `bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc` is SQLite's
  own source identifier, inside `libsqlcipher.so`, next to the string
  `fts5: 2026-07-24 19:02:57`. It is what `sqlite_source_id()` returns and is
  published by SQLite.
- `!!""##$$%%&&''(())**++,,--..//00112233445566778899` and the long incrementing
  decimal run are character lookup tables, also inside `libsqlcipher.so`.

Source scan, three findings, all explained:

| Finding | Verdict |
|---|---|
| `android_hardcoded` in `TotpStore.kt` | False positive. Matched `KEY_SECRET = "totp_secret"` and similar, which are SharedPreferences key *names* and a Keystore alias, not secret values. |
| `android_write_app_dir` | Already satisfied. It asks that sensitive data written to app storage be encrypted; the database key and the TOTP secret are both AES-256-GCM wrapped by a hardware Keystore key before being written. |
| `android_logging` in `MainActivity.kt` | Benign. Exactly one `Log` call in the whole app, gated behind `BuildConfig.DEBUG`, logging screen width, height, density and size class. No financial data and nothing in release builds. |

### Debug builds are worth scanning separately

The debug APK scores 45/100 and raises two high-severity findings, both expected:
signed with the debug certificate, and `android:debuggable=true`.

It also flags one thing the release build does not: `PreviewActivity` from
`androidx.compose.ui:ui-tooling` is exported and unprotected. Verified as
debug-only, one occurrence in the debug manifest and none in release, because the
dependency is `debugImplementation`. Worth knowing that a debug build sitting on a
phone has an exported activity any app on the device can launch, which is another
argument for not leaving debug builds installed indefinitely.

## On the other tools in the usual lists

MobSF is the one genuinely useful tool in the usual "top ten free mobile
scanners" lists. It is maintained, self-hostable, and does thorough static
analysis of an APK or AAB. It is a reasonable thing to stand up later.

It is **not** installed here, for these reasons:

- It wants Docker, which is not on this box and needs root to install, or a
  Django app with roughly eighty Python dependencies.
- The findings it would add beyond this script are mostly in areas this app does
  not have: network security configuration, TLS pinning, WebView settings, API
  endpoints. There is no network code at all.

If MobSF does get stood up, the sensible route is Docker on a machine where
Docker already exists, pointed at the AAB.

Most of the other tools in those lists do not apply:

- **OWASP ZAP, Burp Suite, Nmap, Nikto, SQLMap** all test servers and APIs. This
  app has no backend and no network permission.
- **QARK and AndroBugs** are effectively abandoned.
- **Drozer** tests IPC and exported components. Worth a look if this app ever
  gains a content provider or a non-trivial exported component; today there is
  nothing to probe.
- **Frida** is a manual instrumentation tool rather than a scanner. Genuinely
  useful for attacking your own lock screen, which is a good exercise once the
  key is bound to user authentication.

## Worth adding later

- **Semgrep** with Kotlin and Android rules, for source-level patterns this
  script cannot see.
- **Gradle dependency verification**, pinning checksums so a compromised
  artifact cannot be swapped in silently.
- **OWASP MASVS** as a checklist. It is the standard these tools implement, and
  reading it directly is more useful than running three overlapping scanners.

## External verification: what each tool actually contributed

Three independent tools, deliberately not just the hand-rolled script. Each was
measured against something it should have caught, because a scanner that reports
nothing is indistinguishable from a scanner that is not working.

| Tool | Verdict |
|---|---|
| **Android Lint** | Best of the three. Found a real security gap the others missed. |
| **MobSF** | Useful for manifest, certificate, permissions, network, trackers. Its code analysis silently did not run. |
| **Semgrep** | Poor fit. Caught 3 of about 10 deliberate vulnerabilities in a Kotlin test file. |

### Android Lint found what the others did not

`DataExtractionRules`: the manifest set `android:dataExtractionRules`, which only
applies from Android 12. At minSdk 29 that leaves Android 10 and 11 relying purely
on `allowBackup="false"`, with no `fullBackupContent` rules behind it. Fixed by
adding `res/xml/backup_rules.xml` excluding every domain.

Neither MobSF nor the audit script noticed, because both read the manifest as
written rather than reasoning about what applies at which API level. Lint knows
Android version semantics; the others pattern-match.

Lint also surfaced genuine non-security problems: five unused legacy launcher
PNGs that the adaptive icon had already superseded, duplicate round icons, four
Compose functions with the `modifier` parameter in the wrong position, and a use
of `Configuration.screenWidthDp` that does not track the real window on
foldables, which is precisely the device this app targets.

38 issues down to 0.

### Semgrep was measured and found wanting

Run against a Kotlin file containing ten deliberate vulnerabilities, including a
hardcoded AWS key, a Google API key, MD5, SHA-1, DES, AES-ECB, insecure `Random`,
SQL string concatenation and a trust-all `TrustManager`:

| Ruleset | Caught |
|---|---|
| `p/default` | 3 of 10 |
| `p/kotlin` | 2 of 10 |
| `p/owasp-top-ten` | 2 of 10 |
| `p/security-audit` | 1 of 10 |
| `p/secrets` | 0 of 10 |

It missed the hardcoded AWS and Google keys entirely. The community rules are
weighted towards Python, JavaScript, Go and Java; Kotlin and Android coverage is
thin. A clean semgrep run on this codebase therefore means very little, and it is
not worth adding to the routine.

Worth re-testing occasionally, since rulesets improve. The test file approach is
the thing to keep: measure the tool before trusting its silence.

### Lint is now configured to fail on security findings

`app/lint.xml` raises the Security category to `error`, so a finding blocks the
build rather than scrolling past in a warning list. Deliberate exceptions are
listed there individually with reasons.

One of those reasons is worth repeating: lint's own `ObsoleteSdkInt` advice, to
merge `mipmap-anydpi-v26` into plain `mipmap-anydpi` at minSdk 29, **breaks the
build**. AAPT then cannot resolve `mipmap/ic_launcher`. Established by doing it,
failing, and reverting. Good advice in general, wrong for adaptive icon XML, and
ignored with that explanation rather than silently.

## Attack surface of an app with no network permission

"No INTERNET permission" was becoming a lazy answer. The app cannot open a socket,
but it still runs on a device that is permanently connected and shares that device
with other apps. The reachable surface, enumerated honestly:

| Surface | Status |
|---|---|
| **Files the app parses** | The real one. See below. |
| Other apps via IPC | `MainActivity` is exported because it is the launcher, but it reads nothing from the launching Intent, so there is no injection path. No deep links, no content provider, no exported service. |
| Overlay and tapjacking | Addressed, below. |
| Keyboard capture of the passphrase | Addressed, below. |
| Screenshots, recents, accessibility capture | `FLAG_SECURE` in release builds. |
| ADB and cloud backup extraction | `allowBackup=false`, plus extraction rules for both API bands. |
| Physical access and forensics | SQLCipher with a hardware-wrapped key. |
| Dependency supply chain | 150 dependencies checked against OSV. Gradle dependency verification still outstanding. |

### Parsing untrusted files was genuinely unbounded

Two importers take input the user chooses, and **the CSV path has no
cryptographic gate at all**: pick a file, it gets parsed. Both were written with
no limits, which means a hostile or merely malformed file could take the process
down with an `OutOfMemoryError`.

Fixed:

- **`CsvReader`** now caps total input at 8 MB, 100,000 rows, 512 columns per row
  and 64 KB per field, failing with `CsvTooLargeException` and a message a person
  can act on. A real Fidelity export is about 2.5 KB, so the headroom is three
  orders of magnitude. Six tests, each a denial-of-service attempt, including an
  unterminated quote that must terminate rather than run away.
- **`BackupEnvelope`** now reads the ciphertext and the decompressed payload with
  running limits rather than `readBytes()`. Gzip expands, and a few kilobytes can
  inflate to gigabytes. GCM authentication means a hostile backup has to be built
  by someone who knows the passphrase, which narrows it, but "here is a backup,
  the passphrase is X" is a plausible thing to be told. The limit is injectable so
  the mechanism is testable; the first attempt at that test exhausted the test JVM
  building the payload, which is its own small lesson.

### The passphrase field was not telling the keyboard it was a passphrase

`PasswordVisualTransformation` only hides the glyphs on screen. Without
`KeyboardType.Password` the IME was never told it was handling a secret, so it was
free to offer suggestions, autocorrect the input, and retain it in personalised
learning. Every passphrase field now uses a shared `PassphraseKeyboardOptions`
with the password keyboard type, autocorrect off and capitalisation off.

Autocorrect mattered beyond privacy: a keyboard quietly "fixing" a passphrase
produces a wrong-passphrase error with no visible cause.

### Overlay defence

An app drawing on top of this one can intercept taps, which is how tapjacking
works. Two layers now:

- `Window.setHideOverlayWindows(true)` on Android 12 and above, which asks the
  system to hide other apps' overlays entirely while this window is in front. This
  needs `android.permission.HIDE_OVERLAY_WINDOWS`, a normal permission granted at
  install with no prompt, which only ever reduces what other apps can do.
- `filterTouchesWhenObscured` on the decor view as the fallback below Android 12,
  which refuses taps arriving through an overlay.

Lint caught that the first attempt at this was missing the permission, which is
the second time tonight that `abortOnError` has paid for itself.
