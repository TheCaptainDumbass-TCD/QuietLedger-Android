#!/usr/bin/env python3
"""
Converts the Python desktop app's data into a QuietLedger Android backup file.

    python3 scripts/desktop-to-android.py ~/.finance_tracker/finance_data.db
    python3 scripts/desktop-to-android.py backup.ftbackup

Writes a .qlbk file. Copy it to the phone and use Restore in the app's menu.

Run this on the machine that has the data. Nothing is sent anywhere, no
passphrase is ever printed, and amounts are only shown if you ask for --detail.
The output file is encrypted with a passphrase you choose here and type again
on the phone.

Needs the `cryptography` package only when reading a .ftbackup, which the
desktop app's virtualenv already has:

    ~/QuietLedger/venv/bin/python scripts/desktop-to-android.py ...

Corrections that survive re-importing
-------------------------------------
Importing replaces everything in the app, so any category fixed by hand there is
lost the next time this runs. Put corrections in a `category-overrides.json`
next to the source file instead, and they are applied on every import:

    {
      "Truck Insurence": "AUTO_INSURANCE",
      "Medical Loan": "MEDICAL"
    }

Values are validated against the enum in Categories.kt, so a typo or a category
the app no longer has fails loudly rather than quietly becoming OTHER.

This is the answer to the importer guessing wrong, rather than a longer list of
heuristics. The guess only has to be good enough that the overrides file stays
short.

What comes across, and what does not
------------------------------------
The desktop keeps debts and recurring bills in one `debts` table and tells them
apart with two hardcoded category lists. Android uses separate tables, so this
script performs that split:

    CREDIT, VEHICLES, TAXES, BACK_TAXES   -> debts, included in the payoff plan
    HOUSING, INSURANCE, UTILITIES,
    SERVICES, SAVINGS_TRANSFER            -> recurring expenses, excluded

Investments, savings accounts, precious metals and tax years are **not**
transferred, because those screens do not exist in the Android app yet. Nothing
is deleted from the desktop, so they are still there when it does.
"""

import argparse
import base64
import getpass
import gzip
import hashlib
import json
import os
import re
import sqlite3
import struct
import sys
import tempfile
from datetime import datetime, timezone, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

# --- QuietLedger backup format, mirroring BackupEnvelope.kt -----------------

MAGIC = b"QLBK"
FORMAT_VERSION = 1
PBKDF2_ITERATIONS = 600_000
SALT_SIZE = 16
NONCE_SIZE = 12
MIN_PASSPHRASE = 10
SCHEMA_VERSION = 1

# --- desktop to Android category mapping ------------------------------------

DEBT_CATEGORIES = {
    "Credit": "CREDIT_CARD",
    "Vehicles": "VEHICLE_LOAN",
    "Taxes": "TAX",
    "Back Taxes": "TAX",
}

# Fallback per desktop category when the name gives nothing away.
EXPENSE_FALLBACK = {
    "Housing": "RENT",
    "Insurance": "OTHER_INSURANCE",
    "Utilities": "ELECTRIC",
    "Services": "SUBSCRIPTION",
    "Savings Transfer": "SAVINGS_TRANSFER",
}

