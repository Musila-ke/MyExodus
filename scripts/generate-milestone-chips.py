"""Generates the milestone-chip artwork (sobriety-medallion style) as SVG masters.

Original artwork — no Alcoholics Anonymous marks (the circle-and-triangle logo and
"To Thine Own Self Be True" are AA trademarks; vendor chip designs are copyrighted).
Style: brushed-metal rim, beaded edge, engraved sunburst + guilloché field, a leaf
wreath that grows with the milestone, and the MyExodus leaf in the centre.

    python3 scripts/generate-milestone-chips.py     # writes assets/milestones/chips/svg/

Rasterize to transparent PNG (must be resvg — macOS qlmanage flattens SVG onto white):

    mkdir -p /tmp/rvg && npm install --prefix /tmp/rvg @resvg/resvg-js
    cat > /tmp/rasterize.cjs <<'EOF'
    const { Resvg } = require('@resvg/resvg-js'); const fs = require('fs');
    const [, , i, o, s] = process.argv;
    fs.writeFileSync(o, new Resvg(fs.readFileSync(i, 'utf8'),
      { fitTo: { mode: 'width', value: Number(s) || 512 } }).render().asPng());
    EOF
    for f in assets/milestones/chips/svg/*.svg; do
      NODE_PATH=/tmp/rvg/node_modules node /tmp/rasterize.cjs "$f" \
        "assets/milestones/chips/png/$(basename "${f%.svg}").png" 512
    done
"""
import base64
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "assets" / "milestones" / "chips" / "svg"
LEAF = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "logo_mark.png"
LEAF_URI = "data:image/png;base64," + base64.b64encode(LEAF.read_bytes()).decode()

# label, [metal-light, metal-mid, metal-dark], ornament level 0–6
TIERS = [
    ("24 HOURS",  ["#FFFFFF", "#DAD7D0", "#A9A6A0"], 0),   # pearl
    ("3 DAYS",    ["#F0B58C", "#C9744A", "#8C4A2B"], 1),   # copper
    ("1 WEEK",    ["#E3C08A", "#B08968", "#7A5636"], 1),   # bronze
    ("1 MONTH",   ["#F2F4F6", "#BFC6CC", "#8E959B"], 2),   # silver
    ("3 MONTHS",  ["#8FDCC8", "#2E9E8C", "#0F766E"], 2),   # emerald (app teal)
    ("6 MONTHS",  ["#A8C8F0", "#5B84C4", "#33538F"], 3),   # sapphire
    ("1 YEAR",    ["#F5D083", "#D99A3D", "#B45309"], 3),   # gold (app gold)
    ("2 YEARS",   ["#F3C3A6", "#D08A63", "#9C4F33"], 4),   # rose gold
    ("5 YEARS",   ["#DDE8EE", "#93AAB8", "#5C7484"], 4),   # titanium blue-steel
    ("10 YEARS",  ["#E4F6FC", "#9CCFE3", "#5E93AD"], 5),   # diamond
    ("20 YEARS",  ["#D6D3CC", "#8A867E", "#4A4844"], 5),   # onyx-steel
    ("30 YEARS",  ["#E8D9B8", "#A5854F", "#5C431F"], 6),   # antique gold
]

# viewBox 512; concentric structure (chip centre 256,256; leaf/wreath centre 256,236)
R_RING, R_BEAD, R_FIELD, R_GROOVE1, R_GROOVE2, R_TEXT = 250, 228, 212, 198, 176, 166
CY, R_WREATH, R_RAYS = 236, 128, (178, 199)


def blend(a: str, b: str, t: float) -> str:
    ah, bh = [int(a[i:i + 2], 16) for i in (1, 3, 5)], [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(ah, bh))


def rays(light: str) -> str:
    out = []
    for k in range(132):
        a = math.radians(k * 360 / 132)
        x1, y1 = 256 + R_RAYS[0] * math.cos(a), 256 + R_RAYS[0] * math.sin(a)
        x2, y2 = 256 + R_RAYS[1] * math.cos(a), 256 + R_RAYS[1] * math.sin(a)
        out.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}"/>')
    return f'<g stroke="{light}" stroke-opacity="0.18" stroke-width="1.2">{"".join(out)}</g>'


def guilloche(light: str) -> str:
    out = [f'<circle cx="256" cy="{CY}" r="{100 + i * 5.5:.1f}"/>' for i in range(13)]
    return f'<g fill="none" stroke="{light}" stroke-opacity="0.055" stroke-width="1">{"".join(out)}</g>'


def leaf_ring(radius: float, rx: float, ry: float, step: float, gap: float,
              light: str, mid: str) -> str:
    leaves = []
    for k in range(int(360 / step)):
        th = k * step
        if gap and abs(((th - 90 + 180) % 360) - 180) < gap:
            continue
        a = math.radians(th)
        x, y = 256 + radius * math.cos(a), CY - radius * math.sin(a)
        fill, op = (light, 0.92) if len(leaves) % 2 == 0 else (mid, 0.88)
        leaves.append(f'<ellipse cx="0" cy="0" rx="{rx:.1f}" ry="{ry:.1f}" fill="{fill}" fill-opacity="{op}"'
                      f' transform="translate({x:.1f} {y:.1f}) rotate({90 - th:.1f})"/>')
    return ("".join(leaves) +
            f'<circle cx="256" cy="{CY}" r="{radius}" fill="none" stroke="{mid}" stroke-opacity="0.35" stroke-width="1.4"/>')


