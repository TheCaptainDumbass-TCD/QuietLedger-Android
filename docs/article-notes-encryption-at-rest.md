# Article notes: Encryption at Rest on Android

Source material for a post in the existing **Encryption in Transit and At Rest**
series on thecaptaindumbass.com, alongside the macOS FileVault and VeraCrypt
instalment.

These are notes and verified facts, not a draft. The writing is Dennis's.

Everything below is taken from working code in this repository, with file paths so
any claim can be checked rather than taken on trust.

## Why this is the interesting chapter of the series

The desktop entries in the series cover full disk encryption, where the operating
system does the work and the user turns it on. Android is a different problem:
full disk encryption is already on and the user cannot turn it off, which sounds
like the end of the story and is not.

Device encryption protects a powered-off phone. It does nothing once the phone is
unlocked and sitting on a desk, and it does nothing about one app reading
another's files on a compromised device. An app holding financial data has to
encrypt its own storage, with a key the app controls, and that is where the real
decisions live.

So the angle: **on Android, "the disk is encrypted" is the beginning of the
problem, not the answer to it.**

## The architecture, as built

Verified constants from `core/crypto/KeyEnvelope.kt`:

| Element | Choice |
|---|---|
| Database encryption | SQLCipher 4.19.0 (`net.zetetic:sqlcipher-android`) |
| Database key | 32 random bytes, 256 bit |
| Key wrapping | AES-256-GCM, 128 bit tag |
| GCM nonce | 12 bytes |
| Passphrase KDF | PBKDF2WithHmacSHA256, **600,000 iterations**, 16 byte salt |
| Hardware backing | Android Keystore, AES-256-GCM key |
| Blob format | version byte, explicit IV length, then ciphertext |

The structure worth explaining to a reader is the **two independent wrappings of
one key**:

1. The database key is generated once, at random, and never derived from
   anything. It is what SQLCipher actually uses.
2. That key is then wrapped twice. Once by a key held in the hardware Keystore,
   which is what makes unlock fast and ties the data to this specific device.
   Once by a key derived from the user's passphrase with PBKDF2.

Either wrapping can unwrap the database key. That is the whole design:

- The Keystore path gives you a phone that opens quickly and whose data is
  useless if the storage is lifted off the device.
- The passphrase path is the escape hatch. Secure elements invalidate keys. A
  factory reset, a fingerprint being re-enrolled, or a system update can all
  destroy a Keystore key. If that were the only wrapping, the data would be gone.

That is a point worth labouring in the article, because "hardware backed" is
marketed as a pure upgrade and the failure mode is rarely mentioned: **a key the
hardware will not give back is indistinguishable from a key that never existed.**

## The mistake, which is the actual story

This is the part worth writing, because it was a real bug that reached a real
device and the reason it happened generalises.

**What was written.** The wrapping code initialised the cipher with a
`GCMParameterSpec` carrying a nonce the app had generated. That is textbook
correct AES-GCM, and it is what every tutorial shows.

**What happened on the phone.** Setup failed immediately with

```
Caller-provided IV not permitted
```

**Why.** The Android Keystore refuses to let you supply the IV when encrypting
with a Keystore-held key. It generates its own and you read it back off the cipher
afterwards. Supplying one is forbidden precisely because nonce reuse destroys GCM,
and the Keystore will not trust the caller not to do that.

The asymmetry is the trap: **decryption does take the stored IV.** So the encrypt
and decrypt paths are not mirror images, which is exactly the shape of API nobody
expects, and the code looked right.

**Why the tests did not catch it.** Every one of the 71 unit tests at the time ran
on the JVM, where `AndroidKeyStore` does not exist. The logic around the key was
tested thoroughly. The thing that actually stores the key was never tested at all.

That is the transferable lesson, and it is not really about cryptography:

> A test suite that cannot reach the platform will keep passing while the
> product is broken. Anything touching the Keystore, biometrics, native
> libraries or real file storage needs a test on a device, because the parts you
> cannot test locally are exactly the parts with surprising rules.

