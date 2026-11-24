# UI backlog

Working list, expected to churn. Raised from the first look at the app running
on the Pixel Fold inner screen, 30 September 2026.

## Second review, with data, 30 September 2026

From `*-data-dark-*` screenshots taken on the phone. First look at the app with
real numbers in it and in dark theme.

### Two bugs found, both now fixed

**Interest was counted twice in the payoff total.** The screen showed
outstanding 3254.63 and interest 100.19, then a total of 3455.01. Those do not
add up; the total was over by exactly the interest. Cause: interest was added to
the balance, and then the payment covering it was recorded as principal, so
adding interest on top counted it a second time.

The fix stores what actually leaves your pocket and derives principal from it,
rather than the other way round. Three tests added, two of which failed before
the change, including one built from the exact numbers on screen.

Worth noting why the existing tests missed it. The interest test only asserted
that interest was positive and that the plan took longer. The totals test used
debts with no interest, where the double count is zero. Neither checked that
principal plus interest equals the total, which is the invariant that matters.

**"saves 1 months".** Counts were interpolated straight into strings. Now goes
through a `pluralise` helper with its own tests.

### Confirmed working with real data

- Totals are right: 2000.00 plus 1254.63 is 3254.63, and 200.00 plus 158.00 is
  358.00.
- Interest is being charged and reported per debt. APR shows on the debt rows.
- Snowball ordering is correct, smallest balance first, and the projection gives
  Finance One 9 months and the car 10.
- Dark theme works throughout.
- The rail container colour landed. It now reads as a region rather than
  floating icons.
- Three columns on the dashboard at the unfolded width, as calculated.
- Data entry works on the phone, so whatever stopped it in the emulator is an
  emulator issue rather than an app one.
- Screenshots work on device now that FLAG_SECURE is release-only. The captures
  are 2076 x 2152 and 1080 x 2342, exactly the specified panel sizes.

### Still open from this round

- **Vertical emptiness persists even with data.** Content fills the top half and
  the rest is blank. This is the most visible remaining layout problem.
- **The debt rows are very wide and sparse.** One column of full width cards at
  893dp wastes the space. Two columns, or a list beside a detail pane, would use
  it better.
- **The rail is narrow and the labels are tight.** "Overview" nearly touches
  both edges.
- **The rail panel stops short of the bottom** of the screen rather than running
  its full height, which looks slightly unfinished.
- **Names are not capitalised.** "car" was entered lowercase and displays that
  way. Arguably correct, but worth deciding.

## Confirmed working

- Setup completes and the encrypted database opens on real hardware. The
  Keystore IV fix holds.
- The layout correctly switches to a navigation rail at this width rather than
  staying on a bottom bar.
- Window insets are handled. Nothing hides under the status bar or the gesture
  bar.
- Empty states appear on all three screens with sensible wording.
- Dynamic colour is being picked up from the system theme.

## Problems, in the order I would fix them

### 1. The navigation rail disappears into the background

Material3 gives `NavigationRail` a container colour of `surface`, which is the
same as the screen behind it. The result is three icons apparently floating in
empty space with no sense of being a navigation region, which is most of why the
layout feels unanchored.

Fix: give the rail its own container colour, `surfaceContainer` or similar, so it
reads as a distinct region. Consider a header slot so the top of the rail is not
just dead space.

### 2. Content clumps into the top third and the rest is empty

Content occupies roughly the top 30 percent of the unfolded screen and the rest
is blank. It reads as unfinished rather than spacious.

#### The actual numbers

From the device specifications:

| Display | Pixels | PPI | Density | dp | Width class |
|---|---|---|---|---|---|
| Inner (folding) | 2076 x 2152 | 372 | 2.325 | **893 x 926** | Expanded |
| Cover (external) | 1080 x 2342 | 399 | 2.494 | **433 x 939** | Compact |

So the cover screen is Compact and gets the bottom navigation bar, which is
already what happens and is correct. The inner screen is 893dp wide, which is
**Expanded**, comfortably past the 840dp threshold.

#### Correcting a wrong inference

An earlier note in this file guessed the inner screen was Medium, 600 to 840dp,
on the grounds that the dashboard grid only showed two columns. That was wrong.

`GridCells.Adaptive(minSize = 200.dp)` across the 781dp available after the rail
and padding fits **three** columns at about 252dp each. Measuring the cards in
`overview-unfolded-empty-2026-09-30.png` gives roughly 290px, and at the
screenshot scale of 1.17 px per dp that is 248dp. So the grid was already three
columns wide, and only two were filled because only two cards existed.

The evidence was in the screenshot the whole time, in the card width. Counting
filled cells instead of measuring them produced the wrong answer.

#### What follows from that

A side by side two pane layout **is** viable at 893dp, so it goes back on the
list rather than being set aside.

- Two pane for Debts: list beside detail, using `material3-adaptive`, already a
  dependency.
- Two pane for Overview: cards beside the payoff summary.
- Fill vertically. 926dp of height with content in the top third is the most
  visible problem regardless of panes.
- More dashboard cards once there is more to show. The third column is already
  there and empty.
- Log the size class on startup anyway, so this is measured rather than derived
  from specifications.

### 3. Empty states float in the middle of a large void

Centring a short sentence vertically works on a phone. On a near square screen
it leaves the message adrift. Worth constraining to a narrower column and
placing it higher, and giving the Debts empty state its own call to action
rather than relying on the corner button.

