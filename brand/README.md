# OpenDevTrack brand

The OpenDevTrack mark is the **Commit Ring**: an open «O» drawn as a line of commits. The three hollow nodes are the
history of the project; the solid teal node is the `HEAD`, the current state. The gap on the right stands for *open*:
the line goes on.

Everything that shows the OpenDevTrack identity (the `ui/` theme, the favicon, documentation, slides, the site) uses the
files in this folder and the rules below. The files are not redrawn or edited: a missing variant is added here first.

## Files

| File | Use |
| --- | --- |
| `svg/odt-logo-horizontal*.svg` | Main logo: page headers, the README, slides |
| `svg/odt-logo-stacked*.svg` | Square or narrow spaces: covers, splash screens |
| `svg/odt-mark*.svg` | The symbol alone: avatars, social profiles |
| `svg/odt-app-icon.svg` | App icon, full detail, from 64 px |
| `svg/odt-favicon.svg` | Simplified icon without the nodes, up to 48 px |
| `png/` | The same files rendered: app icon 64–1024 px, `apple-touch-icon.png` (180 px), favicons 16/32/48 px, logos 1200 px wide |
| `favicon.ico` | Favicon with 16, 32 and 48 px images |

Colour variants: no suffix for light backgrounds, `-dark` for dark backgrounds, `-black` and `-white` for one-colour
printing.

The lettering is converted to outlines, so the SVG files need no font. The hollow nodes are real holes (an SVG mask):
the mark works on any background.

## Colours

| Role | Light | Dark |
| --- | --- | --- |
| Ink (ring, «opendev») | `#13233F` | `#E2E8F0` |
| `HEAD` (solid node) | `#0D9488` | `#2DD4BF` |
| «track» and teal text | `#0F766E` | `#2DD4BF` |
| Reference background | `#F8FAFC` | `#0B1220` |

Teal text uses `#0F766E`, not `#0D9488`: it reaches a contrast of 4.5:1 on white.

## Type

- **Space Grotesk**: logo and headings. In the logo, «open» Regular 400, «dev» Bold 700, «track» Regular 400.
- **JetBrains Mono**: code, command line, badges.

Both are under the SIL Open Font License and available from Google Fonts.

## Rules

- **Clear space**: around the logo, at least the diameter of the `HEAD` node (about one fifth of the symbol's height).
- **Minimum sizes**: the full symbol from 32 px; below that, `odt-favicon.svg`. The horizontal logo from 120 px wide.
- **Do not**:
  - rotate the symbol (the gap stays on the right);
  - close the ring;
  - colour the ring teal (teal belongs to the `HEAD` only);
  - change the colours or the letter weights;
  - add shadows, outlines or gradients.

## In a web page

```html
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" href="/odt-favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
```

For a web manifest: `png/odt-app-icon-192.png` and `png/odt-app-icon-512.png`.
