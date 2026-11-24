# Release readiness

There is no plan to distribute this. The goal is to be in a position to decide
later without having to go back and undo anything.

That splits the work in two, and the split matters because the halves have very
different value:

**Engineering quality** is worth doing now. It is also the part that carries
over. Several other app ideas are likely to follow, so the patterns established
here get reused: the exact money type, the key wrapping, the backup envelope, the
build and signing setup, the generated palette with contrast checks, the testing
approach, the repo conventions that keep real financial data out of git. Getting
those right once pays off repeatedly.

**Publication paperwork** is worth deferring. Privacy policy, Data Safety
answers, store listing assets, developer verification and the 25 dollar fee are
all a day's work at the point a decision is actually made, and none of it
influences how the app is built. Doing it now would be wasted if the answer
turns out to be no.

The sections below are ordered on that basis rather than as one flat checklist.

## Done

- **Encrypted export and import.** The only route data has off the device, and
  what makes a lost signing key or a new phone survivable rather than fatal.
- **Signed release build.** 4096-bit RSA, valid 30 years, v2 and v3 signature
  schemes. Verified with `apksigner`.
- **App Bundle output.** `bundleRelease` produces the AAB that Play wants.
  7.9 MB, against 11 MB for the APK and 42 MB for the debug build.
- **Minification and resource shrinking** enabled and building cleanly through
  R8 with Room, Hilt and SQLCipher present.
- **`lintVitalRelease` passes.** That is the subset of lint that blocks a Play
  upload.
- **Zero compiler warnings.**
- **Targets a current API level.** compileSdk and targetSdk 37, Android 17.
- **Three permissions, all security related.** `USE_BIOMETRIC` and
  `USE_FINGERPRINT` from `androidx.biometric`, and `HIDE_OVERLAY_WINDOWS` for
  tapjacking defence. All normal protection level, none prompt the user, and none
  grant access to anything. No internet, no storage, no location. Corrected from an earlier claim of none at all: adding the biometric
  library introduced these without being asked, which is exactly why the audit
  script reads the merged manifest rather than the one in source.
- **Cloud backup disabled**, both via `allowBackup=false` and explicit data
  extraction rules, so financial data cannot leave the device through Android's
  own transfer mechanisms.
- **Encrypted at rest** with SQLCipher, key wrapped by the hardware Keystore
  and by a passphrase.
- **Biometric unlock**, offered automatically when a fingerprint, face or device
  PIN is enrolled, with the passphrase always available as a fallback.
- **Two-step unlock, finished.** Authenticator enrolment with a QR code,
  verification before the secret is stored, eight single-use recovery codes, and
  a settings screen. Either factor, both, or neither. The gates are satisfied
  before the database opens, not after.
- **Screenshot blocking** in release builds, off in debug so layouts can be
  shared.
- **Adaptive layout** for both the cover and inner displays.
- **Contrast verified** against WCAG AA across both themes, worst case 4.59.
- **Bounded parsing of untrusted files.** Size, row, column and field limits on
  CSV import; bounded decompression on backup import so a compression bomb cannot
  exhaust memory. Both the reachable attack surface of an app with no network.
- **Passphrase fields hardened.** Password keyboard type so the IME does not
  learn or autocorrect the passphrase, overlay hiding on Android 12 and above, and
  obscured-touch rejection below it.
- **Static security audit**, scripted and repeatable. Exported components and
  permissions from the merged manifest, application flags, bundled secrets, dex
  string scanning, and every resolved dependency checked against the OSV
  advisory database. Currently clean across 150 dependencies. See
  docs/security-audit.md.

## Engineering quality, worth doing regardless

These are the items that would be worth fixing even if this app is never
distributed, and whose patterns carry to the next project.

- [ ] **Run the release build on hardware.** It has been assembled but never
      launched. R8 stripping something reached only by reflection does not show
      up in a debug build, and this app uses Room, Hilt and SQLCipher, all of
      which are reflection-adjacent. Highest risk item on this list by a
      distance.
- [x] **A way to get the data out.** Done. Encrypted export and import, format
      documented in `BackupEnvelope`. AES-256-GCM, PBKDF2 at 600,000
      iterations, gzipped JSON payload, authenticated header. Written and read
      through the Storage Access Framework, so files can go to Proton Drive,
      Google Drive or local storage with no integration code. 20 tests,
      including a full round trip through a real database.
      Still to verify on a device: that the file picker behaves as expected and
      that a file written on the phone restores on the phone.
- [ ] **Externalise the UI strings.** Everything is hardcoded in Kotlin rather
      than `strings.xml`. Works fine in English, blocks translation, and is not
      how a shipping app is built.
## Publication paperwork, deferred until there is a decision

