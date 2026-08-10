# DESIGN.md — humanlive design system of record

> Source of truth for every visual decision in humanlive. Authored in
> Iteration 0 of the [DRE-254](/DRE/issues/DRE-254) UI/UX design sprint, built on
> the [impeccable.style](https://impeccable.style/) methodology. The token values
> below mirror `app/src/main/java/dreamteam/app/ui/Theme.kt` — the code is the
> compiled truth; this document is the designer-readable view of the same system.
> When the two disagree, **Theme.kt wins** and this doc is updated to match.

## How to use this document

This is a **DESIGN.md** in the impeccable.style sense: a portable design system
every contributor (human, agent, or tool) reads before designing or building a
surface. Every later change in the sprint consumes these tokens and rules
instead of inventing new ones.

- **Building a surface?** Start from the [Surface tiers](#surface-tiers) and the
  [7-pillar acceptance criteria](#the-7-pillar-acceptance-criteria). Reach for a
  token, never a literal.
- **Reviewing a surface?** Run the [design-critique](#) pass against the criteria
  + the [anti-pattern negative checks](#anti-pattern-negative-checks).
- **Drift appeared?** Use `extract` discipline — three+ identical ad-hoc uses
  become a token or primitive, recorded here.

---

## 1. Product posture (why these rules exist)

humanlive is an **Operate**-mode product (impeccable's four modes: Persuade /
Operate / Read / Experience). The visitor completes a task — *do today's workout
and log how it went* — so **scanability and native mobile expectations outrank
expression**. The brand intent is a **calm, scannable journal**: one clear
primary action per surface, evidence demoted behind a quiet secondary surface,
no card-walled walls of text. Full product context lives in [`PRODUCT.md`](./PRODUCT.md).

This is the load-bearing tension the system resolves: the app is
**evidence-linked** (citations are a feature, not decoration) AND **calm**
(evidence must recede behind the workout, never compete with it).

---

## 2. Palette

Warm, minimalist, calm. Sage-teal accent on warm off-white (light) or warm-dark
(dark). **No pure black, no gray-on-color, no AI purple/blue gradients.** Depth
comes from tonal surface layering, not harsh drop-shadows.

### Semantic roles (`ZenPalette`, Theme.kt:113)

| Role | Light hex | Dark hex | Used for |
|------|-----------|----------|----------|
| **Ink**   | `#2E2A26` | `#EDE8E0` | all body + heading text (warm charcoal, never pure black) |
| **Paper** | `#FAF7F2` | `#1C1B1A` | the canvas (warm washi off-white) |
| **Matcha**| `#5E8B7E` | `#8FAB9F` | primary accent — the calm sage-teal hero (Play CTA, step badges) |
| **Sand**  | `#C9A66B` | `#D9BC89` | secondary — warm gold for gentle emphasis |
| **Clay**  | `#B58263` | `#D0A684` | tertiary — breathing/secondary highlights |
| **Stone** | `#6B645B` | `#B5ADA2` | muted meta text (`onSurfaceVariant`) |
| **Mist**  | `#E8E2D6` | — | hairline dividers / whisper-faint fills (`outlineVariant`) |

These names map onto Material 3 color roles: `Ink→onBackground/onSurface`,
`Paper→background`, `Matcha→primary`, `Stone→onSurfaceVariant`, etc. Designers
reason in the names; the Compose tree consumes the M3 roles.

### Color rules

- **Evidence/citation/source affordances render in `onSurfaceVariant` /
  `surfaceVariant` — never `primary`.** They must recede behind the workout.
  Exception: step-number badges keep `primary` (they sequence the how-to, a
  structural element, not evidence).
- **No pure black.** Keep warm `Ink` (`#2E2A26` light / `#EDE8E0` dark).
- **No gray-on-color text.** On a colored surface use the matching `on*` token.
- **Contrast ≥ WCAG AA** (4.5:1 body, 3:1 large). The muted `Stone` evidence text
  is the risk surface — verify, don't assume.

---

## 3. Typography

A **serif/sans pairing**: Lora (display serif) for the two heading roles,
Mulish (humanist sans) for everything else. The pairing is the move from
"generic app" to "calm journal." Both ship **full Cyrillic** (the app is
Russian — a Latin-only display face like Fraunces was rejected because its
Cyrillic headings would silently fall back to sans and look broken).

### Type scale (`AppTypography`, Theme.kt:162)

| Role | Family | Weight | Size | Line-height | Tracking | Used for |
|------|--------|--------|------|-------------|----------|----------|
| `headlineMedium` | **Lora** | Bold | 28sp | 34 | −0.5sp | screen title (the largest element) |
| `titleLarge` | **Lora** | Bold | 22sp | 28 | −0.2sp | section / session heading |
| `titleMedium` | Mulish | SemiBold | 17sp | 24 | — | exercise name, sub-section |
| `bodyLarge` | Mulish | Normal | 16sp | 24 | — | primary body / how-to steps |
| `bodyMedium` | Mulish | Normal | 14sp | 21 | — | secondary body |
| `bodySmall` | Mulish | Normal | 13sp | 18 | — | hints, captions |
| `labelLarge` | Mulish | SemiBold | 14sp | — | +0.1sp | button text |
| `labelMedium` | Mulish | Medium | 12sp | — | +0.4sp | section labels, prescription/meta tags |
| `labelSmall` | Mulish | Medium | 11sp | — | +0.4sp | smallest meta (image credit) |

The scale clears impeccable's "flat type hierarchy" check: consecutive steps
differ by ≥1.25× where it carries hierarchy (28→22→17→…).

### Type rules

- **At most 3 type roles visible on any one screen at once** (e.g.
  `titleLarge` display → `titleMedium` Mulish → `body`/`label`). More = fail.
- **The screen title is the largest element on its screen.** A section label
  (`labelMedium`, `Stone`) is always smaller than the content it titles.
- **Prescription/density text ≤ `labelMedium` (12sp).** Exercise name =
  `titleMedium`. Never use `bodyLarge` for metadata.
- **Long Russian exercise names / descriptions never clip or truncate
  mid-word** on a 360dp device. Use `maxLines`/`ellipsis` deliberately, and
  verify against the longest catalog name.
- `FontWeight.Light` is used sparingly for secondary/evidence rows; do not
  proliferate it onto primary content.

---

## 4. Spacing — the single rhythm

A 4dp-base scale consumed everywhere via tokens, so the whole app shares ONE
visual rhythm. This is "вёрстка как математика": **no ad-hoc `dp` literals in
new/changed composables.** All gaps, edges, and paddings reference `Spacing.*`.

### The scale (`Spacing`, Theme.kt:234)

| Token | Value | Used for |
|-------|-------|----------|
| `xs` | 4dp | base unit — hairline gaps, divider insets, tight grouping |
| `sm` | 8dp | chips, adjacent controls, intra-row separation |
| `md` | 12dp | label → its block, mid-density list rows |
| `lg` | 16dp | inside a Card, edge-to-content air, screen edge (`screen`) |
| `xl` | 20dp | between top-level sections |
| `xxl` | 24dp | screen header → first section, scene margins |
| `section` | 32dp | the calm break between major top-level sections |
| `screen` | 16dp | window → content column edge padding |
| `touchTarget` | 48dp | floor on every actionable control |
| `exerciseMediaHeight` | 180dp | 16:9 exercise image slot height |

Legacy aliases (`card`=lg, `itemGap`=sm, `tightGap`=xs) are kept canonical so
existing call sites compile; new code uses the named scale.

### Spacing rules

- **No vertical "wall":** every screen chunks into ≤4 visible groups; **≥
  `Spacing.section` (32dp) between top-level groups.** The current code uses
  `Spacing.lg` (16dp) as the LazyColumn arrangement everywhere — that is the
  monotonous-spacing anti-pattern this sprint fixes.
- **Every actionable control ≥ `Spacing.touchTarget` (48dp)** min height;
  checkbox rows included.
- **Tight groupings for related items, generous separations between sections.**
  Rhythm, not monotony.

---

## 5. Shape

Soft, generous radii read as calm and tactile — never sharp.

### Shape family (`ZenShape`, Theme.kt:273)

| Token | Value | Used for |
|-------|-------|----------|
| `card` (`AppCardShape`) | 24dp | the default card / container |
| `cardLarge` | 28dp | larger scenes: breathing, full-screen sheets, dialogs |
| `field` | 16dp | inputs, text fields, dense inline surfaces |
| `pill` | fully rounded (`50`) | chips, prescription tags, primary action buttons |

### Shape rules

- **Don't over-round small cards.** impeccable flags ≥24px radii on small cards
  as a soft-blob tell. `card` (24dp) is reserved for genuinely self-contained
  units (an exercise detail, a dialog, a sheet) — not for every labeled section.
- **Full-pill is for tags and buttons only**, not section containers.

---

## 6. Elevation — tonal, not shadowed

Calm is conveyed by **tonal layering** (warmer/lighter surface tiers via
`surfaceContainer` / `surfaceVariant` + `surfaceTint`), not harsh drop-shadows.
Keep shadow elevations low so depth stays whisper-soft.

### Elevation (`ZenElevation`, Theme.kt:305)

| Token | Value | Used for |
|-------|-------|----------|
| `resting` | 0dp | the resting card — depth from its surface tier, not a shadow |
| `raised` | 1dp | a card lifted one notch (subtle) |
| `floating` | 3dp | FABs, sticky headers |
| `overlay` | 6dp | dialogs / bottom sheets over content |

**Prefer flat layered cards.** Do not stack a hairline border *and* a wide
diffuse shadow — impeccable flags that combination as a generated-UI signature.
Commit to one: a defined edge **or** soft elevation, not both.

---

## 7. Motion — calm, slow, deliberate

Nothing snaps. Motion conveys state, not decoration. No bounce/elastic easing on
interface elements (reserve spring physics for things that are actually
physical, like the breathing orb).

### Motion system (`Motion`, Theme.kt:325)

**Durations:** `microMs`=150 (ripple/chip toggle) · `smallMs`=250 (press/expand)
· `mediumMs`=400 (cross-fade/sheet) · `largeMs`=600 (card expand/screen enter).

**Easings:** `Emphasized` (FastOutSlowIn) · `EmphasizedDecelerate`
(`0.05,0.7,0.1,1` — entrances) · `EmphasizedAccelerate` (`0.3,0,0.8,0.15` —
exits) · `Calm` (`0.4,0,0.2,1` — ambient).

**Specs:** `calm` (600ms Emphasized, card expand/screen enter) · `gentle`
(400ms Calm, fade/scale swap) · `quick` (250ms Emphasized, tap feedback) ·
`breath` (4000ms Linear, pacer cycle) · `softSpring` (damping 0.8,
MediumLow — interactive elements only).

### Motion rules

- **Expand/collapse** (exercise detail, citations) uses `Motion.calm` /
  `gentle` via `AnimatedVisibility` — **never an instant snap.**
- **Screen enter** uses `Motion.largeMs`; no bounce / `Spring.StiffnessHigh` on
  ambient elements.
- **Animate only what is changing.** A pulsing status dot on static data is a
  tell. Animate the breathing orb (it *is* changing), not a "live" badge.

---

## 8. Surface tiers — when to use a Card

This is the #1 fix of the sprint. impeccable's headline anti-pattern is *"cards
wrapping everything"* ("Cardocalypse" / "Nested cards"). humanlive currently
puts a `Card` around the session, every exercise, nutrition, and adaptation —
producing a card-walled wall.

### The 3-tier hierarchy (decided, DRE-254 §Design direction 1)

| Tier | What it is | When to use |
|------|------------|-------------|
| **Paper canvas** | the screen background (`Paper`) | always — the base |
| **Labeled section** | `labelMedium` label + content, separated by `Spacing.section`, **NO border** | the default chunking unit. Nutrition, adaptation, session-summary, quick-log are sections, **not cards.** |
| **Card** | a bordered, tonally-lifted surface (`ZenShape.card`, `resting` elevation) | reserved ONLY for a genuinely self-contained unit: a single exercise detail, a dialog, a sheet. |

**Rule:** dividers and whitespace do the chunking. A `Card` is earned by
containment semantics, not applied reflexively. If you can replace a `Card`
with a label + `Spacing.section`, do.

---

## 9. Interaction & affordance

- **Every clickable is a real component** — `Button` / `OutlinedButton` /
  `TextButton` / icon-button. **No bare `Text` + `.clickable` pretending to be a
  button.** (The current "Подробнее"/"Свернуть" text toggles and the
  "Источники (N)" row are the violations this sprint removes.)
- **No dual-semantics on one tap target.** A row either completes the exercise
  (checkbox) or opens detail — never both. (Already split; keep it.)
- **Disabled controls look disabled and are disabled for a real reason.** E.g.
  coach buttons are **hidden** when AI-coach is OFF, not grey-dead.
- **Touch targets ≥ 48dp.** Checkbox rows included.

---

## 10. UX writing

- **Buttons are verbs naming the outcome:** `Начать тренировку`, `Сохранить`,
  `Готово · далее` — not nouns or `Submit`.
- **No decorative microcopy.** The support-framing disclaimer ("поддержка, а не
  замена врача") appears **once per surface that needs it**, not repeated in
  every card. (Redundant-UX-writing anti-pattern.)
- **The "Источники (N)" affordance is a single quiet label, closed by default**
  — never an open citation list in the exercise row.
- **Russian-language, support framing only:** no diagnosis, no "у вас …", no
  treatment/cure claim. Any new/changed authored string is added to its
  `*Strings.all` list so the banned-phrase JVM test stays green.

---

## The 7-pillar acceptance criteria

Every surface is graded against these. Each is a **pass/fail check** an
implementer or QA can run. (Canonical source: [DRE-254 plan](/DRE/issues/DRE-254#document-plan).)

### P1 — Typography
- **C1.1** At most 3 type roles visible on any screen at once.
- **C1.2** Hierarchy unambiguous: screen title is the largest; a section label is smaller than its content.
- **C1.3** Prescription/density text ≤ `labelMedium` (12sp); exercise name = `titleMedium`.
- **C1.4** Long RU names never clip/truncate mid-word on 360dp.

### P2 — Spatial
- **C2.1** No vertical wall: ≤4 visible groups; ≥ `Spacing.section` between top-level groups.
- **C2.2** `Card` only for a self-contained unit; nutrition/adaptation/session-summary are labeled sections, not bordered cards.
- **C2.3** Zero ad-hoc `dp` literals in new/changed composables — all gaps reference `Spacing.*`.
- **C2.4** Every actionable control ≥ `Spacing.touchTarget` (48dp).

### P3 — Responsive / scaling
- **C3.1** Single-column on phone (≤600dp); `BoxWithConstraints` compact/normal branch preserved. No horizontal scroll / clipped content on 360/411/600dp.
- **C3.2** Max font scale does not break layout or truncate prescription text.
- **C3.3** Landscape reflows (scroll, not an unusable stacked column) for Today + Play.

### P4 — Interaction
- **C4.1** Every clickable is a real Button/TextButton/icon-button — no bare `Text.clickable`.
- **C4.2** No dual-semantics: a row completes (checkbox) OR opens detail, never both.
- **C4.3** Disabled controls look disabled for a real reason (coach buttons hidden, not grey-dead).

### P5 — Motion
- **C5.1** Expand/collapse uses `Motion.calm`/`gentle` (AnimatedVisibility) — no instant snaps.
- **C5.2** Screen enter uses `Motion.largeMs`; no bounce/`StiffnessHigh` on ambient elements.

### P6 — Color
- **C6.1** Evidence/citation/source affordances render in `onSurfaceVariant`/`surfaceVariant`, never `primary` (exception: step-number badges).
- **C6.2** No pure black (warm `Ink`); no gray-on-color text.

### P7 — UX writing
- **C7.1** Buttons are verbs naming the outcome.
- **C7.2** No decorative microcopy; disclaimer once per surface, not per card.
- **C7.3** "Источники (N)" is a single quiet label, closed by default.

---

## Anti-pattern negative checks

The impeccable.style slop detector defines 64 patterns. These are the ones
relevant to humanlive — a change introducing any of them **fails review**:

| Anti-pattern | What it is | humanlive relevance |
|--------------|------------|---------------------|
| **Cardocalypse / Nested cards** | cards inside cards; cards wrapping everything | **the core complaint** — SessionCard + nutrition Card + adaptation Card + week Card + citation Card |
| **Monotonous spacing** | one spacing value everywhere, no rhythm | `Spacing.lg` as the LazyColumn gap on every screen |
| **Redundant UX writing** | label/sublabel/hint all saying the same thing | disclaimer repeated; Play CTA + full session duplicating the workout |
| **Bare Text as button** (interaction) | `Text` + `.clickable` instead of a real affordance | "Подробнее"/"Скрыть" + "Источники (N)" toggles |
| **Flat type hierarchy** | font sizes too close, no contrast | guarded by the type scale; watch over-use of `titleLarge` |
| **Hairline border + wide shadow** | the generated-UI edge+shadow combo | keep elevation tonal, not both |
| **Cream/beige palette** (reflex) | warm off-white reached for by reflex | humanlive's palette is **deliberate** (calm journal intent), not reflex — but the detector flags it; the intent is documented here so it reads as chosen, not default |
| **Pulsing status dot** | decorative pulse on static data | animate only changing data (breathing orb) |
| **Cramped padding** | text too close to container edge | ≥ `Spacing.lg` (16dp) inside bordered containers |
| **Low contrast / gray-on-color** | text below WCAG AA | verify muted `Stone` evidence text |

---

## Component conventions (build-to patterns)

These are the patterns the sprint normalizes toward. Implementers apply them
verbatim.

- **`SessionSummaryCard`** (new, Iter 1) — a compact summary: `todayDateLine`
  (display), session label, "N упражнений", progress "Сделано: done/total", ONE
  primary Play CTA. Rest day → the existing `REST_DAY` line. This replaces the
  inline full `SessionCard` on Today.
- **Exercise row** (Iter 2) — checkbox + name (`titleMedium`) + ONE prescription
  `MetaTag` (`"{sets}×{repScheme} @{RIR} RIR"`) + a real `TextButton`
  "Подробнее"/"Скрыть". Equipment/evidence-level/how-to/AI summary/citations/media
  live behind that toggle, in muted `surfaceVariant`.
- **`exerciseDensityChips`** (Iter 2) — emits ONE prescription chip; equipment +
  evidence-level move to a detail-only `exerciseMetaChips(...)`.
- **`EvidenceCitationCard`** — unchanged render; parent toggle is
  `onSurfaceVariant`, closed by default.
- **`MetaTag`** — read-only badge: `Surface(surfaceVariant)` + `labelMedium` +
  `onSurfaceVariant`. The densest honest render of an informational tag.

---

## Cross-cutting guardrails (the "no other logic" constraint)

- **G1** Every change is in the Compose tree / Theme tokens only. Zero edits to
  the safety gate, plan generator, coach, `LocalDatabase`, or domain models. A
  diff touching `dreamteam.domain.*` or gate logic fails review.
- **G2** No new Gradle dependency. (Coil2 is the only network dep and stays.)
- **G3** No new medical claim: any new/changed authored string is added to the
  relevant `*Strings.all` list so the banned-phrase JVM test stays green.

---

## Verification per change

1. **Trustworthy build** — `./gradlew :app:compileDebugKotlin --rerun-tasks`
   (never trust incremental/UP-TO-DATE; `--rerun-tasks` is the only pass that
   counts).
2. **Banned-phrase test** — the `*Strings*` suite stays green (G3).
3. **No-logic guard** — diff has zero changes under `dreamteam/domain/**`, gate,
   coach, or `LocalDatabase` (G1).
4. **Visual proof** — emulator screenshot before/after; design-critique pass
   against the criteria. (dre_m10 has no network — remote images verify the
   offline placeholder only; on-network painting is QA's job.)
5. **End-to-end behavior** — Today shows summary + Play (no inline full list);
   default exercise row = name + ONE chip, citations closed.

---

*References: [impeccable.style](https://impeccable.style/) ·
[Designing with Impeccable](https://impeccable.style/designing) ·
[Slop detector](https://impeccable.style/slop) ·
[DRE-254 sprint spec](/DRE/issues/DRE-254#document-plan) ·
[Theme.kt](../app/src/main/java/dreamteam/app/ui/Theme.kt)*