# The desktop only records a coarse category, so the specific one is guessed
# from the name. Ordered: first match wins, so put the specific before the
# general. Every guess is reported, because a wrong category is quiet and
# annoying rather than loud.
NAME_HINTS = [
    (r"\b(electric|power|hydro)\b", "ELECTRIC"),
    (r"\bnatural\s*gas\b|\bgas\s*(bill|company|co)\b", "NATURAL_GAS"),
    (r"\bpropane\b", "PROPANE"),
    (r"\bheating\s*oil\b|\bfuel\s*oil\b", "HEATING_OIL"),
    # Water before sewer, so a combined "Water & Sewer" bill reads as water
    # rather than the other way round. A sewer-only bill still matches below.
    (r"\bwater\b", "WATER"),
    (r"\bsewer\b|\bsewage\b", "SEWER"),
    (r"\btrash\b|\bgarbage\b|\brecycl", "TRASH"),
    (r"\binternet\b|\bbroadband\b|\bfiber\b|\bfibre\b", "INTERNET"),
    (r"\bcell\b|\bmobile\b|\bphone\b", "MOBILE_PHONE"),
    (r"\blandline\b", "LANDLINE"),
    (r"\brent\b", "RENT"),
    (r"\blease\b", "LEASE"),
    (r"\bhoa\b|\bcondo\s*fee", "HOA_FEES"),
    (r"\bproperty\s*tax\b", "PROPERTY_TAX"),
    (r"\bhomeowners?\b|\bhome\s*ins", "HOMEOWNERS_INSURANCE"),
    (r"\brenters?\s*ins", "RENTERS_INSURANCE"),
    # Generic words only. Manufacturer and model names were tried and removed:
    # the list is unbounded, regionally biased, ages badly, and several makes
    # build both cars and motorcycles. People also name these after a vehicle
    # with a nickname or a typo that no list would catch.
    #
    # The trade is deliberate. An unmatched entry falls back to a category that
    # says "other", is listed in the report as needing a look, and takes one tap
    # to correct. A confident wrong guess costs more, because it looks right.
    (r"\bmotorcycle\b|\bmoto\b", "MOTORCYCLE_INSURANCE"),
    (r"\b(boat|rv|camper|trailer)\b.*\bins", "RECREATIONAL_INSURANCE"),
    (r"\b(auto|car|truck|suv|vehicle)\b.*\bins|\bins.*\b(auto|car|truck|suv)\b",
     "AUTO_INSURANCE"),
    (r"\blife\s*ins", "LIFE_INSURANCE"),
    (r"\bumbrella\b", "UMBRELLA_INSURANCE"),
    # Insurance-qualified names first, so "health insurance" is cover while a
    # bare "medical" or a telehealth service is care. They are different things
    # and belong in different lines on a budget.
    (r"\bhealth\s*ins|\bmedical\s*ins", "HEALTH_INSURANCE"),
    (r"\bdental\s*ins", "DENTAL_INSURANCE"),
    (r"\bvision\s*ins", "VISION_INSURANCE"),
    (r"\bpharmacy\b|\bprescription\b|\brx\b", "PHARMACY"),
    (r"\bgym\b|\bfitness\b", "GYM"),
    (r"\bmedical\b|\bclinic\b|\bdoctor\b|\btele.?(doc|health)\b|\bhealth\b", "MEDICAL"),
    (r"\bdental\b", "DENTAL_INSURANCE"),
    (r"\bvision\b", "VISION_INSURANCE"),
    (r"\bnetflix\b|\bhulu\b|\bspotify\b|\bdisney\b|\bstream", "STREAMING"),
    (r"\blawn\b|\bsnow\b|\bmow", "LAWN_CARE"),
    (r"\bclean", "CLEANING"),
    (r"\bchildcare\b|\bdaycare\b", "CHILDCARE"),
    (r"\bpet\b|\bvet\b", "PET_CARE"),
    (r"\bcharity\b|\btithe\b|\bdonat", "CHARITY"),
    (r"\bsubscription\b", "SUBSCRIPTION"),
]



# --- interest rates hidden in the notes field ----------------------------------

# Words that mean a nearby percentage is an interest rate.
RATE_WORDS = r"(?:apr|int|interest|rate)"

# Words that mean it is definitely not. A card advertising "3% Cashback" would
# otherwise be imported as a 3% APR, which understates what it really costs.
NOT_RATE_WORDS = ("cashback", "cash back", "cash-back", "reward", "points",
                  "discount", "off", "return", "yield", "apy")

# A rate that is only temporary. Importing "0% for 15 months" as a flat 0%
# projects a payoff that assumes the promotion never ends, which is worse than
# recording nothing and being asked.
PROMO_WORDS = ("for ", "intro", "promo", "until", "first ", "months starting")


