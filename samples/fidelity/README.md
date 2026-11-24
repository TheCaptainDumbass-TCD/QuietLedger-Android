# Fidelity CSV samples

The importer is written against the real export format, so it needs a real
sample to work from. It must not contain anything that identifies an account.

## What to remove before the file lands here

Fidelity's positions export usually carries these. Scrub all of them.

1. **Account Number.** Even the masked form (`X12345678`, `...4567`). Replace
   the whole column with `REDACTED`.
2. **Account Name.** Things like "Dennis Ayotte Roth IRA" or a nickname you set.
   Replace with a generic label such as `Brokerage` or `Roth IRA`.
3. **Quantity, Last Price, Current Value, Cost Basis, Average Cost Basis.**
   Replace the numbers with made up ones. Keep the formatting exactly as
   Fidelity writes it, including the dollar signs, the commas in thousands, the
   percent signs, and any `n/a` or `--` placeholders. The formatting is the part
   the parser cares about. The values are not.

Keep the symbols. Those are already known and they help verify the parser
handles a money market fund like SPAXX differently from an index fund.

## What to keep exactly as it came out

- The header row, character for character, including any leading blank lines.
- Row order and the total number of columns.
- The trailing disclaimer text Fidelity appends after the data. The parser has
  to know to stop reading when it reaches it, so it needs to see it.
- The original line endings and encoding. Do not open and resave in a program
  that rewrites them if you can avoid it.

## Naming

Save it as `positions.redacted.csv` in this folder. The `.gitignore` blocks
every other CSV in the repo to keep real exports from being committed by
accident, and allows this one through by name.

## About this sample

The current sample is made up. The funds, quantities, prices, values and the
download date are invented, and none of them come from a real account. Only the
header, the column layout, the quoting, the number formatting and Fidelity's
footer text match a real export, because those are what the parser reads.

## If you would rather not redact by hand

Drop the untouched file in `private/` at the repo root instead. That whole
folder is ignored by git and never leaves the machine. The redacted sample can
be generated from it afterward.