### 4. The floating action button is a long reach

Bottom right corner of a large screen is a stretch. On expanded widths the add
action should move somewhere reachable, either into the rail or into the app bar.

### 5. The title is duplicated

The app bar says "Overview" while the rail also highlights "Overview". One of
them is redundant. Either drop the app bar title on wide layouts or use it for
something useful, such as the total owed.

### 6. The app bar is empty apart from the title

No actions. It is the obvious home for a lock button, and later for settings and
import.

## Not yet decided

- Whether the cover screen needs its own condensed layout or whether the compact
  size class is enough. Untested so far; only the inner screen has been seen.
- Whether a home screen widget is worth doing before or after the remaining
  screens.

## Cleanup owed

- Five deprecation warnings. Four are the `hiltViewModel()` overload in
  `MainActivity` and `QuietLedgerApp`, one is the dropdown anchor in
  `DebtEditorDialog`. Need to check the replacement APIs rather than guess.
- Make `FLAG_SECURE` debug-only so debug builds can be screenshotted for
  feedback while release builds stay protected.

## Emulator configuration

There is no Pixel 11 Pro Fold device profile in the SDK. As of September 2026
the foldable profiles are Pixel 10 Pro Fold, Pixel 9 Pro Fold, the original
Pixel Fold, and three generic fold-in and fold-out definitions.

That is probably fine. The Pixel 9 Pro Fold and Pixel 10 Pro Fold both use a
2076 x 2152 inner panel, which is the same as the Pixel 11 Pro Fold, so either
gives the correct inner geometry. The original `pixel_fold` profile does **not**;
it is 2208 x 1840, wider than tall, and would give a misleading layout.

The cover screens differ slightly between generations, but every plausible
option lands in the Compact width class, so it does not change which layout is
chosen.

### Verify rather than assume

With the emulator running, unfolded:

```bash
adb shell wm size        # expect 2076x2152 for the inner display
adb shell wm density     # expect around 372 to 390
```

Then fold it, using the fold control in the Running Devices panel, and run both
again to get the cover screen numbers.

Converting: `dp = px / (density / 160)`. Width classes are Compact below 600dp,
Medium from 600 to 839, Expanded from 840.

Expected, from the device specifications:

| State | Pixels | dp | Width class |
|---|---|---|---|
| Unfolded | 2076 x 2152 | 893 x 926 | Expanded |
| Folded | 1080 x 2342 | 433 x 939 | Compact |

If the emulator reports 2208 x 1840 unfolded, it is on the old `pixel_fold`
profile and should be recreated on Pixel 9 Pro Fold or Pixel 10 Pro Fold.

### Custom profile, if an exact match is ever wanted

Device Manager, then the option to add a hardware profile. Inner display
2076 x 2152 at 372 dpi, cover 1080 x 2342 at 399 dpi, foldable with a hinge.
Not worth doing unless the stock profiles turn out to differ in a way that
actually matters.

### Also worth logging at runtime

Print the window size class and the window dp on startup so this is measured
from the running app rather than derived from a specification sheet. Cheap, and
it removes a whole category of wrong assumption.

## Direction set 30 September 2026

Two things to work on, in this order:

1. **Make real use of the screen, especially unfolded.** Agreed as the priority.
2. **Give it a more financial look.** Not urgent, but wanted.

### 1. Screen real estate

The problem is vertical, not horizontal. At 893 x 926dp the content fills the
top half and the rest is empty, with or without data.

Concrete options, roughly in order of value:

- **List and detail side by side on the Debts screen.** Tap a debt on the left,
  see and edit it on the right instead of in a dialog. This uses the width, kills
  the cramped `AlertDialog`, and removes the keyboard problem entirely.
  `material3-adaptive` is already a dependency.
- **Two columns on the Overview.** Cards on the left, payoff summary on the
  right, so the second half of the screen carries content rather than nothing.
- **Denser debt rows.** One full width card per debt at 893dp is wasteful. Either
  two columns of cards, or much tighter rows in a table-like list.
- **Something worth putting below the fold.** Recent activity from the change
  journal is the obvious candidate, and the data is already being recorded.
- **Widen the rail and give the labels room.** "Overview" nearly touches both
  edges, and the rail panel stops short of the bottom of the screen.

### 2. A more financial look

"QuietLedger" suggests the answer: a ledger is tabular, aligned and precise, and
quiet means restrained rather than decorative. Current look is stock Material 3
with large rounded cards and dynamic colour from the wallpaper, which reads more
like a consumer utility than a financial tool.

Candidate changes, cheapest and most effective first:

- **Tabular figures.** Compose can be told to use fixed width digits via the
  `tnum` font feature. Amounts then align vertically down a column instead of
  wandering. Single highest value change for the money, almost free, and very
  recognisably financial.
- **Right align all amounts** and keep them in a consistent column.
- **Drop dynamic colour** for a fixed, restrained palette. Dynamic colour means
  the app's character changes with the wallpaper, which is the opposite of a
  considered financial identity. The existing icon already has a palette worth
  building on: deep navy, with red, amber, blue and green accents.
- **Tighter radii and less elevation.** Squarer, flatter, more table than card.
- **Semantic colour** applied consistently: what is owed, what is paid, what
  needs attention. Currently only errors are coloured.