def extract_apr(notes: str):
    """
    Returns (basis_points, confidence) where confidence is one of
    'confident', 'promotional' or None.

    Deliberately cautious. A wrong interest rate silently changes every payoff
    figure, and unlike a wrong category it is not visible at a glance.
    """
    if not notes:
        return None, None

    lowered = notes.lower()
    for match in re.finditer(r"(\d+(?:\.\d+)?)\s*%", lowered):
        value = float(match.group(1))
        if value > 100:
            continue

        # Look at the words immediately around the number.
        start, end = match.start(), match.end()
        context = lowered[max(0, start - 30):min(len(lowered), end + 30)]

        if any(word in context for word in NOT_RATE_WORDS):
            continue

        looks_like_rate = re.search(RATE_WORDS, context) is not None
        is_promo = any(word in context for word in PROMO_WORDS)

        if is_promo:
            return int(round(value * 100)), "promotional"
        if looks_like_rate:
            return int(round(value * 100)), "confident"

    return None, None



# --- user supplied overrides ---------------------------------------------------

OVERRIDES_FILENAME = "category-overrides.json"


def load_category_enum():
    """
    Reads the valid categories out of the Kotlin enum.

    Parsed rather than duplicated here, so a category renamed in the app cannot
    silently keep working in this script and produce a file the app then reads
    as OTHER.
    """
    source = (Path(__file__).parent.parent /
              "app/src/main/java/com/quietwire/quietledger/data/model/Categories.kt")
    if not source.exists():
        return None
    text = source.read_text()
    start = text.index("enum class RecurringExpenseCategory")
    end = text.index("companion object", start)
    return set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\(", text[start:end], re.M))


def load_overrides(path: Path):
    """
    Name to category, so a correction survives re-importing.

    The desktop records only a coarse category, and re-importing replaces
    everything, so without this every fix made in the app is lost on the next
    import. Stating it once here is the difference between a one-off tidy and
    doing the same tidy every time.
    """
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text())
    except json.JSONDecodeError as error:
        sys.exit(f"{path} is not valid JSON: {error}")

    valid = load_category_enum()
    if valid:
        for name, category in raw.items():
            if category not in valid:
                sys.exit(
                    f"{path}: {category!r} for {name!r} is not a category the app knows.\n"
                    f"Valid values are in Categories.kt."
                )
    return raw


def to_cents(value) -> int:
    """
    REAL dollars to integer cents.

    Via Decimal on the string form, because the desktop stores money as SQLite
    REAL and reading 158.00 back as 157.99999999999997 then truncating would
    quietly lose a cent on a good number of rows.
    """
    if value is None:
        return 0
    return int(
        (Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))
        * 100
    )


def to_epoch_millis(text) -> int:
    if not text:
        return 0
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f",
                "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            dt = datetime.strptime(str(text), fmt).replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue
    return 0


def guess_expense_category(name: str, desktop_category: str):
    """Returns (category, was_guessed_from_name)."""
    lowered = (name or "").lower()
    for pattern, category in NAME_HINTS:
        if re.search(pattern, lowered):
            return category, True
    return EXPENSE_FALLBACK.get(desktop_category, "OTHER"), False


def decrypt_ftbackup(path: Path, password: str) -> bytes:
    """Reads the desktop backup format: 16 byte salt, then a Fernet token."""
    try:
        from cryptography.fernet import Fernet, InvalidToken
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    except ImportError:
        sys.exit(
            "Reading a .ftbackup needs the cryptography package. Either point\n"
            "this at the live .db instead, or run it with the desktop app's\n"
            "interpreter, for example ~/QuietLedger/venv/bin/python"
        )

    raw = path.read_bytes()
    salt, token = raw[:16], raw[16:]

    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=480_000)
    key = base64.urlsafe_b64encode(kdf.derive(password.encode()))

    try:
        return Fernet(key).decrypt(token)
    except InvalidToken:
        sys.exit("That password did not decrypt the backup.")


