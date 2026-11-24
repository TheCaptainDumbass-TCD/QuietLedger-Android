#!/usr/bin/env bash
# Proves a sealed backup can actually be restored onto the phone.
#
# The phone seals every backup to the server's public key and cannot read what
# it writes, so a sealed backup is useless on its own. The server unseals it
# and re-encrypts it under a passphrase, and the phone's import path reads
# that. This exercises the whole path for real.
#
# Until this existed, every backup being produced was unrestorable and nothing
# said so. A write-only backup system is worse than none, because it is
# believed.
#
#     scripts/verify-restore.sh
#
# Non-destructive. The on-device test builds its own throwaway database, so the
# real ledger is never opened. There is one Android device here and its data is
# real, so a destructive test is not an option.
#
# Run after any change to the envelope format, the import path, or the restore
# conversion on the server.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIEWER="${VIEWER:-$HOME/QuietLedger-Web}"
BACKUPS="${BACKUPS:-$HOME/.local/share/quietledger/backups}"
FIXTURE="$REPO_DIR/app/src/androidTest/assets/restore-from-server.qlbk"
# The real figures the restore must reproduce. They stay outside the repository
# because they describe the real ledger. One name=value per line:
#
#   expectDebts=0
#   expectRecurringExpenses=0
#   expectSavingsAccounts=0
#   expectHoldings=0
#   expectMetalLots=0
#   expectMetalSpotPrices=0
#   expectDebtTotalMinor=0
#
# The debt total is in cents. Update the file when the ledger changes.
EXPECT="${EXPECT:-$HOME/.config/quietledger/restore-expect}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

die() { echo "FAILED: $*" >&2; exit 1; }

[ -f "$EXPECT" ] || die "no expected figures at $EXPECT"
EXPECT_ARGS=()
while IFS='=' read -r name value; do
  case "$name" in ''|\#*) continue ;; esac
  EXPECT_ARGS+=(-e "$name" "$value")
done < "$EXPECT"
[ "${#EXPECT_ARGS[@]}" -eq 21 ] || die "$EXPECT must set all seven expected figures"

[ -d "$VIEWER" ] || die "viewer not found at $VIEWER"
[ -f "$HOME/.config/quietledger/server-key.pem" ] || die "no server private key here"

echo "building a restore file from the newest sealed backup..."
cd "$VIEWER"
./venv/bin/python - "$BACKUPS" "$WORK" <<'PYEOF'
import sys
from pathlib import Path

sys.path.insert(0, ".")
from src.sync import qlbk, serverkey
from src.web import restore

backups, work = Path(sys.argv[1]), Path(sys.argv[2])
sealed = sorted(backups.glob("*.qlbk"), key=lambda p: p.stat().st_mtime)
sealed = [p for p in sealed if qlbk.is_sealed(p.read_bytes())]
if not sealed:
    sys.exit("no sealed backup to work from")

key = serverkey.load()
if key is None:
    sys.exit("no server private key")

result = restore.prepare(sealed[-1], key, work / "restore.qlbk")
(work / "passphrase.txt").write_text(result.passphrase)

header = qlbk.parse_header(result.path.read_bytes())
# Checked here as well as on the device, because the phone's passphrase reader
# accepts version 1 only and this once wrote version 2, producing a file the
# phone refused.
if header.version != 1:
    sys.exit(f"restore file is version {header.version}, the phone reads 1")

print(f"  from {result.source_name}: {result.summary}")
PYEOF

cp "$WORK/restore.qlbk" "$FIXTURE"
PASSPHRASE="$(cat "$WORK/passphrase.txt")"

echo "running the restore on the device..."
cd "$REPO_DIR"

# The passphrase is generated per run, so the test reads it from here rather
# than having it baked in.
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" \
  ./gradlew --quiet :app:assembleDebugAndroidTest >/dev/null 2>&1 \
  || die "could not build the instrumented tests"

ADB="${ADB:-$HOME/Android/sdk/platform-tools/adb}"
DEVICE="${DEVICE:-$($ADB devices | awk '/device$/ {print $1; exit}')}"
[ -n "$DEVICE" ] || die "no device connected"

$ADB -s "$DEVICE" install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null 2>&1 \
  || die "could not install the test apk"

OUTPUT="$($ADB -s "$DEVICE" shell am instrument -w \
  -e class com.quietwire.quietledger.data.backup.RestoreOnDeviceTest \
  -e restorePassphrase "$PASSPHRASE" \
  "${EXPECT_ARGS[@]}" \
  com.quietwire.quietledger.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"

echo "$OUTPUT" | grep -qE "^OK \(" || {
  echo "$OUTPUT" | tail -20
  die "the restore did not work"
}

echo "$OUTPUT" | grep -E "^OK \(" | sed 's/^/  /'
echo "a sealed backup can be restored onto this phone"
