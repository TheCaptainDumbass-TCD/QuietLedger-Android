# Screenshots

Drop them straight in this folder. They get committed, so they survive between
sessions and across machines.

## Naming

Anything readable is fine, but this helps me know what I am looking at without
asking:

```
<screen>-<state>-<date>.png

overview-unfolded-2026-09-30.png
debts-folded-2026-09-30.png
payoff-empty-2026-09-30.png
debt-editor-weekly-2026-09-30.png
```

The fold state matters most. Folded and unfolded are effectively two different
layouts, and a problem in one is often fine in the other.

## If it has real balances in it

Use `private/screenshots/` at the repo root instead. That whole tree is ignored
by git and never leaves the machine. Screenshots in this folder are
public.

## Getting them off the phone

`FLAG_SECURE` currently blocks screenshots in the app, including debug builds.
Until that is made debug-only, capture from the emulator window instead, which
is not affected.