- **A typeface decision.** Stock Roboto is fine but anonymous. A more
  characterful text face with tabular numerals would do a lot.
- **Denser spacing.** Financial tools show more per screen; the current padding
  is generous.

### Taste questions for Dennis

Needed before doing the visual work, since these are preferences not facts:

1. Dark as the primary identity, or proper light and dark parity?
2. Keep dynamic colour matching the wallpaper, or a fixed brand palette taken
   from the app icon?
3. Denser and more tabular, in the manner of a spreadsheet or a brokerage
   statement, or keep the airier card look and just fill the space better?

## Parity principle, decided 6 October 2026

How closely the Android screens follow the desktop ones. This applies to every
remaining screen, not just the next one.

**Primary rule: same data, better layout.** Every field and every calculation the
desktop has must be present. The arrangement is designed for the fold rather than
copied from a desktop tab, as already done for Debts and Payoff.

**Secondary rule: fix what is wrong, and say so.** Where the desktop behaviour is
incorrect rather than merely awkward, Android does the correct thing. Each
divergence gets written down, so the two can be reconciled later and so nobody has
to reverse engineer why the numbers differ.

The second rule has already come up and will again. The desktop payoff engine
uses an approximate ceiling, treats every month as 30 days, lets a zero-payment
debt corrupt later balances, converts weekly payments as `weekly * 4`, and stores
money as SQLite REAL. Android does none of those, which means **the two apps will
legitimately disagree on some figures**. That is the intended outcome, and the
differences are recorded in the desktop SESSION_NOTES.

What this rule is not: licence to redesign silently. A divergence that is not
written down is a bug report waiting to happen when the two are compared.

## Recurring expenses, 6 October 2026

### Why these are not debts

A debt has a balance that amortises to zero. A recurring expense repeats until
cancelled and has no balance. Mortgage is a debt; rent, electric and insurance
are expenses.

The desktop app keeps both in one `debts` table and separates them with two
hardcoded category sets, `SNOWBALL_CATEGORIES` and `RECURRING_CATEGORIES`, so
every query has to remember to filter. Android uses separate tables, which is why
nothing in the payoff engine can see an expense at all. There is a test asserting
that end to end, because the failure mode is a debt-free date that silently
absorbs payment capacity already committed to the electricity bill.

### Granular categories with a rollup

The categories asked for are all present as specific entries rather than a
"Utilities" bucket: rent, lease, HOA, property tax, electric, natural gas,
propane, heating oil, water, sewer, trash, internet, mobile, landline, homeowners
and renters insurance, auto, life and umbrella, health, dental and vision,
vehicle lease, fuel, registration, public transport, subscriptions, streaming,
lawn, cleaning, childcare, pet care, savings transfer, charity.

Each carries an `ExpenseGroup` so reporting can roll up. A single flat
"Utilities" category cannot answer "what am I paying for electricity", which is
most of the reason to track this. Conversely, grouping analytics by thirty-odd
categories would produce thirty rows of a few dollars. The two-level split gets
both.

Legacy coarse names are mapped in `fromStored`, so a desktop import lands
somewhere sensible rather than collapsing into OTHER.

### The escrow trap

A mortgage payment often includes escrow for property tax and homeowners
insurance. Tracking the mortgage as a debt and then adding those as expenses
counts them twice. The editor warns when either of those two categories is
chosen. Worth revisiting if a mortgage record ever grows an explicit escrow
field.

### Still to do here

- Expenses are not yet in the dashboard's monthly outlay, which currently counts
  debt payments only. The total commitment figure should include both, clearly
  separated, since they behave differently.
- No two-pane layout for the unfolded display yet, unlike Debts.
- Due-day is captured but nothing uses it. A "what is due this week" view is the
  obvious use.

## Data appearing to vanish, 6 October 2026

Reported as "the main screen seems to reset and come back randomly, sometimes it
has the test data, sometimes it is as if it were new". Two faults combined.

**The flow bound itself to one database instance.** `databases.require()` was
called when a flow was built rather than when it was collected, so a flow
created at view model construction watched that instance for life. Locking and
unlocking creates a new instance, leaving the old flow attached to a closed
database that never emits again. Fixed by deferring resolution into
`flow { emitAll(...) }`, so every subscription resolves whatever database is
open at that moment.

**`catch` completes a flow, and that is what made it random.** Every view model
ended with `catch { emit(emptyState) }`. In Kotlin Flow, `catch` emits and then
the flow terminates, so `stateIn` held the empty value and would not retry until
every subscriber went away for the `WhileSubscribed` timeout of five seconds.
Move away, wait, come back, and the data reappears. Now there is a short
retry first, and a failure that survives it sets an `unavailable` flag.

**The failure mode was the worst part.** Catching "database is locked" and
rendering "you have no debts" tells someone their financial records have been
lost. They never had been; they were on disk the whole time and briefly
unreachable. Every screen now distinguishes the two, and the notice says
explicitly that the data is still there.

Four regression tests cover it, including a holder that swaps instances the way
`DatabaseProvider` does on lock and unlock.

### Worth doing next on this

- No auto-lock on backgrounding exists yet. When it is added, it will exercise
  this path constantly, so the fix needed to land first.
- `WhileSubscribed(5_000)` is doing a lot of quiet work. Worth revisiting
  whether the dashboard should keep its subscription alive while the app is
  foregrounded.

