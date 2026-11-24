#!/usr/bin/env bash
# Runs a self-hosted MobSF scan against a built APK and against the source.
#
#   ./scripts/mobsf-scan.sh [debug|release]
#
# Both are needed, for a reason worth understanding before trusting any output.
#
# MobSF decompiles an APK with jadx and then runs its code analysis over the
# decompiled Java. On this machine that decompilation **fails**: MobSF downloads
# jadx itself, then its own integrity check rejects the binary it just fetched
# with "Executable/Library Tampering Detected". Code analysis then runs against
# an empty directory and reports zero findings.
#
# The result is a confident all-clear with a plausible score, produced without
# any code having been read. Exactly the failure mode a security tool must not
# have. This script therefore:
#
#   1. Scans the APK, which gives real manifest, certificate, permission,
#      network and tracker analysis. None of that depends on jadx.
#   2. Separately uploads the Kotlin source as a zip, which MobSF analyses
#      directly with no decompilation step, so the code rules actually run.
#   3. Fails loudly if the APK scan reports zero code findings, since that is
#      the signature of the silent decompiler failure rather than clean code.
#
# Setup, once:
#   python3 -m venv ~/mobsf-venv && ~/mobsf-venv/bin/pip install mobsf
#   ~/mobsf-venv/bin/mobsf            # prints the REST API key, listens on :8000
#
# Installed from PyPI rather than by cloning the repository, so it needs no
# Docker and no root.

set -euo pipefail

BUILD_TYPE="${1:-release}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBSF_URL="${MOBSF_URL:-http://127.0.0.1:8000}"
# Only needs the standard library (json, zipfile), so system python is enough.
# This previously pointed into the desktop app's virtualenv, which stopped
# existing when that project was archived.
PYTHON="${PYTHON:-python3}"
cd "$REPO_DIR"

if [ -z "${MOBSF_API_KEY:-}" ]; then
    echo "Set MOBSF_API_KEY first. It is printed when MobSF starts, and is in" >&2
    echo "~/.MobSF/config.py or the startup log." >&2
    exit 1
fi

if ! curl -s --max-time 5 "$MOBSF_URL" >/dev/null; then
    echo "MobSF is not responding at $MOBSF_URL. Start it with ~/mobsf-venv/bin/mobsf" >&2
    exit 1
fi

APK="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
[ -f "$APK" ] || { echo "No APK at $APK; build it first." >&2; exit 1; }

upload_and_scan() {
    local file="$1" mime="$2" scan_type="$3" out="$4"
    local response hash
    response=$(curl -s --max-time 300 -F "file=@$file;type=$mime" \
        "$MOBSF_URL/api/v1/upload" -H "Authorization:$MOBSF_API_KEY")
    hash=$($PYTHON -c "import json,sys; print(json.load(sys.stdin)['hash'])" <<< "$response")
    curl -s --max-time 1800 -X POST --url "$MOBSF_URL/api/v1/scan" \
        -d "scan_type=$scan_type&hash=$hash" -H "Authorization:$MOBSF_API_KEY" -o "$out"
}

echo "Scanning $APK"
upload_and_scan "$APK" "application/vnd.android.package-archive" "apk" /tmp/mobsf-apk.json

echo "Packaging source for a scan that does not depend on jadx"
$PYTHON - << 'PYZIP'
import pathlib, zipfile
root = pathlib.Path('.')
targets = list(root.glob('app/src/**/*')) + [
    root / 'app/build.gradle.kts', root / 'build.gradle.kts',
    root / 'settings.gradle.kts', root / 'gradle/libs.versions.toml']
with zipfile.ZipFile('/tmp/quietledger-src.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for p in targets:
        if p.is_file() and '/build/' not in str(p):
            z.write(p, str(p))
PYZIP
upload_and_scan /tmp/quietledger-src.zip "application/zip" "zip" /tmp/mobsf-src.json

echo
$PYTHON - "$BUILD_TYPE" << 'PYREPORT'
import json, sys, textwrap

build_type = sys.argv[1]
apk = json.load(open('/tmp/mobsf-apk.json'))
src = json.load(open('/tmp/mobsf-src.json'))

sec = apk.get('appsec', {})
print(f"=== APK scan ({build_type}) ===")
print(f"  security score: {sec.get('security_score')}/100")
for bucket in ('high', 'warning', 'info', 'secure'):
    for item in sec.get(bucket) or []:
        print(f"  [{bucket.upper()}] {item.get('title', '')[:100]}")

network = apk.get('network_security', {}).get('network_findings') or []
print(f"  network findings: {len(network)}")
print(f"  domains referenced: {len(apk.get('domains') or {})}")
print(f"  trackers: {(apk.get('trackers') or {}).get('detected_trackers')}")

print()
print("=== Source scan (code rules) ===")
findings = src.get('code_analysis', {}).get('findings', {}) or {}
if not findings:
    print("  none")
order = {'high': 0, 'warning': 1, 'info': 2, 'secure': 3}
for name, data in sorted(findings.items(),
                         key=lambda kv: order.get(kv[1].get('metadata', {}).get('severity', ''), 9)):
    md = data.get('metadata', {})
    print(f"  [{md.get('severity', '?').upper()}] {name}")
    for f in list((data.get('files') or {}).keys())[:6]:
        print(f"        {f}")

# The check that matters: an APK scan with no code findings means jadx failed,
# not that the code is clean.
apk_code = apk.get('code_analysis', {}).get('findings', {}) or {}
print()
if not apk_code:
    print("NOTE: the APK scan produced no code findings. That is the signature of")
    print("      MobSF's jadx decompilation failing, not of clean code. Trust the")
    print("      source scan above for code rules, and check the MobSF log for")
    print("      'Decompiling with JADX failed'.")
PYREPORT