def read_desktop(db_path: Path):
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute("SELECT * FROM debts").fetchall()
    except sqlite3.OperationalError as error:
        sys.exit(f"Could not read a debts table from that file: {error}")
    finally:
        pass

    savings_rows = []
    try:
        savings_rows = connection.execute("SELECT * FROM savings_accounts").fetchall()
    except sqlite3.OperationalError:
        pass

    other_tables = {}
    for table in ("investments", "tax_years"):
        try:
            other_tables[table] = connection.execute(
                f"SELECT COUNT(*) FROM {table}"
            ).fetchone()[0]
        except sqlite3.OperationalError:
            other_tables[table] = 0
    connection.close()
    return rows, savings_rows, other_tables


def convert(rows, overrides=None):
    debts, expenses, notes = [], [], []
    combined_notes, zero_payment = [], []
    apr_found, apr_promotional = [], []
    overrides = overrides or {}
    overridden = []

    for row in rows:
        keys = row.keys()
        name = row["name"]
        desktop_category = row["category"]
        weekly = to_cents(row["weekly_payment"] if "weekly_payment" in keys else 0)
        monthly = to_cents(row["monthly_payment"] if "monthly_payment" in keys else 0)
        created = to_epoch_millis(row["created_at"] if "created_at" in keys else None)
        updated = to_epoch_millis(row["updated_at"] if "updated_at" in keys else None)
        is_paid = bool(row["is_paid"]) if "is_paid" in keys else False

        # The desktop treats these as additive, not as alternatives: it computes
        # `weekly_payment * 4 + monthly_payment`. A record can legitimately carry
        # both. Android stores one amount and one frequency, so a row using both
        # has to be folded into a single monthly figure or money goes missing.
        if weekly > 0 and monthly > 0:
            # Real weeks per month, 52/12, not the desktop's 4. Its figure
            # understates the year by about four weeks, which is one of the
            # arithmetic bugs this app deliberately does not reproduce, so the
            # converted total will be slightly higher than the desktop showed.
            combined = (Decimal(weekly) * Decimal(52) / Decimal(12)) + Decimal(monthly)
            amount = int(combined.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
            frequency = "MONTHLY"
            combined_notes.append(
                f"  {name!r}: had both a weekly and a monthly payment, folded into "
                f"one monthly amount"
            )
        elif weekly > 0:
            amount, frequency = weekly, "WEEKLY"
        else:
            amount, frequency = monthly, "MONTHLY"

        if amount == 0:
            zero_payment.append(f"  {name!r} ({desktop_category})")

        note_text = row["notes"] if "notes" in keys and row["notes"] else ""
        apr_bp, apr_confidence = extract_apr(note_text)
        apr_promo = apr_confidence == "promotional"
        if apr_promo:
            # Applied, and flagged. The app carries a promotional marker, so the
            # rate can be recorded truthfully and the long projection marked as
            # unreliable rather than the rate being dropped.
            apr_promotional.append(
                f"  {name!r}: {apr_bp / 100:.2f}%, marked promotional"
            )
        elif apr_confidence == "confident":
            apr_found.append(f"  {name!r}: {apr_bp / 100:.2f}% APR")

        if desktop_category in DEBT_CATEGORIES:
            debts.append({
                "id": row["id"],
                "name": name,
                "category": DEBT_CATEGORIES[desktop_category],
                "current_balance_minor": to_cents(row["total_amount"]),
                "original_balance_minor": to_cents(row["total_amount"]),
                "minimum_payment_minor": to_cents(
                    row["minimum_payment"] if "minimum_payment" in keys else 0
                ),
                "scheduled_payment_minor": amount,
                "payment_frequency": frequency,
                "apr_basis_points": apr_bp,
                "apr_is_promotional": apr_promo,
                "due_day_of_month": None,
                # Never closed on import. The desktop's is_paid flag used to
                # carry across and hide the debt on Android, where a zero
                # balance is still an account worth seeing and removal is an
                # explicit action.
                "closed_at": None,
                "notes": row["notes"] if "notes" in keys and row["notes"] else "",
                "sort_order": 0,
                "created_at": created,
                "updated_at": updated,
            })
        else:
            if name in overrides:
                category, guessed = overrides[name], True
                overridden.append(f"  {name!r}: {category} (from {OVERRIDES_FILENAME})")
            else:
                category, guessed = guess_expense_category(name, desktop_category)
            if name in overrides:
                pass
            elif guessed:
                notes.append(f"  {name!r}: {desktop_category} -> {category} (from the name)")
            else:
                notes.append(f"  {name!r}: {desktop_category} -> {category} (no hint, check this)")
            expenses.append({
                "id": row["id"],
                "name": name,
                "category": category,
                "amount_minor": amount,
                "frequency": frequency,
                "due_day_of_month": None,
                "is_active": not is_paid,
                "ended_at": updated if is_paid else None,
                "notes": row["notes"] if "notes" in keys and row["notes"] else "",
                "sort_order": 0,
                "created_at": created,
                "updated_at": updated,
            })

    unused = sorted(set(overrides) - {row["name"] for row in rows})

    return (debts, expenses, notes, combined_notes, zero_payment, apr_found,
            apr_promotional, overridden, unused)



# --- savings, the straightforward half of net worth ---------------------------

# The desktop records no purpose, so it is guessed from the name the same way
# expense categories are, and reported.
SAVINGS_PURPOSE_HINTS = [
    (r"\bemergency\b|\be-?fund\b|\brainy\b|\bsafety\s*net\b|\bbuffer\b", "EMERGENCY"),
    (r"\bretire|\bira\b|\b401", "RETIREMENT"),
    (r"\bsinking\b", "SINKING"),
    (r"\bcollege\b|\btuition\b|\beducation\b|\b529\b", "EDUCATION"),
    (r"\bcar\b|\btruck\b|\bvehicle\b", "VEHICLE"),
    (r"\bhouse\b|\bhome\b|\breno", "HOME"),
    (r"\btravel\b|\bvacation\b|\bholiday\b|\btrip\b", "TRAVEL"),
    (r"\btax\b", "TAX"),
]


def convert_savings(rows, overrides=None):
    """Savings accounts, which carry a known balance and need no valuation."""
    overrides = overrides or {}
    accounts, notes = [], []

    for row in rows:
        keys = row.keys()
        name = row["name"]
        lowered = (name or "").lower()

        purpose = overrides.get(name)
        if purpose:
            notes.append(f"  {name!r}: {purpose} (from {OVERRIDES_FILENAME})")
        else:
            purpose = "GENERAL"
            for pattern, candidate in SAVINGS_PURPOSE_HINTS:
                if re.search(pattern, lowered):
                    purpose, = (candidate,)
                    notes.append(f"  {name!r}: {purpose} (from the name)")
                    break
            else:
                notes.append(f"  {name!r}: GENERAL (no hint)")

        apy = row["apy"] if "apy" in keys else None
        created = to_epoch_millis(row["created_at"] if "created_at" in keys else None)
        updated = to_epoch_millis(row["updated_at"] if "updated_at" in keys else None)

        accounts.append({
            "id": row["id"],
            "name": name,
            "institution": (row["institution"] if "institution" in keys and row["institution"] else ""),
            "current_balance_minor": to_cents(row["current_balance"]),
            # APY in basis points, so 4.25 percent becomes 425.
            "apy_basis_points": int(round(float(apy) * 100)) if apy else None,
            "purpose": purpose,
            "goal_minor": None,
            "closed_at": None,
            "notes": (row["notes"] if "notes" in keys and row["notes"] else ""),
            "sort_order": 0,
            "created_at": created,
            "updated_at": updated,
        })

    return accounts, notes



# --- precious metals, from invoice parsing -----------------------------------

METAL_TRACKED = {"GOLD", "SILVER", "COPPER"}


def load_metal_lots(path):
    """Reads lots produced by parse-metal-invoices.py.

    Validated rather than trusted, even though it is our own output. The two
    scripts can drift, and a lot with a metal the app does not know would be
    dropped by the Kotlin mapper and silently vanish from the cost basis.
    """
    payload = json.loads(path.read_text())
    lots = payload.get("lots", [])
    if not lots:
        raise SystemExit(f"{path} contains no lots.")

    unknown = sorted({l["metal"] for l in lots} - METAL_TRACKED)
    if unknown:
        raise SystemExit(
            f"{path} contains metals the app does not track: {', '.join(unknown)}.\n"
            "Tracking is gold, silver and copper. Fix the parser or the filter "
            "rather than letting these be dropped on import."
        )

    converted = []
    for lot in lots:
        purchased = lot.get("order_date") or ""
        millis = 0
        if purchased:
            millis = int(datetime.strptime(purchased, "%Y-%m-%d")
                         .replace(tzinfo=timezone.utc).timestamp() * 1000)

        converted.append({
            "vendor": lot["vendor"],
            "order_number": str(lot["order_number"]),
            "purchased_at": millis,
            "description": lot["description"],
            "metal": lot["metal"],
            "quantity_milli": int(round(float(lot["quantity"]) * 1000)),
            "unit_weight_micro_oz": int(round(float(lot["unit_weight_oz"]) * 1_000_000)),
            "weight_label": lot.get("weight_label") or "",
            "unit_price_minor": lot["unit_price_minor"] or 0,
            "extended_price_minor": lot["extended_price_minor"] or 0,
            "shipping_minor": lot.get("shipping_minor") or 0,
            "spot_price_minor": lot.get("spot_price_minor"),
            "source_file": lot.get("invoice_file") or "",
            "notes": "",
            "created_at": millis,
        })
    return converted


def write_qlbk(path: Path, payload: dict, passphrase: str):
    """Mirrors BackupEnvelope.write, so the Android app can read it directly."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("Writing the backup needs the cryptography package.")

    salt = os.urandom(SALT_SIZE)
    nonce = os.urandom(NONCE_SIZE)
    header = MAGIC + bytes([FORMAT_VERSION]) + struct.pack(">I", PBKDF2_ITERATIONS) + salt + nonce

    key = hashlib.pbkdf2_hmac("sha256", passphrase.encode(), salt, PBKDF2_ITERATIONS, dklen=32)
    plaintext = gzip.compress(json.dumps(payload, separators=(",", ":")).encode())

    # The whole header is authenticated, so the stored iteration count cannot be
    # weakened without the tag failing.
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, header)
    path.write_bytes(header + ciphertext)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="a finance_data.db or a .ftbackup")
    parser.add_argument("-o", "--output", type=Path, help="where to write the .qlbk")
    parser.add_argument("--overrides", type=Path,
                        help=f"name to category corrections, default {OVERRIDES_FILENAME} "
                             "beside the source file")
    parser.add_argument("--metals", type=Path,
                        help="metals.json from parse-metal-invoices.py, included as holdings")
    parser.add_argument("--detail", action="store_true",
                        help="also print amounts, off by default")
    args = parser.parse_args()

    if not args.source.exists():
        sys.exit(f"No such file: {args.source}")

    temp_db = None
    if args.source.suffix == ".ftbackup":
        password = getpass.getpass("Desktop backup password: ")
        data = decrypt_ftbackup(args.source, password)
        temp_db = Path(tempfile.mkstemp(suffix=".db")[1])
        temp_db.write_bytes(data)
        db_path = temp_db
    else:
        db_path = args.source

    try:
        rows, savings_rows, other_tables = read_desktop(db_path)
        overrides_path = args.overrides or (args.source.parent / OVERRIDES_FILENAME)
        overrides = load_overrides(overrides_path)
        (debts, expenses, notes, combined_notes, zero_payment, apr_found,
         apr_promotional, overridden, unused_overrides) = convert(rows, overrides)
        savings, savings_notes = convert_savings(savings_rows, overrides)

        metals = []
        if args.metals:
            metals = load_metal_lots(args.metals)
    finally:
        if temp_db:
            # The decrypted database must not be left lying around.
            temp_db.write_bytes(b"\0" * temp_db.stat().st_size)
            temp_db.unlink()

    print(f"\nRead {len(rows)} rows from the desktop debts table.")
    print(f"  {len(debts)} became debts, included in the payoff plan")
    print(f"  {len(expenses)} became recurring expenses, excluded from it")
    if metals:
        cost = sum(l["extended_price_minor"] + l["shipping_minor"] for l in metals)
        print(f"\nRead {len(metals)} precious metal lots, valued at cost.")
        for metal in ("GOLD", "SILVER", "COPPER"):
            owned = [l for l in metals if l["metal"] == metal]
            if owned:
                oz = sum(l["quantity_milli"] * l["unit_weight_micro_oz"]
                         for l in owned) / 1_000 / 1_000_000
                spent = sum(l["extended_price_minor"] + l["shipping_minor"] for l in owned)
                print(f"  {metal.title():8} {oz:>10,.4f} troy oz   ${spent / 100:>10,.2f}")

    if savings:
        total = sum(a["current_balance_minor"] for a in savings)
        print(f"\nRead {len(savings)} savings accounts, the asset side of net worth.")
        if savings_notes:
            print("  Purpose guessed from the name, since the desktop records none:")
            print("\n".join(f"  {n.strip()}" for n in savings_notes))

    if notes:
        print("\nCategory mapping, worth a glance since the desktop only stores a coarse one:")
        print("\n".join(notes))

    if overridden:
        print(f"\nCategories you have already corrected:")
        print("\n".join(overridden))

    if unused_overrides:
        print(f"\nOverrides in {OVERRIDES_FILENAME} that matched nothing:")
        for name in unused_overrides:
            print(f"  {name!r}")
        print("  Either the name is misspelled here, or it changed in the desktop app.")
        print("  Those entries fell back to a guess, so this is worth fixing.")

    if apr_found:
        print("\nInterest rates found in the notes and applied:")
        print("\n".join(apr_found))

    if apr_promotional:
        print("\nRates that read as temporary, applied and flagged:")
        print("\n".join(apr_promotional))
        print("  Marked promotional in the app, so the rate is recorded but long")
        print("  projections are shown as unreliable. Update it by hand when the")
        print("  offer ends.")

    if combined_notes:
        print("\nRows that used both payment fields:")
        print("\n".join(combined_notes))
        print("  The desktop adds weekly * 4 to the monthly figure. This uses the real")
        print("  52/12 weeks per month, so these totals will read slightly higher here.")

    if zero_payment:
        print("\nNo payment amount recorded, carried across as zero:")
        print("\n".join(zero_payment))
        print("  A debt with no payment is reported by the payoff screen as never")
        print("  clearing, rather than being given an invented date.")

    skipped = {k: v for k, v in other_tables.items() if v}
    if skipped:
        print("\nNot transferred, because those screens do not exist on Android yet:")
        for table, count in skipped.items():
            print(f"  {table}: {count} rows")
        print("  Nothing was removed from the desktop, so they are still there.")

    if args.detail:
        print("\nAmounts:")
        for d in debts:
            print(f"  debt    {d['name']}: {d['current_balance_minor'] / 100:.2f}")
        for e in expenses:
            print(f"  expense {e['name']}: {e['amount_minor'] / 100:.2f} {e['frequency']}")

    payload = {
        "schema": SCHEMA_VERSION,
        "app_version": "desktop-import",
        "exported_at": int(datetime.now(timezone.utc).timestamp() * 1000),
        "debts": debts,
        "recurring_expenses": expenses,
        "balance_changes": [],
        "savings_accounts": savings,
        "metal_lots": metals,
    }

    print()
    while True:
        passphrase = getpass.getpass(
            f"Choose a passphrase for the Android file ({MIN_PASSPHRASE}+ characters): ")
        if len(passphrase) < MIN_PASSPHRASE:
            print(f"  Too short, needs at least {MIN_PASSPHRASE}.")
            continue
        if passphrase != getpass.getpass("Type it again: "):
            print("  Those did not match.")
            continue
        break

    output = args.output or Path(
        f"quietledger-from-desktop-{datetime.now():%Y-%m-%d}.qlbk")
    write_qlbk(output, payload, passphrase)

    print(f"\nWrote {output}  ({output.stat().st_size} bytes)")
    print("Copy it to the phone, then Restore from the menu and enter that passphrase.")
    print("Restore replaces everything currently in the app, and it will show you")
    print("what the file contains before it does.")


if __name__ == "__main__":
    main()