## Importing from the desktop app, 6 October 2026

`scripts/desktop-to-android.py` reads a desktop `finance_data.db` or an
encrypted `.ftbackup` and writes a `.qlbk` the Android app restores. It runs on
the machine holding the data; no passphrase is printed and amounts are hidden
unless `--detail` is passed.

### What it has to reconcile

**One table becomes two.** The desktop keeps debts and recurring bills together
and separates them with hardcoded category sets. Credit, Vehicles, Taxes and
Back Taxes become debts; Housing, Insurance, Utilities, Services and Savings
Transfer become recurring expenses.

**Two payment columns become one.** The desktop has `weekly_payment` and
`monthly_payment` and **adds** them as `weekly * 4 + monthly`. Android holds one
amount and one frequency, which is the correct model and makes it impossible to
enter both. A legacy row using both is folded into a single monthly figure using
the real 52/12 weeks per month, so the converted total reads slightly higher
than the desktop displayed. That difference is the desktop bug, not a conversion
error, and the script reports every row it affects.

**A coarse category becomes a specific one.** The desktop records "Utilities",
Android records "Electric". The gap is bridged by matching the entry name.

### On guessing, and why the hint list stays small

Manufacturer and model names were added to the hints and then removed. The
reasoning is worth keeping, because it applies to any heuristic of this kind:

- The list is unbounded and regionally biased, and ages as models change.
- Several makes build both cars and motorcycles, so some entries cannot be
  resolved from the make at all.
- Real entries carry nicknames and typos. A row like
  `Truck Insurence`, with a misspelling, is something no list would match.

So the hints cover generic words only: electric, water, propane, internet, rent,
lease, motorcycle, and so on. Anything unmatched falls back to an explicitly
vague category, is listed in the report as needing a look, and takes one tap to
fix in the app.

The principle: **a confident wrong guess costs more than an honest unknown.**
Correcting something labelled "other" is trivial. Noticing a plausible-looking
mistake buried among correct ones is not, and the whole point of this app is
that the numbers can be trusted.

### Insurance against care

A telehealth subscription is a service, not cover. They were briefly mapped
together and separated again, because mixing them makes "what does my insurance
cost" wrong. Health, dental and vision insurance sit in the insurance side of
the Health group; medical, pharmacy and gym are costs of care.

### Not transferred

Investments, savings accounts, precious metals and tax years. Those screens do
not exist yet, and nothing is removed from the desktop, so they are still there
when they do.

### Format drift

There are now two implementations of the backup envelope, Python and Kotlin.
`DesktopImportTest` restores a committed fixture written by the Python side
using the real Kotlin `BackupRepository`, so a divergence fails a test rather
than producing a file that will not open.

## Payment rollover is opt-in, 6 October 2026

Reported as the payoff tab calculating incorrectly: a balance of 9,985.97 with
"just over 500 a month" showing as paid off in nine months.

The arithmetic was right. The debt pays 125 weekly, which is 541.67 a month, so
on its own it takes nineteen months. A second debt paying 375 weekly, 1,625 a
month, on a smaller balance cleared at month five and its payment then rolled
into the first. Nine months is the correct snowball answer.

Being right is not sufficient. The screen showed a number that could not be
checked against the two figures next to it, which is indistinguishable from a
bug and corrodes trust in every other number on the page.

### The decision

Rollover now defaults to **off**, and that is a product decision rather than a
fix. Assuming a cleared debt's payment goes to the next one is a prediction
about behaviour, not arithmetic. The money might go to savings, to investing, or
nowhere in particular. Presenting a debt-free date that silently depends on a
choice the user never made is a forecast dressed as a fact.

The default is now what is actually happening: each debt on its own payment,
plus interest where an APR is recorded. Rollover is a switch on the payoff
screen, which states what it would save before it is turned on, and the headline
says which assumption is in play.

### Also added

`DebtProjection.monthsOnOwnPayment`, so when rollover is on the screen can show
both numbers rather than one unexplained one. It is simulated rather than
divided, so interest and the final part payment behave identically to the main
projection; a self-contradicting explanation would be worse than none.

Two tests cover the reported case directly, with the real figures, asserting
nineteen months by default and nine with rollover on.

### A bug found while building it

The helper that simulates a debt alone called `pay()` with the full payment.
That method subtracts whatever it is handed and does not clamp, because the main
loop clamps before calling it. The balance went negative on the final month and
the debt never registered as cleared, so the figure came back null. Caught by
the new test rather than by reading the code.


## Import corrections, 6 October 2026

Importing replaces everything, so a category corrected in the app is lost on the
next import. During testing that means re-importing undoes the tidying every
time, which is enough friction to stop someone re-importing at all.

`category-overrides.json`, read from beside the source database, maps an entry
name to a category and is applied on every run. Values are validated against the
`RecurringExpenseCategory` enum by parsing `Categories.kt`, so a typo or a
renamed category fails loudly instead of silently landing in OTHER. Parsing the
real enum rather than duplicating the list is deliberate: a second copy would
drift.

This is also the proper answer to the importer guessing wrong. Rather than
growing the heuristics until they cover every vehicle nickname, the guess only
has to be good enough that the overrides file stays short. On the real data that
is seven of twelve from name hints and five stated once in the file, with
nothing left needing a look.

