#!/usr/bin/env python3
"""
Regenerates the Material 3 colour schemes in
app/src/main/java/com/quietwire/quietledger/ui/theme/Color.kt

Hues come from the app icon. Tonal ramps are generated rather than hand-picked,
and every on-colour pair is contrast checked against WCAG AA before anything is
written out. If you change a hue or a tone, run this and read the output: it
will tell you which pairs fail rather than letting a hard-to-read combination
ship.

    python3 scripts/generate-palette.py

Writes Scheme.kt.part next to itself for pasting into Color.kt.
"""
import colorsys

def hsl(h,s,l):
    r,g,b = colorsys.hls_to_rgb(h/360.0, l/100.0, s/100.0)
    return (round(r*255), round(g*255), round(b*255))
def hx(rgb): return "#%02X%02X%02X" % rgb
def lum(rgb):
    f=lambda c:(c/255)/12.92 if c/255<=0.03928 else (((c/255)+0.055)/1.055)**2.4
    r,g,b=[f(v) for v in rgb]; return 0.2126*r+0.7152*g+0.0722*b
def contrast(a,b):
    la,lb=lum(a),lum(b); hi,lo=max(la,lb),min(la,lb); return (hi+0.05)/(lo+0.05)

P=(210,44); S=(46,55); T=(153,70); E=(0,65); N=(210,7); NV=(210,14)
def t(pal, tone):
    h,s = pal
    if tone == 0: return (0,0,0)
    if tone == 100: return (255,255,255)
    return hsl(h,s,tone)

light = dict(
    # Tones solved so white text clears 4.5:1. Gold and green are inherently
    # bright hues, so they sit lower than the Material default of 40.
    primary=t(P,40), onPrimary=t(P,100), primaryContainer=t(P,90), onPrimaryContainer=t(P,10),
    secondary=t(S,35), onSecondary=t(S,100), secondaryContainer=t(S,90), onSecondaryContainer=t(S,10),
    tertiary=t(T,31), onTertiary=t(T,100), tertiaryContainer=t(T,90), onTertiaryContainer=t(T,10),
    error=t(E,40), onError=t(E,100), errorContainer=t(E,90), onErrorContainer=t(E,10),
    background=t(N,98), onBackground=t(N,10),
    surface=t(N,98), onSurface=t(N,10),
    surfaceVariant=t(NV,90), onSurfaceVariant=t(NV,30),
    surfaceContainerLowest=t(N,100), surfaceContainerLow=t(N,96),
    surfaceContainer=t(N,94), surfaceContainerHigh=t(N,92), surfaceContainerHighest=t(N,90),
    surfaceDim=t(N,90), surfaceBright=t(N,99),
    outline=t(NV,50), outlineVariant=t(NV,80),
    inverseSurface=t(N,20), inverseOnSurface=t(N,95), inversePrimary=t(P,80),
    scrim=t(N,0),
)
dark = dict(
    primary=t(P,80), onPrimary=t(P,20), primaryContainer=t(P,30), onPrimaryContainer=t(P,90),
    secondary=t(S,80), onSecondary=t(S,20), secondaryContainer=t(S,30), onSecondaryContainer=t(S,90),
    tertiary=t(T,80), onTertiary=t(T,20), tertiaryContainer=t(T,28), onTertiaryContainer=t(T,90),
    error=t(E,80), onError=t(E,20), errorContainer=t(E,30), onErrorContainer=t(E,90),
    background=t(N,6), onBackground=t(N,90),
    surface=t(N,6), onSurface=t(N,90),
    surfaceVariant=t(NV,30), onSurfaceVariant=t(NV,80),
    surfaceContainerLowest=t(N,4), surfaceContainerLow=t(N,10),
    surfaceContainer=t(N,12), surfaceContainerHigh=t(N,17), surfaceContainerHighest=t(N,22),
    surfaceDim=t(N,6), surfaceBright=t(N,22),
    outline=t(NV,60), outlineVariant=t(NV,30),
    inverseSurface=t(N,90), inverseOnSurface=t(N,20), inversePrimary=t(P,40),
    scrim=t(N,0),
)

PAIRS=[("onPrimary","primary"),("onSecondary","secondary"),("onTertiary","tertiary"),
 ("onError","error"),("onPrimaryContainer","primaryContainer"),
 ("onSecondaryContainer","secondaryContainer"),("onTertiaryContainer","tertiaryContainer"),
 ("onErrorContainer","errorContainer"),("onSurface","surface"),
 ("onSurfaceVariant","surfaceVariant"),("onBackground","background"),
 ("onSurface","surfaceContainer"),("onSurface","surfaceContainerHigh"),
 ("onSurface","surfaceContainerLow"),("onSurface","surfaceContainerHighest"),
 ("onSurfaceVariant","surfaceContainer"),("inverseOnSurface","inverseSurface")]

ok = True
for name, sc in (("LIGHT",light),("DARK",dark)):
    worst = min(contrast(sc[f], sc[b]) for f,b in PAIRS)
    fails = [(f,b,contrast(sc[f],sc[b])) for f,b in PAIRS if contrast(sc[f],sc[b]) < 4.5]
    print(f"{name}: worst {worst:.2f}  failures {len(fails)}")
    for f,b,c in fails:
        print(f"   {f} on {b} = {c:.2f}")
        ok = False
print("ALL PASS" if ok else "STILL FAILING")

# Semantic colours for money movement, shared by both themes.
print("\nSemantic:")
for label, pal, lt, dt in (("positive", T, 31, 80), ("negative", E, 40, 80), ("accent", S, 35, 80)):
    print(f"  {label:<9} light {hx(t(pal,lt))}  dark {hx(t(pal,dt))}")

def kt(name, sc):
    lines = [f'private val {name} = ' + ('lightColorScheme(' if 'Light' in name else 'darkColorScheme(')]
    for k, v in sc.items():
        lines.append(f'    {k} = Color(0xFF{hx(v)[1:]}),')
    lines.append(')')
    return "\n".join(lines)

open('Scheme.kt.part','w').write(kt("LightScheme", light) + "\n\n" + kt("DarkScheme", dark) + "\n")
print("\nwrote Scheme.kt.part")
