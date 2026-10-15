# Brand Guidelines v1.0

> Last updated: 2026-10-15
> Status: Draft — generated from the `ui-ux-pro-max` design system run (Flat Design, density 7, motion 3)

## Quick Reference

| Element | Value |
|---------|-------|
| Primary Color | `#0D9488` (teal-600) |
| Accent Color | `#EA580C` (orange-600) |
| Background | `#F0FDFA` (teal-50) |
| Primary Font | Plus Jakarta Sans |
| Voice | Profesional, claro, español rioplatense informal (voseo) |
| Style | Flat — no shadows, no gradients on surfaces |

---

## 1. Color Palette

### Primary — teal

| Name | Hex | Token | Usage |
|------|-----|-------|-------|
| Primary | `#0D9488` | `primary-600` | Icons, focus ring, decorative borders |
| Primary Strong | `#0F766E` | `primary-700` | **Any surface carrying white text** |
| Primary Soft | `#F0FDFA` | `primary-50` | Page background, active nav, banners |
| Primary Border | `#99F6E4` | `primary-200` | Decorative card borders only |
| Primary Ink | `#134E4A` | `primary-900` | Dark-mode surfaces, foreground |

### Accent — orange

| Name | Hex | Token | Usage |
|------|-----|-------|-------|
| Accent | `#EA580C` | `accent-600` | Highlights, decorative emphasis |
| Accent Strong | `#C2410C` | `accent-700` | **Any surface carrying white text** |

### Neutral

| Name | Hex | Token | Usage |
|------|-----|-------|-------|
| Card | `#FFFFFF` | `white` | Card surfaces |
| Muted | `#E8F1F4` | `muted` | Subtle fills, skeletons |
| Muted Foreground | `#475569` | `slate-600` | Secondary text |
| Destructive | `#DC2626` | `red-600` | Errors, log out |

### Semantic status

| Name | Hex | Usage |
|------|-----|-------|
| Success | `#16A34A` | Confirmations |
| Warning | `#D97706` | Urgent banner, retry notices |

### Accessibility

Measured WCAG 2.1 contrast, pinned by `src/__tests__/designTokens.test.js`:

| Pair | Ratio | Verdict |
|------|-------|---------|
| White on `primary-700` | 5.47:1 | Passes AA |
| White on `primary-600` | 3.75:1 | **Fails AA — never use for text** |
| White on `accent-700` | 5.18:1 | Passes AA |
| White on `accent-600` | 3.56:1 | **Fails AA — never use for text** |
| `primary-600` on white (focus ring) | 3.75:1 | Passes the 3:1 non-text floor |

**The rule:** `primary-600` and `accent-600` are for icons, borders and focus rings. The moment a
surface carries white text it moves to the `-700` shade.

This is a live constraint, not a preference: the previous blue `primary-600` (`#2563EB`) measured
5.17:1 and passed AA everywhere. Repainting the same shade to teal would have silently dropped
every button below the floor. Any future palette change must re-run the contrast test.

**Known deviation:** the border token `#99F6E4` measures 1.26:1 on white and therefore fails WCAG
1.4.11 (3:1) as a control boundary — as did the previous `border-gray-300` (1.47:1). It is used for
decorative card borders only; interactive controls rely on the `primary-600` focus ring, which
passes, for their visible boundary.

---

## 2. Typography

**Plus Jakarta Sans**, weights 400 / 500 / 600 / 700, loaded from Google Fonts with
`display=swap`.

- Body: 400, `text-sm`/`text-base`
- Labels and buttons: 500
- Headings: 600
- Fallback stack: `ui-sans-serif, system-ui, sans-serif`

---

## 3. Logo Usage

There is no graphical logo. The wordmark is the `Mail` glyph from lucide-react at
`text-primary-600`, immediately left of the product name, optionally inside a rounded tile filled
with `primary-700` when it carries white text.

- Minimum clear space: one glyph width on all sides.
- The service provider marks (Google, Microsoft) are third-party and keep their own brand colours.
  Do not repaint them to the palette.

---

## 4. Voice

Profesional, claro, español rioplatense informal (voseo): "Sincroniza para ver tus emails",
"Escribí tu respuesta acá".

- Direct, no filler. Say what happened and what to do next.
- Errors name the cause and offer the next action (`Reintentar`), never "algo salió mal".
- The assistant speaks in the first person; the product speaks to the user in the second person.