An override that matches nothing is reported rather than ignored. Without that,
correcting a typo in the desktop app silently detaches the override and the
entry quietly reverts to a guess, which is the same class of quiet failure as
the empty-screen bug: wrong, plausible, and invisible.

That warning is also why the file holds one key per entry rather than every
likely spelling. Defensive duplicates would mean one of them is always reported
as unused, which trains you to ignore the warning. One key, and a loud failure
if it stops matching.

## Overview layout, noted 6 October 2026

### Equal card sizes

All cards on the Overview should be the same size, except the headline card at
the top which stays larger.

Currently they are in a `LazyVerticalGrid` with `GridCells.Adaptive`, so each
card sizes to its own content and a row of three ends up ragged. Fixing it means
giving the stat cards a fixed height, or a shared aspect ratio, so the grid reads
as a grid. Worth checking on both the folded and unfolded widths, since three
columns at 893dp and one at 433dp will want different proportions.

### The headline card becoming net worth

Requested: the top card shows total debt now, and should become net worth once
net worth turns positive.

Two things to settle first, which is why this is a discussion rather than a task.

**It is blocked on assets existing.** Net worth is assets minus liabilities, and
the Android app currently has no investments, savings accounts or precious
metals. The figure cannot be computed until at least one of those screens is
built, so this naturally follows Investments and Savings rather than preceding
them.

**Switching on a threshold has a failure mode worth avoiding.** If the headline
metric changes identity when net worth crosses zero, then sitting near zero makes
it flip between "Total owed" and "Net worth" from one week to the next. A
headline figure that changes meaning is hard to trust and impossible to compare
against last month.

Three options, in rough order of preference:

1. **Show net worth as soon as any asset is recorded**, even when negative. A
   negative net worth is still a net worth, and is the honest number. Total owed
   moves to the caption. Nothing flips based on a threshold.
2. **Show both, always.** Net worth as the headline, total owed beneath it.
   Loses the simplicity of one big number.
3. **Switch at zero as originally described.** Simplest to describe, but has the
   flip-flopping problem above.

Option 1 is the one I would argue for, and it means the behaviour changes once
when assets are first entered rather than every time a balance moves.

**Decided 7 October 2026: split the top card in two.** One half for what is
owed, one for net worth, both always shown. That removes the threshold problem
entirely, since neither figure ever changes identity.

Owed is displayed as a negative number. Debt is money you do not have, and a
bare positive figure sitting beside a net worth reads as though it were an
asset. The sign makes the two halves add up on sight, which is the reason to put
them together at all.

Net worth is shown even while negative, and even with no assets recorded. With
nothing owned, a net worth equal to minus the debt is the truthful answer; the
caption reads "no assets recorded yet" so the matching figures are explained
rather than looking like a duplicated number.

The halves sit side by side above 460dp and stack below it. A currency figure at
this size needs roughly 200dp, which half the folded cover screen does not
give it.

`assetsTotal` is wired through the dashboard state now and is zero until
Investments and Savings exist, so net worth becomes correct the moment those
screens start recording things rather than needing the card revisited.

### Equal card sizes, done

Stat cards share a `heightIn(min = 124.dp)` floor rather than a fixed height, so
a row reads as a row while still growing if the system font scale is turned up. A
fixed height would look tidy at the default scale and clip text for anyone who
needs larger type. Values are one line with an ellipsis, captions two, which is
what keeps the floor sufficient in practice.

## Savings, 7 October 2026

Assets are savings plus investments plus precious metals. Savings came first
because it is the only one of the three that needs no valuation: the balance is
simply known. Investments store a quantity and a purchase price, and the desktop
fetches a current price with yfinance, which this app cannot do because it has no
network permission. That is what the weekly Fidelity CSV is for, so investments
wait for that import path.

Built: entity, migration 2 to 3, repository, screen, editor, dashboard wiring,
backup format, and desktop import. Thirteen tests.

### Deposits and withdrawals are events, not edits

The screen offers Deposit and Withdraw rather than only editing the balance.
Asking for the amount moved means the journal records what happened; asking for
the new balance records only where things ended up. A balance that fell is the
question worth asking about later, and without the amount and a note there is
nothing to ask against. A withdrawal is a negative movement rather than a
separate concept, so there is one code path and one set of tests.

### Deliberate rules

- **A savings balance cannot be negative.** Zero is fine. Negative would quietly
  reduce net worth as though the account were a debt, which is what the debt
  table is for.
- **APY in basis points**, same reasoning as money in cents. Interest is never
  applied automatically; the balance changes when the user says it changed,
  because this app does not invent transactions that did not happen.
- **Closing removes an account from net worth but keeps its history.** Closed
  money is not money you have, but what was saved still happened.
- **Goal progress is capped at 1** so a bar cannot overflow, with a separate
  flag for having exceeded it, so the display is bounded without the data
  lying.
- The bank field says explicitly never to put an account number in it, because
  a field called "bank" next to a balance invites exactly that.

### Navigation is now at its ceiling

Overview, Debts, Expenses, Savings, Payoff is five bottom-bar destinations,
which is the Material maximum. Investments and precious metals cannot simply be
added as a sixth and seventh.

The likely answer is grouping the asset types under one destination with
internal sections. Deliberately not built yet: an empty shell for screens that
do not exist would be worse than deciding it once Investments is real and its
shape is known.

### Icon