**Second fault, worth including.** When key creation threw, the app routed the
user to a lock screen with an Unlock button. There was no key to unlock, so the
only possible outcome was a second error and no way back. A failed setup has to
return to setup. The fix made "no key exists yet" a distinct exception type rather
than a generic failure, so nothing can confuse *not set up* with *cannot be
opened*.

**The fix, and what it added.** Encryption now lets the Keystore generate the
nonce and stores what it produced. The blob gained a version byte and an explicit
IV length rather than assuming twelve bytes, because assuming the Keystore's nonce
size would have been the next latent bug. Thirteen JVM tests on the wrapping
logic, including tamper detection and malformed input, plus eleven instrumented
tests against the real Keystore.

## The backup format, a second section if the post has room

Encryption at rest is not only the live database. The data has to be able to leave
the device, or a lost signing key means an uninstall and an uninstall means the
data is gone.

Format in `data/backup/BackupEnvelope.kt`:

```
offset  size  field
0       4     magic "QLBK"
4       1     format version
5       4     PBKDF2 iteration count, big endian
9       16    salt
25      12    GCM nonce
37      ..    AES-256-GCM ciphertext, 16 byte tag appended
```

Three decisions worth explaining:

- **The whole 37 byte header is passed to GCM as additional authenticated data.**
  Without that, an attacker could rewrite the stored iteration count down to 1 and
  the file would still decrypt. Authenticating the header makes the parameters
  tamper evident.
- **The iteration count is stored, not assumed.** It can be raised in future
  without making every existing backup unreadable.
- **The payload is gzipped JSON, not a copy of the database file.** It survives
  schema changes, and nothing has to write a decrypted database to storage in
  order to produce it, so there is no window where plaintext financial data exists
  on disk.

And the follow-up bug, which fits the series' habit of showing the mistakes:
decompression was unbounded. Gzip expands, so a few kilobytes can inflate to
gigabytes and take the process down. Fixed with a running byte limit. The first
attempt at a test for it exhausted the test JVM while building the payload, which
is why the limit is now injectable.

## What the article should refuse to claim

Worth a short section, because it is the opposite of how most security writing
reads.

Biometric unlock and the authenticator code are **gates in front of the app, not
locks on the data**:

- The Keystore key is not created with `setUserAuthenticationRequired`, so the
  biometric check is enforced by the app's own code rather than by the secure
  element.
- TOTP verification needs the shared secret stored locally, so anyone who can read
  the app's private storage can generate valid codes. A six digit code rotating
  every thirty seconds carries about twenty bits of entropy and cannot protect a
  key regardless.

Both stop someone picking up an unlocked phone. Neither stops someone running code
as the app. The honest ranking:

1. SQLCipher with a hardware-wrapped key
2. The passphrase, via PBKDF2 at 600,000 iterations
3. Biometric and authenticator gates

Saying so is more useful to a reader than implying bank-grade two factor, and it
sets up the obvious follow-up post: binding the key to user authentication so the
secure element does the enforcing.

## Files to reference

- `app/src/main/java/com/quietwire/quietledger/core/crypto/KeyEnvelope.kt`
- `app/src/main/java/com/quietwire/quietledger/core/crypto/DatabaseKeyStore.kt`
- `app/src/main/java/com/quietwire/quietledger/data/backup/BackupEnvelope.kt`
- `app/src/androidTest/java/.../DatabaseKeyStoreTest.kt` for the device tests
- `docs/security-audit.md` for the third party verification angle

## Open questions for Dennis

- One long post, or split into the live database and the backup format?
- How much code to show. The envelope format table carries most of the meaning
  without any Kotlin at all.
- Whether to wait for the `setUserAuthenticationRequired` work, so the post can
  end with the stronger design rather than promising it.
- Whether to name the app. It is a private repository today, and the article works
  either way.
