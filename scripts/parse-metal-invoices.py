#!/usr/bin/env python3
"""Extract precious metal purchase lots from SD Bullion and APMEX invoices.

Run with the pypdf venv:

    ~/pdf-venv/bin/python scripts/parse-metal-invoices.py "private/desktop-backups"

Design notes, because the failure modes here are the whole problem.

**Every invoice is reconciled against its own arithmetic.** Line items plus
shipping minus savings must equal the order total the vendor printed. If it does
not, the parse of that invoice is wrong and it is reported rather than accepted.
That check is free and it is far stronger than eyeballing a sample, because the
vendor already did the sum.

**Accessories are excluded explicitly.** One real invoice contains "Empty 1 oz
Silver Maple Tube" fifteen times at $2.99. The description says silver and says
1 oz, and naive parsing would have credited fifteen ounces of metal that do not
exist. Tubes, boxes and capsules are storage, not holdings.

**Anything unclassified is reported, never dropped.** A silently skipped line is
a hole in a cost basis that nobody will notice. Tracking is gold, silver and
copper; anything else stops and asks.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, asdict, field
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

try:
    from pypdf import PdfReader
except ImportError:
    sys.exit("pypdf is needed. Try: ~/pdf-venv/bin/python " + " ".join(sys.argv))


CENT = Decimal("0.01")

# Storage and packaging. These carry a metal word and a weight in their name,
# which is exactly why they have to be matched before the metal is read.
ACCESSORY_PATTERNS = [
    # Plural-tolerant on purpose. "Tubes" did not match a bare \\btube\\b, so a
    # 1/10 oz gold coin tube was counted as a tenth of an ounce of gold. The
    # description names a metal and a weight because it describes what the tube
    # is *for*, which is exactly the trap.
    r"\bempty\b",
    r"\bmonster\s*box(es)?\b",
    r"\btubes?\b",
    r"\bcapsules?\b",
    r"\bair-?tites?\b",
    r"\bholders?\b",
    r"\bflips?\b",
    r"\bsleeves?\b",
    r"\bboxe?s?\b",
    r"\bcases?\b",
    r"\bbags?\b",
    r"\btrays?\b",
    r"\balbums?\b",
    r"\bdisplays?\b",
    r"\bgift\s*(cards?|box(es)?)\b",
    r"\bcloths?\b",
    r"\bgloves?\b",
    r"\bscales?\b",
    r"\btesters?\b",
    r"\bmagnets?\b",
    r"\bbooks?\b",
    r"\bshipping\b",
    r"\binsurance\b",
]

# Every metal here is weighed in troy ounces. Copper is *quoted* per pound on
# COMEX, but copper bullion is sold by the troy ounce, and dealers convert with
# 14.5833 to the pound.
WEIGHT_UNIT = {"GOLD": "troy oz", "SILVER": "troy oz"}

# Tracking these three only. Anything else is reported rather than guessed at.
METALS = {
    "GOLD": r"\bgold\b|\bau\b(?!\w)",
    "SILVER": r"\bsilver\b|\bsilber\b",
}

# Recognised so they can be counted and excluded quietly, rather than landing in
# the "unclassified" list where a real problem would be lost among them.
#
# Copper was tracked and removed: quoted per pound rather than per troy ounce,
# invoices carrying a placeholder $0.10 spot, and a holding of novelty rounds
# whose worth is the minting rather than the metal.
NOT_TRACKED = {"COPPER": r"\bcopper\b"}

# Metals deliberately recognised so they can be named in the report instead of
# vanishing into "unclassified".
OTHER_METALS = {
    "PLATINUM": r"\bplatinum\b",
    "PALLADIUM": r"\bpalladium\b",
    "RHODIUM": r"\brhodium\b",
}

TROY_OUNCES_PER_KILO = Decimal("32.1507466")
TROY_OUNCES_PER_GRAM = Decimal("0.0321507466")


def money(text: str | None) -> Decimal | None:
    """A currency field to Decimal, tolerating $ , ( ) and a stray minus."""
    if text is None:
        return None
    cleaned = text.strip().replace("$", "").replace(",", "").replace(" ", "")
    if not cleaned or cleaned in {"--", "-", "N/A"}:
        return None
    negative = cleaned.startswith("(") and cleaned.endswith(")")
    cleaned = cleaned.strip("()")
    try:
        value = Decimal(cleaned)
    except Exception:
        return None
    return -value if negative else value


def to_cents(value: Decimal | None) -> int | None:
    if value is None:
        return None
    return int((value.quantize(CENT, rounding=ROUND_HALF_UP) * 100).to_integral_value())


def is_accessory(description: str) -> bool:
    lowered = description.lower()
    return any(re.search(p, lowered) for p in ACCESSORY_PATTERNS)


def classify_metal(description: str) -> tuple[str | None, str | None]:
    """Returns (tracked metal, other metal seen). Both None means unclassified."""
    lowered = description.lower()
    for metal, pattern in METALS.items():
        if re.search(pattern, lowered):
            return metal, None
    for metal, pattern in {**NOT_TRACKED, **OTHER_METALS}.items():
        if re.search(pattern, lowered):
            return None, metal
    return None, None


def parse_weight(description: str) -> tuple[Decimal | None, str | None]:
    """Weight of one unit, as troy ounces, plus the unit as written.

    Returns (None, None) when the description carries no weight, which is
    reported rather than assumed to be one ounce. A guessed weight is a
    fabricated holding.
    """
    lowered = description.lower().replace("-", " ")

    fractions = {
        "1/2": Decimal("0.5"), "1/4": Decimal("0.25"), "1/10": Decimal("0.1"),
        "1/20": Decimal("0.05"), "1/25": Decimal("0.04"), "1/8": Decimal("0.125"),
        "3/4": Decimal("0.75"), "1/undefined": None,
    }

    # Kilo first: "1 kilo" also contains a bare number that the oz rule would
    # otherwise pick up.
    kilo = re.search(r"(\d+(?:\.\d+)?)\s*(?:kilo|kg)\b", lowered)
    if kilo:
        return Decimal(kilo.group(1)) * TROY_OUNCES_PER_KILO, f"{kilo.group(1)} kg"

    grams = re.search(r"(\d+(?:\.\d+)?)\s*(?:gram|gr\b|g\b)", lowered)
    if grams:
        return Decimal(grams.group(1)) * TROY_OUNCES_PER_GRAM, f"{grams.group(1)} g"

    fraction = re.search(r"(\d+/\d+)\s*(?:troy\s*)?oz", lowered)
    if fraction and fraction.group(1) in fractions:
        return fractions[fraction.group(1)], f"{fraction.group(1)} oz"

    ounces = re.search(r"(\d+(?:\.\d+)?)\s*(?:troy\s*)?oz", lowered)
    if ounces:
        return Decimal(ounces.group(1)), f"{ounces.group(1)} oz"

    pounds = re.search(r"(\d+(?:\.\d+)?)\s*(?:lb|pound)", lowered)
    if pounds:
        # Troy, 14.5833 to the pound. Copper bullion is sold by the troy ounce
        # even though the metal is quoted per pound, and every dealer converts
        # with this figure. Briefly changed to 16 on the strength of one quoted
        # per-ounce price that happened to be an avoirdupois conversion.
        return Decimal(pounds.group(1)) * Decimal("14.5833333"), f"{pounds.group(1)} lb"

    return None, None


@dataclass
class Lot:
    vendor: str
    order_number: str
    order_date: str
    description: str
    metal: str
    quantity: Decimal
    unit_weight_oz: Decimal
    weight_label: str
    unit_price: Decimal
    extended_price: Decimal
    spot_price: Decimal | None
    invoice_file: str

    @property
    def total_oz(self) -> Decimal:
        return self.quantity * self.unit_weight_oz

    def premium_fraction(self) -> Decimal | None:
        """How far above spot this was bought, as a fraction.

        Interesting for bullion, and only meaningful because the invoice records
        the spot price at the moment of purchase. It cannot be reconstructed
        later.
        """
        if self.spot_price is None or self.spot_price <= 0:
            return None
        melt = self.spot_price * self.unit_weight_oz
        if melt <= 0:
            return None
        return (self.unit_price - melt) / melt


@dataclass
class Invoice:
    vendor: str
    order_number: str
    order_date: str
    file: str
    lots: list[Lot] = field(default_factory=list)
    accessories: list[tuple[str, Decimal]] = field(default_factory=list)
    unclassified: list[str] = field(default_factory=list)
    shipping: Decimal = Decimal("0")
    savings: Decimal = Decimal("0")
    tax: Decimal = Decimal("0")
    stated_subtotal: Decimal | None = None
    stated_total: Decimal | None = None
    all_line_total: Decimal = Decimal("0")

    def reconcile(self) -> str | None:
        """Checks the parse against the vendor's own sum.

        Only the subtotal is checked. It is the figure that depends on having
        read every line item correctly, which is the thing that can actually go
        wrong, and it avoids false alarms from tax or promotional credits
        appearing in places that vary between vendors.
        """
        if self.stated_subtotal is None:
            return "no subtotal printed, cannot verify"
        delta = (self.all_line_total - self.stated_subtotal).copy_abs()
        if delta > Decimal("0.02"):
            return (
                f"line items sum to {self.all_line_total} but the invoice says "
                f"{self.stated_subtotal} (out by {delta})"
            )
        return None


# --- SD Bullion --------------------------------------------------------------

def parse_sd_bullion(text: str, path: Path) -> Invoice:
    order = re.search(r"Order\s*#\s*(\d+)", text)
    date = re.search(r"Order Date:\s*([A-Za-z]{3}\s+\d{1,2},\s*\d{4})", text)

    order_date = ""
    if date:
        order_date = datetime.strptime(date.group(1), "%b %d, %Y").date().isoformat()

    invoice = Invoice(
        vendor="SD Bullion",
        order_number=order.group(1) if order else path.stem,
        order_date=order_date,
        file=path.name,
    )

    spot = {}
    for metal, value in re.findall(r"(Silver|Gold|Copper|Platinum|Palladium):\s*\$([\d,.]+)", text):
        spot[metal.upper()] = money(value)

    invoice.shipping = money(
        (re.search(r"Shipping & Handling:\s*\$([\d,.]+)", text) or [None, "0"])[1]
    ) or Decimal("0")
    invoice.stated_subtotal = money(
        (re.search(r"Subtotal:\s*\$([\d,.]+)", text) or [None, None])[1]
    )
    invoice.stated_total = money(
        (re.search(r"Grand Total:\s*\$([\d,.]+)", text) or [None, None])[1]
    )

    body = text[text.find("Products"):]
    end = body.find("Subtotal:")
    if end > 0:
        body = body[:end]

    # The description can wrap across lines, and the SKU can wrap mid-token, so
    # the numeric tail is the anchor: SKU, quantity, unit price, then two more
    # currency figures whose order differs from the printed header.
    pattern = re.compile(
        r"([A-Z0-9][A-Z0-9\-]*(?:\s*\n[A-Z0-9\-]+)?)\s+"
        r"(\d+)\$([\d,.]+)\s+\$([\d,.]+)\$([\d,.]+)"
    )

    cursor = 0
    for match in pattern.finditer(body):
        description = re.sub(r"\s+", " ", body[cursor:match.start()]).strip()
        description = re.sub(r"^(Products SKU QtyPrice Tax Subtotal)\s*", "", description)
        cursor = match.end()

        quantity = Decimal(match.group(2))
        unit_price = money(match.group(3))
        first, second = money(match.group(4)), money(match.group(5))

        # Identify the extended price by arithmetic rather than column order.
        expected = (unit_price * quantity).quantize(CENT)
        if first is not None and (first - expected).copy_abs() <= Decimal("0.02"):
            extended, tax = first, second
        elif second is not None and (second - expected).copy_abs() <= Decimal("0.02"):
            extended, tax = second, first
        else:
            extended, tax = first, second

        invoice.tax += tax or Decimal("0")
        invoice.all_line_total += extended or Decimal("0")

        _record(invoice, description, quantity, unit_price, extended, spot)

    return invoice


# --- APMEX -------------------------------------------------------------------

def parse_apmex(text: str, path: Path) -> Invoice:
    order = re.search(r"Order Number:\s*\n?\s*(\d+)", text)
    date = re.search(r"Order Date:\s*\n?\s*(\d{1,2}/\d{1,2}/\d{4})", text)

    order_date = ""
    if date:
        order_date = datetime.strptime(date.group(1), "%m/%d/%Y").date().isoformat()

    invoice = Invoice(
        vendor="APMEX",
        order_number=order.group(1) if order else path.stem,
        order_date=order_date,
        file=path.name,
    )

    invoice.shipping = money(
        (re.search(r"Shipping, Handling & Insurance:\s*\n?\s*\$?([\d,.]+)", text) or [None, "0"])[1]
    ) or Decimal("0")
    savings = re.search(r"Savings:\s*\n?\s*\(?\$?([\d,.]+)\)?", text)
    invoice.savings = money(savings.group(1)) if savings else Decimal("0")
    invoice.stated_subtotal = money(
        (re.search(r"Sub-?Total:\s*\n?\s*\$?([\d,.]+)", text) or [None, None])[1]
    )
    invoice.stated_total = money(
        (re.search(r"Order Total:\s*\n?\s*\$?([\d,.]+)", text) or [None, None])[1]
    )

    body = text[text.find("Order Summary"):]
    end = body.find("Payment Instructions")
    if end > 0:
        body = body[:end]

    # A quantity can wrap mid-number: APMEX prints eight decimal places, so
    # "25.00000000" arrives as "25.0000000" followed by a lone "0". Rejoin
    # those before anything tries to read them as two fields.
    lines = []
    for line in body.splitlines():
        stripped = line.strip()
        if (lines and re.fullmatch(r"0+", stripped) and
                re.fullmatch(r"[\d,]+\.\d{1,7}", lines[-1])):
            lines[-1] += stripped
        else:
            lines.append(stripped)

    headings = {"Item Description", "Spot Price", "Quantity", "Unit Price",
                "Ext. Price", "Order Summary"}

    currency = re.compile(r"^\$[\d,]+\.\d{2}$")
    bare_number = re.compile(r"^[\d,]+\.\d+$")

    description_parts: list[str] = []
    tail: list[str] = []

    def flush():
        """Completes one row once its numeric tail is known.

        Two shapes exist. Metal rows carry a spot price; accessories such as
        coin tubes have no spot at all, which is why keying on a four-field
        tail silently skipped two entire orders.
        """
        nonlocal description_parts, tail
        description = re.sub(r"\s+", " ", " ".join(description_parts)).strip()
        description_parts, values = [], tail
        tail = []

        if not description or len(values) < 3:
            return

        if len(values) >= 4 and currency.match(values[-4]):
            spot_value = money(values[-4])
            quantity = Decimal(values[-3].replace(",", ""))
        else:
            spot_value = None
            quantity = Decimal(values[-3].replace(",", ""))

        unit_price = money(values[-2])
        extended = money(values[-1])

        invoice.all_line_total += extended or Decimal("0")

        metal, _ = classify_metal(description)
        spot = {metal: spot_value} if metal and spot_value is not None else {}
        _record(invoice, description, quantity, unit_price, extended, spot)

    for line in lines:
        if not line or line in headings:
            continue

        if currency.match(line) or bare_number.match(line):
            tail.append(line)
            # A currency value last in a complete tail ends the row.
            if len(tail) >= 3 and currency.match(tail[-1]) and currency.match(tail[-2]):
                flush()
        else:
            if tail:
                flush()
            description_parts.append(line)

    flush()

    return invoice


def _record(invoice, description, quantity, unit_price, extended, spot):
    """Classifies one line and files it as a lot, an accessory, or a question."""
    if not description:
        invoice.unclassified.append("(no description read)")
        return

    if is_accessory(description):
        invoice.accessories.append((description, extended or Decimal("0")))
        return

    metal, other = classify_metal(description)
    if metal is None:
        label = f"{description} [{other}]" if other else description
        invoice.unclassified.append(label)
        return

    weight, weight_label = parse_weight(description)
    if weight is None:
        # Not assumed to be an ounce. A guessed weight is an invented holding.
        invoice.unclassified.append(f"{description} (no weight in the description)")
        return

    invoice.lots.append(Lot(
        vendor=invoice.vendor,
        order_number=invoice.order_number,
        order_date=invoice.order_date,
        description=description,
        metal=metal,
        quantity=quantity,
        unit_weight_oz=weight,
        weight_label=weight_label,
        unit_price=unit_price or Decimal("0"),
        extended_price=extended or Decimal("0"),
        spot_price=spot.get(metal),
        invoice_file=invoice.file,
    ))



def allocate_shipping(invoice: "Invoice") -> dict[int, int]:
    """Splits an order's shipping across its metal lots, in cents.

    Pro rata by line value, with the rounding remainder given to the largest
    line so the parts sum to the invoice exactly. Shipping is part of what was
    paid, so dropping it would understate the cost basis; allocating it with
    naive rounding would leave the total a few cents adrift from the PDF, which
    is precisely the kind of discrepancy that destroys trust in a figure.

    Accessories are excluded from the base, so their share of shipping is not
    charged against metal. That slightly overstates metal's share, which is the
    conservative direction for a cost basis.
    """
    shipping = to_cents(invoice.shipping) or 0
    if shipping == 0 or not invoice.lots:
        return {id(lot): 0 for lot in invoice.lots}

    base = [(lot, to_cents(lot.extended_price) or 0) for lot in invoice.lots]
    total = sum(value for _, value in base)
    if total == 0:
        # No line values to weight by, so split evenly and give the remainder
        # to the first lot.
        each, extra = divmod(shipping, len(base))
        shares = {id(lot): each for lot, _ in base}
        shares[id(base[0][0])] += extra
        return shares

    shares, assigned = {}, 0
    for lot, value in base:
        share = shipping * value // total
        shares[id(lot)] = share
        assigned += share

    remainder = shipping - assigned
    if remainder:
        largest = max(base, key=lambda pair: pair[1])[0]
        shares[id(largest)] += remainder

    return shares


def parse(path: Path) -> Invoice | None:
    """Identifies the vendor by layout, not by name.

    Newer APMEX invoices carry the company name only in a logo image, so it
    never appears in extracted text. Keying on the name silently skipped
    fourteen real invoices, which is a hole in a cost basis rather than a
    cosmetic bug. The column headings are the reliable signal.
    """
    text = "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)

    sd_markers = ("SD Bullion", "Products SKU", "Grand Total:")
    apmex_markers = ("APMEX", "Ext. Price", "Details For Order Number",
                     "Shipping, Handling & Insurance")

    if any(m in text for m in sd_markers):
        return parse_sd_bullion(text, path)
    if any(m.upper() in text.upper() for m in apmex_markers):
        return parse_apmex(text, path)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", type=Path, help="folder holding the vendor subfolders")
    ap.add_argument("-o", "--output", type=Path, help="write lots as JSON")
    ap.add_argument("--detail", action="store_true", help="list every lot")
    args = ap.parse_args()

    pdfs = sorted(args.root.rglob("*.pdf"))
    if not pdfs:
        return print(f"No PDFs under {args.root}") or 1

    invoices, unreadable = [], []
    for pdf in pdfs:
        try:
            invoice = parse(pdf)
        except Exception as exc:
            unreadable.append((pdf.name, f"{type(exc).__name__}: {exc}"))
            continue
        if invoice is None:
            unreadable.append((pdf.name, "vendor not recognised"))
        else:
            invoices.append(invoice)

    print(f"Read {len(pdfs)} invoices from {args.root}\n")

    # Reconciliation first, because nothing below means anything if the parse
    # does not agree with the vendor's own sum.
    failed = [(i, why) for i in invoices if (why := i.reconcile())]
    print(f"Reconciled against the vendor's printed subtotal:")
    print(f"  {len(invoices) - len(failed)} of {len(invoices)} agree")
    if failed:
        print(f"\n  {len(failed)} DO NOT AGREE, so their line items were misread:")
        for invoice, why in failed:
            print(f"    {invoice.file}: {why}")

    if unreadable:
        print(f"\n  {len(unreadable)} could not be read at all:")
        for name, why in unreadable:
            print(f"    {name}: {why}")

    lots = [lot for i in invoices for lot in i.lots]
    accessories = [a for i in invoices for a in i.accessories]
    unclassified = [(i.file, u) for i in invoices for u in i.unclassified]

    print(f"\nLine items:")
    print(f"  {len(lots)} metal lots")
    print(f"  {len(accessories)} accessories, excluded from holdings")
    print(f"  {len(unclassified)} unclassified")

    if unclassified:
        print("\n  Unclassified lines need a decision. Nothing was silently dropped:")
        for file, label in unclassified:
            print(f"    {file}: {label}")

    print("\nHoldings by metal, valued at what was paid:")
    for metal in ("GOLD", "SILVER"):
        owned = [l for l in lots if l.metal == metal]
        if not owned:
            continue
        ounces = sum((l.total_oz for l in owned), Decimal("0"))
        cost = sum((l.extended_price for l in owned), Decimal("0"))
        premiums = [p for l in owned if (p := l.premium_fraction()) is not None]
        unit = WEIGHT_UNIT[metal]
        line = f"  {metal.title():8} {ounces:>12,.4f} {unit:8} cost ${cost:>12,.2f}"
        if premiums and metal != "COPPER":
            average = sum(premiums) / len(premiums)
            line += f"   average premium over spot {average * 100:>6.1f}%"
        elif metal == "COPPER":
            # SD Bullion prints "Copper: $0.10" as its market price, which is
            # neither the per-pound nor the per-ounce figure and looks like a
            # placeholder. A premium computed from it is meaningless, so it is
            # not shown rather than shown and disbelieved.
            line += "   premium not shown, vendor spot unreliable"
        print(line)

    shipping = sum((i.shipping for i in invoices), Decimal("0"))
    item_cost = sum((l.extended_price for l in lots), Decimal("0"))
    accessory_cost = sum((a[1] for a in accessories), Decimal("0"))

    print(f"\nCost basis:")
    print(f"  metal                       ${item_cost:>12,.2f}")
    print(f"  accessories                 ${accessory_cost:>12,.2f}   (not a holding)")
    print(f"  shipping and handling       ${shipping:>12,.2f}")
    print(f"  total paid                  ${item_cost + accessory_cost + shipping:>12,.2f}")

    if args.detail:
        print("\nEvery lot:")
        for lot in sorted(lots, key=lambda l: (l.order_date, l.metal)):
            print(f"  {lot.order_date}  {lot.metal:7} {lot.quantity:>6} x "
                  f"{lot.weight_label:>8}  ${lot.extended_price:>10,.2f}  "
                  f"{lot.description[:52]}")

    # Allocation is checked rather than trusted: every order's shipping must
    # reappear in its lots to the cent.
    allocation, misallocated = {}, []
    for invoice in invoices:
        shares = allocate_shipping(invoice)
        allocation.update(shares)
        expected = to_cents(invoice.shipping) or 0
        actual = sum(shares.values())
        if invoice.lots and actual != expected:
            misallocated.append(f"{invoice.file}: {actual} allocated, {expected} charged")

    print(f"\nShipping allocated across metal lots:")
    if misallocated:
        print(f"  {len(misallocated)} orders do not add up:")
        for line in misallocated:
            print(f"    {line}")
    else:
        allocated = sum(allocation.values())
        print(f"  every order sums to the cent (${allocated / 100:,.2f} spread over "
              f"{len(lots)} lots)")

    if args.output:
        payload = {
            "lots": [
                {**asdict(lot),
                 "quantity": str(lot.quantity),
                 "unit_weight_oz": str(lot.unit_weight_oz),
                 "unit_price_minor": to_cents(lot.unit_price),
                 "extended_price_minor": to_cents(lot.extended_price),
                 "shipping_minor": allocation.get(id(lot), 0),
                 "spot_price_minor": to_cents(lot.spot_price),
                 "total_oz": str(lot.total_oz)}
                for lot in lots
            ],
            "orders": [
                {"vendor": i.vendor, "order_number": i.order_number,
                 "order_date": i.order_date,
                 "shipping_minor": to_cents(i.shipping),
                 "savings_minor": to_cents(i.savings),
                 "tax_minor": to_cents(i.tax),
                 "total_minor": to_cents(i.stated_total)}
                for i in invoices
            ],
        }
        for lot in payload["lots"]:
            lot.pop("unit_price", None)
            lot.pop("extended_price", None)
            lot.pop("spot_price", None)
        args.output.write_text(json.dumps(payload, indent=2))
        print(f"\nWrote {args.output}  ({len(payload['lots'])} lots)")

    return 1 if (failed or unreadable or unclassified or misallocated) else 0


if __name__ == "__main__":
    sys.exit(main())