`Icons.Default.Savings` is in the extended icon set, which is not a dependency.
Star from the core set is the least bad of what is available and should be
revisited if the extended set is ever added for other reasons.

## Investments, 7 October 2026

Scope settled: one account, a Fidelity Traditional IRA, imported from the weekly
positions CSV. That removes the hardest problem by itself. The export already
carries the values Fidelity calculated, so there is no price lookup and the lack
of a network permission costs nothing.

### Read only, on purpose

There is no add or edit for a holding. The export is the broker's own statement
of what is held and what it is worth; hand-editing it would mean the total no
longer corresponds to anything. Updating means importing a newer file.

### An import replaces, it does not merge

A positions file is a snapshot. A symbol absent from a newer file has been sold,
so merging would leave it in the list and inflate net worth indefinitely. The
replace is scoped to the account named in the file, so importing one account's
export cannot delete another's.

There is a test for exactly that failure: import the real export, remove one
position from the file, import again, and assert the symbol is gone and the
total fell.

### Honesty about staleness

`as_of` comes from the file's "Date downloaded" footer, not from the phone's
clock at import time. The screen states the date and how long ago it was, and
past a fortnight suggests importing a newer file. A week-old valuation is a
different claim from a live price and should not be presented with the same
confidence as a bank balance.

### Deliberate representations

- **Quantity in millionths**, as a scaled integer. Reinvested dividends produce
  fractional shares, and a float quantity times a float price is how a portfolio
  total ends up disagreeing with itself.
- **A missing cost basis stays null.** Fidelity sends "--" for some positions.
  Zero would imply the entire value is gain, which is a fabricated number. The
  row says "no cost basis" instead, and the combined gain excludes it.
- **Gain is derived** from cost basis and value rather than read from the file's
  percentage column, so it cannot disagree with the money shown beside it.
- **SPAXX is cash, not a holding.** It has a value but no quantity or price, and
  it appears in its own section.

### Navigation resolved

Savings and investments now share an **Assets** destination with a segmented
control, which keeps the bottom bar at five items: Overview, Debts, Expenses,
Assets, Payoff.

They are separate sections rather than one merged list because the figures mean
different things: a savings balance is simply known, a holding is a valuation as
of a date. Each tab shows its own total so switching is not needed just to
compare them.

Precious metals are still outstanding and have the same spot-price problem that
investments avoided by using the broker's own numbers. There is no equivalent
export, so it will need either a manually entered value per holding or a
deliberate decision to value at purchase price and say so.

## Precious metals, 7 October 2026

Scope settled: gold, silver and copper, from the original SD Bullion and APMEX
invoices, tracking cost and fees. The holding is static, so this is a record of
what was bought rather than a live position.

### Valued at cost, and labelled as such

There is no spot feed and no network permission, so a market value would be a
number the app cannot know. "Held, at cost" is a smaller claim and a true one,
and for a holding that is not being traded it is also the more useful figure.

The invoice's printed spot price is stored per lot, which is the only way a
premium over spot can ever be known. It cannot be reconstructed afterwards.

### Lots, not totals

Stored as purchase lots rather than a running total per metal. Summing to
"200 oz of silver" would discard the cost basis, which is the thing worth
tracking.

### The parser, and why it can be trusted

`scripts/parse-metal-invoices.py` reads the PDFs with pypdf, in a venv outside
the project so it is not a build dependency.

**Every invoice is reconciled against its own printed subtotal.** The vendor
already did the arithmetic, so the check is free and far stronger than
inspecting a sample. 109 of 109 agree. Any that did not would be reported, not
accepted.

**Shipping is allocated pro rata by line value**, with the rounding remainder
given to the largest line so the parts sum to the invoice exactly. Checked per
order, to the cent. Shipping is part of what was paid; omitting it understates
the basis, and sloppy rounding leaves the total a few cents from the PDF, which
is exactly what destroys confidence in a figure.

### Three bugs the numbers caught

**The word "APMEX" is not in newer invoices.** It is only in a logo image, so
it never reaches extracted text. Keying on the vendor name silently skipped 14
real invoices. Detection is now by column headings. A silent skip here is a hole
in a cost basis, not a cosmetic bug.

**Accessory-only orders have no spot price column**, so a pattern expecting four
numeric fields matched nothing and two whole orders parsed as empty. The
reconciliation check is what surfaced it; without it they would have looked fine.

**`\btube\b` does not match "Tubes".** A 1/10 oz gold coin tube was counted as a
tenth of an ounce of gold. Accessory descriptions name a metal and a weight
precisely because they describe what the accessory is *for*, which is the trap.
Caught by noticing gold rise by exactly 0.1 oz between runs. Patterns are now
plural-tolerant, and the total paid stayed identical while the item moved from
metal to accessories, which is the right outcome.

### Deliberate rules

- **Accessories are excluded from holdings** but their cost is still reported, so
  the total paid reconciles.
- **A description with no weight is reported, not assumed to be one ounce.** A
  guessed weight is a fabricated holding.
- **An unrecognised metal is dropped by the Kotlin mapper, not coerced.** Filing
  platinum under silver would corrupt both the ounce count and the basis.
- **Premium uses melt value, not the spot price directly.** A 1/10 oz gold coin
  at $250 against gold at $2,000 is 25% over melt; comparing to spot directly
  would report it as 87% below.
