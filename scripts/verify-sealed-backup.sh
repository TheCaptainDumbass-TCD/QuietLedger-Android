#!/usr/bin/env bash
# Proves a backup sealed by Kotlin can be opened by the server.
#
# There are two implementations of one binary format, and no Kotlin test can
# show they agree. A mismatched OAEP digest, a byte order slip in the wrapped
# key length, or a header field added on one side only all produce a file that
# seals cleanly here and cannot be opened there. The symptom appears during a
# restore, which is the worst possible moment.
#
# So the JVM test writes a sealed file and this opens it for real.
#
#     scripts/verify-sealed-backup.sh
#
# Needs the server's private key on this host. Run it after any change to
# BackupEnvelope.seal or to the envelope format on either side.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIEWER="${VIEWER:-$HOME/QuietLedger-Web}"
SEALED="$REPO_DIR/app/build/sealed-by-kotlin.qlbk"

die() { echo "FAILED: $*" >&2; exit 1; }

[ -d "$VIEWER" ] || die "viewer not found at $VIEWER"
[ -f "$HOME/.config/quietledger/server-key.pem" ] || die "no server private key on this host"

echo "sealing with Kotlin..."
cd "$REPO_DIR"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" \
  ./gradlew --quiet :app:testDebugUnitTest --tests "*SealedEnvelopeTest*" --rerun \
  >/dev/null 2>&1 || die "the Kotlin sealing test did not pass"

[ -f "$SEALED" ] || die "no sealed file was produced at $SEALED"

echo "opening with the server's private key..."
cd "$VIEWER"
./venv/bin/python - "$SEALED" <<'PYEOF'
import sys
from pathlib import Path

sys.path.insert(0, ".")
from src.sync import qlbk, serverkey
from src.sync.model import Ledger

path = Path(sys.argv[1])
key = serverkey.load()
if key is None:
    sys.exit("no server private key")

if not qlbk.is_sealed(path.read_bytes()):
    sys.exit("the file Kotlin wrote is not recognised as sealed")

payload = qlbk.read_sealed(path, key)
ledger = Ledger.from_payload(payload)

# Checked rather than assumed. A file that decrypts to an empty ledger would
# otherwise look like a pass.
if not ledger.debts:
    sys.exit("opened, but the contents are empty")

print(f"  opened: {len(ledger.debts)} debts, owed {ledger.total_owed}")
PYEOF

echo "the two implementations agree"