def wreath(level: int, light: str, mid: str) -> str:
    """A laurel ring that gains layers with the milestone (no element ever crosses
    the label arc at r≈166 or the leaf disc at r=92)."""
    if level < 2:
        return ""
    out = leaf_ring(R_WREATH, 6.0, 13.5, 7.5 if level < 5 else 6.5,
                    gap=30 if level == 2 else 0, light=light, mid=mid)
    if level >= 4:
        out += leaf_ring(112, 4.6, 10.0, 9.0 if level < 6 else 7.5, gap=0, light=mid, mid=light)
    return out


TEMPLATE = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="512" height="512" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="metal" x1="0.1" y1="0" x2="0.9" y2="1">
      <stop offset="0" stop-color="{dark}"/><stop offset="0.18" stop-color="{light}"/>
      <stop offset="0.42" stop-color="{mid}"/><stop offset="0.62" stop-color="{light}"/>
      <stop offset="0.85" stop-color="{mid}"/><stop offset="1" stop-color="{dark}"/>
    </linearGradient>
    <linearGradient id="rimLight" x1="0" y1="1" x2="0" y2="0">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.28"/>
      <stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.03"/>
      <stop offset="1" stop-color="#000000" stop-opacity="0.18"/>
    </linearGradient>
    <radialGradient id="field" cx="0.38" cy="0.3" r="0.9">
      <stop offset="0" stop-color="{fieldHi}"/><stop offset="0.7" stop-color="#17150F"/><stop offset="1" stop-color="#0D0C08"/>
    </radialGradient>
    <radialGradient id="sheen" cx="0.35" cy="0.25" r="0.75">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.16"/><stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
    <path id="topArc" d="M 256,256 m -{R},0 a {R},{R} 0 0 1 {R2},0"/>
    <path id="botArcSmall" d="M 256,256 m -{RS},0 a {RS},{RS} 0 0 0 {RS2},0"/>
  </defs>

  <circle cx="256" cy="256" r="{R_RING}" fill="url(#metal)"/>
  <circle cx="256" cy="256" r="{R_RING}" fill="none" stroke="#000" stroke-opacity="0.35" stroke-width="2"/>
  <circle cx="256" cy="256" r="{R_BEAD}" fill="none" stroke="{dark}" stroke-width="9"
          stroke-dasharray="0.5 17.4" stroke-linecap="round" stroke-opacity="0.85"/>
  <circle cx="256" cy="256" r="{R_FIELD}" fill="url(#field)"/>
  <circle cx="256" cy="256" r="{R_FIELD}" fill="url(#rimLight)"/>
  <circle cx="256" cy="256" r="{R_FIELD}" fill="none" stroke="{mid}" stroke-width="3" stroke-opacity="0.9"/>
  <circle cx="256" cy="256" r="{R_FIELD}" fill="url(#sheen)"/>
  <circle cx="256" cy="256" r="{R_GROOVE1}" fill="none" stroke="{light}" stroke-width="1.5" stroke-opacity="0.22"/>
  <circle cx="256" cy="256" r="{R_GROOVE2}" fill="none" stroke="{light}" stroke-width="1" stroke-opacity="0.16"/>
  {rays}
  {guilloche}

  <circle cx="256" cy="{CY}" r="88" fill="#000" fill-opacity="0.28"/>
  <circle cx="256" cy="{CY}" r="88" fill="none" stroke="{light}" stroke-width="1.2" stroke-opacity="0.35"/>
  {wreath}
  <g transform="translate(256 {CY})">
    <image xlink:href="{leaf}" x="-78" y="-78" width="156" height="156"/>
  </g>

  <g font-family="Georgia, 'Times New Roman', serif" font-weight="bold" text-anchor="middle">
    <text font-size="44" letter-spacing="8" fill="#000" fill-opacity="0.65" dy="2.5"><textPath xlink:href="#topArc" startOffset="50%">{label}</textPath></text>
    <text font-size="44" letter-spacing="8" fill="{light}"><textPath xlink:href="#topArc" startOffset="50%">{label}</textPath></text>
    <text font-size="44" letter-spacing="8" fill="#FFF" fill-opacity="0.22" dy="-1.6"><textPath xlink:href="#topArc" startOffset="50%">{label}</textPath></text>
    <text font-size="19" letter-spacing="9" fill="{light}" fill-opacity="0.75"><textPath xlink:href="#botArcSmall" startOffset="50%">MYEXODUS</textPath></text>
  </g>

  <g fill="{light}" fill-opacity="0.85">
    <rect x="-5" y="-5" width="10" height="10" transform="translate(76 256) rotate(45)"/>
    <rect x="-5" y="-5" width="10" height="10" transform="translate(436 256) rotate(45)"/>
  </g>
</svg>
"""


def render(label, metal, level):
    light, mid, dark = metal
    return TEMPLATE.format(
        light=light, mid=mid, dark=dark, label=label, leaf=LEAF_URI,
        fieldHi=blend("#26241C", dark, 0.30),
        R=R_TEXT, R2=2 * R_TEXT, RS=178, RS2=2 * 178,
        R_RING=R_RING, R_BEAD=R_BEAD, R_FIELD=R_FIELD, CY=CY,
        R_GROOVE1=R_GROOVE1, R_GROOVE2=R_GROOVE2,
        rays=rays(light), guilloche=guilloche(light),
        wreath=wreath(level, light, mid),
    )


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for label, metal, level in TIERS:
        name = label.lower().replace(" ", "-")
        (OUT / f"{name}.svg").write_text(render(label, metal, level))
        print(f"wrote {OUT / f'{name}.svg'}")


if __name__ == "__main__":
    main()