- SD Bullion prints `Copper: $0.10` as its market price, which looks like a
  placeholder on their side. The parse is faithful to the invoice, so the copper
  premium reads implausibly high. Worth remembering before treating that number
  as meaningful.

### Assets now has three sections

Savings, Retirement and Metals, which keeps the bottom bar at five destinations.
Separate sections rather than a merged list because the three are different
kinds of fact: a bank balance is known, a holding is a valuation as of a date,
and bullion is what was paid. Net worth adds them, but presenting them
identically would imply a confidence two of them do not have.

## Metal valuation, 7 October 2026

Spot prices are entered by hand, not fetched.

### Why not automatic

The app declares no `INTERNET` permission, so the operating system itself
guarantees it cannot send financial data anywhere. That is enforced, not
promised, and it is the single strongest security property this app has.

Free sources exist that need no account, and the privacy cost would not be data
leaving; it would be that the guarantee stops being structural and becomes a
matter of trusting the code. For three numbers, on a holding that is not being
traded, that is a poor trade. Revisit only if the holding becomes active.

### All or nothing

A market value appears only once **every** held metal has a price. Valuing part
of the holding at market and the rest at cost produces a figure that corresponds
to nothing and cannot be checked against anything.

### Never dressed up as live

`enteredAt` is stored per price. The card reads "Worth, at your prices" and says
how old the oldest one is, suggesting an update past a month. The headline is
market value when complete and cost when not, and each is labelled as what it
is. Clearing a field removes the price rather than keeping a stale one, so the
valuation returns to being honestly incomplete.

Net worth follows the same rule: market when complete, cost otherwise, with the
Overview card captioned either "at your spot prices" or "at cost".

### Stored in the database, not preferences

So it is encrypted at rest with everything else and included in backups.
Replacing lots from a fresh invoice parse deliberately keeps the prices, since
they are a separate thing the user supplied.

## Metal quoting units, 7 October 2026

Gold and silver trade **per troy ounce**. Copper trades **per pound**, and
always has. The first version of the spot price dialog asked for all three per
troy ounce, so entering copper's quoted price would have valued it about 14.6
times too high.

Each field now asks for the unit that metal is actually quoted in, converts to
per troy ounce for storage so every valuation shares one unit, and converts back
for display so the editor shows what was typed.

`TROY_OUNCES_PER_POUND = 14.583333333`, from 453.59237 g over 31.1034768 g.

### Copper: quoted per pound, weighed in troy ounces

Copper is quoted **per pound** on COMEX, unlike gold and silver which are quoted
per troy ounce. So the dialog asks for copper per pound.

Weight, though, is **troy for all three**. Copper bullion rounds and bars are
sold by the troy ounce even though the metal trades per pound, and every dealer
converts with 14.5833 troy ounces to the pound. JM Bullion, FindBullionPrices,
PeerMetals, BullionTally and MetalCharts all publish per-pound over 14.5833,
giving about $0.45 an ounce at $6.65 a pound.

**This was got wrong in both directions, and the second mistake was worse.**
First every metal was assumed troy with no thought given to it, which happened
to be right. Then a single quoted figure of $0.41 to $0.42 an ounce appeared,
which matches 6.65/16, and on that evidence alone the whole model was switched
to avoirdupois: correct code replaced with incorrect code, with new tests
written to lock the error in. The source had simply used the avoirdupois ounce,
which the bullion trade does not.

The lesson is not "check against external data", which was already the lesson
from the invoice reconciliation. It is that a number which *fits* an explanation
is not evidence for it. 6.65/16 = 0.4156 fits; so does 6.65/14.5833 = 0.456
against a different published figure. Establishing the convention is what
settles it, and that needed five sources rather than one.

### Prices are stored as quoted, not normalised

A per-pound copper price is about $6.65; per troy ounce it is about $0.456.
Storing the normalised per-ounce figure means rounding to $0.46, and at 19
ounces that is $8.74 against a true $8.66, over 1% lost, on the figure the
screen presents as a valuation.

So the quoted price is stored exactly as entered, and conversion happens when a
holding is valued, with ten decimal places of intermediate precision and
rounding only at the final cent. It also means the editor shows precisely what
was typed.

## Session notes, 7 October 2026

### Where metals stand

The Metals card shows the total and the gain, but **only once a spot price
exists for every metal held**. Until then it shows cost and names which metals
are missing a price. That is deliberate, because part at market and part at
cost is a figure that corresponds to nothing, but it does mean the feature
looks absent
until three numbers are typed in.

Prices verified against five sources each:

```
Gold     per troy ounce   $4,143 - $4,187 across sources, 13 hours
Silver   per troy ounce   $61.27 - $61.99
Copper   per pound        $6.54 - $6.71, over 14.5833 for per-ounce
```

### Spot is not a sale price

HJ Bullion publishes bid $61.37, ask $61.62, and "we pay $56.46/oz, 8% under
bid". Spot is effectively the ask, so a holding valued at spot overstates what
would actually be realised, about $1,100 on 200 ounces of silver.

The card now says so in one line rather than modelling it. A realisable-value
percentage was offered and deliberately not built without agreement.

### Still outstanding

- **The About menu entry is unverified on hardware.** Biometric unlock is on, so
  it could not be checked from adb. Needs a human to confirm four menu entries.
- **Retirement positions were lost** and need re-importing from the Fidelity CSV.
  What deleted them is still unknown. The About screen now records read failures
  in memory, so a recurrence should leave evidence.