None of this changes how the app is built. Listed so it is known rather than
discovered late.

- [ ] **Privacy policy** at a stable URL. Play requires one for anything
      handling financial information. The honest version is short, since nothing
      leaves the device.
- [ ] **Data Safety declaration** on the Play Console. The answers are currently
      all "collected: no", which stays true only while there is no network call.
- [ ] **Developer verification.** Identity check plus the 25 dollar one-time
      Console fee. Signing and sideloading need neither.
- [ ] **Decide on Play App Signing**, which determines whether a lost keystore is
      recoverable.
- [ ] **Store listing assets.** The 512x512 icon exists. Feature graphic,
      per-form-factor screenshots and descriptions do not.

### Harden the lock to be cryptographic rather than application level

The biometric prompt currently gates the app, not the key. The Keystore key that
wraps the database key is not created with `setUserAuthenticationRequired`, so
the unlock is enforced by this app's own code rather than by the secure element.

That stops someone picking up an unlocked phone and reading balances. It does not
stop someone who can run code as this app.

Binding the key to authentication is strictly stronger: the unlock has to go
through a `CryptoObject` so the Keystore itself refuses to release the key
without a verified user. It was not done blind because getting it wrong locks the
user out of their own data, and it needs testing on hardware. The recovery path
already exists, since a passphrase unlock re-wraps the key, so this is a
contained piece of work rather than a risky one.

## Nice to have

- [ ] **Semgrep for source-level patterns**, and Gradle dependency verification
      so a swapped artifact cannot slip in silently. Both cheap, neither urgent.
- [ ] **Instrumented tests run regularly.** They exist for the Keystore but
      cannot run on the dev server, which has no KVM. They pass or fail on the
      laptop and nowhere else.
- [ ] **Baseline profile** for startup performance. Measurable win on a cold
      start and cheap to generate.
- [ ] **`applicationIdSuffix` on debug**, so debug and release can coexist on
      one device. Deliberately deferred because it orphans the existing test
      install.
- [ ] **Accessibility pass.** Content descriptions, touch target sizes,
      behaviour at large font scales. Nothing has been checked.
- [ ] **Decide about crash reporting.** Useful, but every option sends data off
      the device, which cuts against the whole premise. Leaning towards none,
      with the mapping file kept so a manually shared stack trace can be read.
- [ ] **Confirm behaviour when the database cannot be opened.** The unlock path
      has recovery, but a corrupt database file is untested.

## Explicitly not doing

- **Analytics or telemetry of any kind.** The app's entire proposition is that
  the data stays put.
- **Dynamic colour.** Dropped in favour of a fixed identity.
- **Broker API integration.** Priced out at 100 dollars a month minimum through
  an aggregator, and it would mean credentials on the device. The weekly CSV
  import is both cheaper and a better fit.

## Two-step unlock: what it is and is not

Both unlock factors are optional and can be combined. Nobody should be forced to
enrol a fingerprint to use a finance app, and nobody who prefers the sensor
should have to keep an authenticator to hand.

| Policy | Required to open |
|---|---|
| Neither | Passphrase |
| Biometric | Fingerprint, face or device PIN |
| Authenticator | A six digit code |
| Both | Fingerprint and a code |

The passphrase is deliberately not one of the selectable factors. It is the thing
that actually unwraps the encryption key, and it stays available because without
it a lost phone or a wiped secure element would mean the data is gone for good.

### Be honest about the strength

TOTP cannot be a second factor in the cryptographic sense here, and the UI should
not imply otherwise.

A six digit code carries roughly 20 bits of entropy and rotates every 30 seconds,
so it cannot derive or protect an encryption key. Verifying codes locally requires
the shared secret to be stored on the device, which means anyone who can read this
app's private storage can generate valid codes themselves.

So both biometrics and TOTP are gates in front of the app rather than locks on the
data. They stop someone who is holding your unlocked phone. They do not stop
someone who can run code as this app. That is still worth having, and for a lot of
people it is the threat that actually matters, but it is a different claim from
what a bank means by two-factor authentication.

The honest ranking of what protects the data:

1. **SQLCipher with a key wrapped by the hardware Keystore.** The real protection.
2. **The passphrase**, which derives a key through PBKDF2 at 600,000 iterations.
3. **Biometric and TOTP gates**, which protect against opportunistic access.

Moving biometrics from 3 to 1 is the hardening already noted above: create the
Keystore key with `setUserAuthenticationRequired` so the secure element, not this
app's code, enforces the check.

### Lost authenticator

Enrolling produces eight single-use recovery codes, stored as salted SHA-256
hashes. Without them a lost authenticator would mean a lockout, and with the
passphrase silently bypassing TOTP the factor would be meaningless for anyone who
chose it deliberately.