- A backup taken **before** today did not contain holdings at all. Take a fresh
  one after re-importing.

### What this evening actually taught

Three regression tests written tonight passed with their bug still present. The
only reason that is known is that each was checked by reintroducing the fault,
and that practice should be mandatory rather than occasional: a test that has
never failed has not been shown to test anything.

Two claimed fixes were retracted the same way. The SQLCipher key-zeroing change
and the re-import transaction. Both are defensible on their own merits and
neither fixed the reported fault.

The copper unit was changed from troy to avoirdupois and back. A single quoted
figure fitted the wrong model, and fitting is not evidence; five sources settled
it. Correct code was replaced with incorrect code, with tests written to lock
the error in, which is the worst shape a mistake can take.

Finally, 226 JVM tests passed while the installed app showed nothing on every
screen, because those tests substitute plain in-memory Room for SQLCipher, Hilt
and the migrations. Device access via adb and the About screen both exist to
close that gap.

## Retirement holds whatever the export contains, 9 October 2026

The Fidelity export carries two accounts: `Traditional IRA` with seven
positions, and `Individual - TOD` holding a little cash. Both import, and both
count towards the figure shown under **Retirement**.

Decided to leave it. The taxable account is near empty and stays that way until
the debts are cleared and the IRA's annual contribution is met, so the label is
accurate in substance today.

**Revisit when the second account starts receiving money.** At that point
"Retirement" is actively wrong for part of the total, and the options are:

- import only the IRA and ignore other accounts
- rename the section to something that covers both
- show per-account subtotals, which is the most honest and the most work

The third is the right answer once there is anything to split. The screen already
separates positions from cash, so grouping by account is a natural extension
rather than a redesign.

Worth noting the import deliberately replaces **all** holdings regardless of
account, which is what makes a multi-account export safe. Scoping the delete to
one account name is what caused the doubling.

## Scope: what each app is for, 9 October 2026

Clarified by the user, and it supersedes the original brief.

The first instruction was "I want the exact same functionality/features". That is
no longer the goal. The split is now:

- **Android** is the day-to-day app and holds the accurate data. It answers
  "where do I stand" and guides paying the debt down. Updates are quick and
  coarse: read a balance off a lender's screen and type it in.
- **Desktop** keeps the granular detail and will be revised later. It is also
  the reference for what was being tracked historically.

### What this changes

**The remaining "parity gaps" may not be gaps.** Tax years and analytics exist on
the desktop and have no Android equivalent. That was on the list as missing
work; under this split it is only missing if the user asks for it. Nothing should
be built to close a gap for its own sake.

**Granularity belongs on the desktop.** A request that implies detailed
record-keeping on the phone is worth questioning before building, because the
phone's job is a clear view and a fast edit.

### A concrete example of the new rule

`DebtRepository.recordPayment()` is written, tested, and reachable from
`DebtsViewModel`, but **no screen exposes it**, the same shape of gap as Delete
was. Savings got Deposit and Withdraw buttons for exactly this reason, and debts
never got the equivalent, which is an inconsistency introduced without noticing.

Deliberately left unexposed. IRS and lender balances are read off a screen rather
than calculated, so "how much did you pay" is the wrong question to ask; editing
the balance is both simpler and closer to how the number is actually obtained.
The edit is journalled against the balance field either way, so the history is
not lost.

Kept rather than deleted: it is tested, costs nothing, and an importer may want
it. Recorded here so it reads as a decision rather than an oversight.

## Notes from testing, 21 October 2026

### Fixed now, because both got in the way of testing

**No way out of Security or About.** Those are reached from the overflow menu
rather than the bottom bar, and `TopAppBar` had no `navigationIcon` at all, so
nothing on screen offered an exit. Force-quitting the app was the only way back,
which is about as close to a trap as navigation gets. A back arrow now appears
for menu-opened destinations only.

**Owing nothing was reported as failure.** `clearsCompletely` was
`debtFreeDate != null`, and a plan with no debts has no date, so a zero balance
produced "Not on track" in red on both the Overview and Payoff. Being debt free
is the best possible state and the app called it a problem.

Now three distinct states rather than two: nothing owed, a date to work towards,
and something that never clears. `PayoffCalculator` already excluded zero-balance
debts, so the fault was entirely in how an empty plan was interpreted.

Worth recording that the first version of this fix **would have crashed the
Payoff screen**: `plan.debtFreeDate!!` is force-unwrapped inside the
`clearsCompletely` branch, which the change made true for a plan with no date.
Caught by reading the screen before building, not by a test.

### Next session

**SEP IRA replacing the Traditional IRA.** The export will carry a different
account name. No code change is expected: an import replaces every holding
regardless of account, and the screen groups by whatever names appear. Worth
confirming against a real export rather than assuming, since the last
account-related assumption caused the doubling.

**Emergency fund screen.** One, three and six months of expenses, and where the
savings stand against each. The desktop has this. The monthly figure to size it
against already exists as the recurring-expense total, and arguably should
include debt payments too, which is worth deciding before building.

### Not acted on

Lint reports `sqlcipher-android 4.19.1` is available, currently on 4.19.0.
Deliberately not upgraded mid-testing: changing the encryption library while
someone is validating their data is a poor trade. Check the changelog for
anything security related before taking it.
