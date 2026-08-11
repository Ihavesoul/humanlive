# Final design-critique — all surfaces (sprint close gate, DRE-265)

> Iteration 4b final pass (DRE-265). A code-free critique of **every surface**
> against the [7-pillar spec](./DESIGN.md#the-7-pillar-acceptance-criteria)
> (DRE-254 plan) + DESIGN.md (DRE-256), run on the finished code at commit
> `0286beb` (DRE-259). This is the symmetry pass to the [Iter-0 baseline
> critique](./design-critique-baseline.md): the baseline produced the must-fix
> list the sprint targeted; this pass confirms it is **empty** and records the
> residual should/nice-to-fix with explicit dispositions. Format follows the
> [design-critique skill](https://…). Findings cite `file:line` anchors and map
> to pillars P1–P7.

**Method.** Structural review of every Compose surface + on-device render on the
`dre_m10` emulator (360dp, light **and** dark) via the accessibility tree, plus
an objective WCAG contrast computation from the Theme.kt hex values (the C6.1
risk surface — muted `Stone` evidence text — is not eyeballed, it is measured).
dre_m10 has no network, so the offline image fallback is what renders; on-network
painting is QA's job (unchanged from baseline).

**Gate verdict: must-fix = EMPTY.** All eight baseline structural must-fix items
are fixed in Iter 1–4 (verified in code and on-device). Residual findings are
should-fix / nice-to-fix, each with an explicit disposition below.

---

## Baseline must-fix disposition (the sprint close gate)

| # | Baseline must-fix (Iter 0) | Pillar | Disposition | Evidence |
|---|----------------------------|--------|-------------|----------|
| 1 | Today: Play button **and** full SessionCard = workout duplication + wall | P2/C2.1 | **FIXED** | `DreamTeamApp.kt:649` — `SessionSummaryCard` (date/label, count, done/total, ONE Play CTA); inline full `SessionCard` removed from Today |
| 2 | Today: nutrition + adaptation wrapped in Cards | P2/C2.2 | **FIXED** | `DreamTeamApp.kt:650-678` — de-carded labeled sections (`labelMedium` label + content, no border) |
| 3 | Monotonous `Spacing.lg` rhythm everywhere | P2/C2.1 | **FIXED** | `Arrangement.spacedBy(Spacing.section)` (32dp) on Today `:642`, Plan `:536`, Settings `:1554`, Symptoms/Progress |
| 4 | Bare `Text.clickable` "Подробнее / Скрыть" | P4/C4.1 | **FIXED** | `DreamTeamApp.kt:1044` — real `TextButton` + `Modifier.heightIn(min = Spacing.touchTarget)` (48dp, C2.4) |
| 5 | Bare clickable Row "Источники (N)" | P4/C4.1 | **FIXED** | `DreamTeamApp.kt:1172` — real `TextButton`, 48dp floor, `onSurfaceVariant` color, closed by default |
| 6 | Chip soup (5 tags competing with the name) | P1/C1.3 | **FIXED** | `DreamTeamApp.kt:1042` — ONE prescription `MetaTag`; equipment/evidence-level moved to detail-only `exerciseMetaChips` (`:1158`) |
| 7 | Plan: one giant week Card (title+nutrition+warnings+adaptation walled together) | P2/C2.2 | **FIXED** | `DreamTeamApp.kt:541-575` — week title as `headlineMedium` section heading; nutrition/adaptation de-carded labeled sections |
| 8 | Plan: sessions are dense SessionCards (wall) | P2/C2.1 | **FIXED** (wall resolved) | SessionCard reformed: collapsed-by-default (name + ONE chip + toggle), hairline dividers between exercises (`:1027`), detail behind tap. **Deferred (nice-to-fix #6):** full de-card of Plan sessions to a borderless scannable list — the wall is gone (collapsed rows), the residual is the Card border itself |

---

## Design critique: cross-surface findings

### Must-fix (blocks ship)
- _None._ The sprint's structural goals — kill the duplication, kill the card
  wall, kill the chip soup, make every clickable a real affordance, give the
  app a rhythm — are met. See the disposition table above.

### Should-fix (before broader rollout)

- **C6.1 / Color — light-mode primary-button text below strict WCAG-AA body.**
  White `onPrimary` (`#FFFFFF`) on `primary` Matcha (`#5E8B7E`) = **3.84:1**
  (computed from Theme.kt hex). Below WCAG AA 4.5:1 for body text; passes
  AA-large (3:1). Affects every filled `Button` (Play CTA, "Готово · далее",
  save buttons). **Dark mode passes cleanly (6.62:1).** DRE-259's AA claim
  covered only the muted evidence text, not button labels. **Try:** darken
  Matcha toward `#4F7A6D` (≈4.6:1) **or** accept AA-large as the bar for
  button labels (M3 treats label text as large in its token system). This is a
  brand-color vs. strict-AA tradeoff — **founder decision** (the sage is a
  deliberate choice). **Disposition: surfaced for founder call; not blocking
  because it passes AA-large and dark, and the spec's named risk surface (muted
  evidence text) passes at 4.92–5.74:1.**
- **C6.1 / Color — light-mode prescription text.** `primary` on `background` =
  **3.59:1** (PlayScene.kt:162, `titleMedium` 17sp SemiBold). Same root cause
  as above. The spec sanctions primary-on-the-focus-element ("verify it reads
  as accent-on-purpose", DESIGN.md §2); it passes AA-large. **Try:** resolved
  by the same Matcha darkening, or leave as deliberate accent.
- **P7.2 / UX writing — disclaimer repeats on Settings.** "Приложение
  поддерживает, не заменяет врача" appears in both `SettingsStrings.HINT`
  (`:1663`) and `COACH_TOGGLE_HINT` (`:1666`). Spec: once per surface. **Try:**
  keep it in the screen hint; drop it from the toggle explanation (the hint
  already covers the whole screen).

### Nice-to-fix (when there's room)

- **C4.1 / Interaction — `EvidenceCitationCard` expand is a bare
  `Column.clickable`.** `ClientExerciseReferences.kt:331` — the SHOW/HIDE is a
  `Text` inside a `Column.clickable` (card-tap-to-expand), not a real
  `TextButton`. This is a container tap (a common, defensible pattern), but for
  strict consistency with C4.1 the SHOW/HIDE affordance could be a `TextButton`
  as the exercise-row toggles now are. Low severity.
- **C5.2 / Motion — no cross-fade on Play exercise-to-exercise advance.**
  `PlayScene.kt:71-77` — `markCurrentDone` swaps the name/prescription
  instantly (baseline should-fix). Screen **enter** is animated at the nav
  level (fade-through, `8488d77`); the in-Play advance is not. **Try:** wrap the
  exercise body in `AnimatedContent` with `Motion.gentle` on the position key.
- **C2.2 / Spatial — Plan sessions remain bordered `SessionCard`s.** The
  baseline recommended a borderless scannable list. The reformed
  collapsed-by-default SessionCard resolves the *wall* (each exercise is one
  row: name + ONE chip + toggle), so this is cosmetic, not a regression.
  **Deferred** — full de-card is a future iteration if the founder wants Plan
  to mirror Today's borderless rhythm exactly.

### Strengths to keep (do not change in founder review)

- **`SessionSummaryCard` killed the duplication** — Today is now a compact
  summary + ONE Play CTA, no inline full list. The single biggest "too much
  text" win.
- **ONE prescription `MetaTag` per exercise** (`DreamTeamApp.kt:1042`) — the
  chip soup is gone; equipment/evidence-level live behind the detail toggle.
- **Every toggle is a real `TextButton` with a 48dp floor** (C2.4/C4.1) —
  "Подробнее/Скрыть" (`:1044`) and "Источники (N)" (`:1172`).
- **Calm motion is wired everywhere it should be** — `AnimatedVisibility` +
  `Motion.calmExpand`/`calmCollapse` on all three expand sites (SessionCard
  detail, citations, `EvidenceCitationCard`); no bounce easing on ambient
  elements.
- **De-carded labeled sections + `Spacing.section` rhythm** give every screen a
  calm chunked read instead of the monotonous 16dp wall.
- **Play is the model surface** — one exercise big and legible (`headlineMedium`),
  single mark-done→advance path, derived resume position, and now a real
  **success state** (`allDone` → «Тренировка завершена!», `PlayScene.kt:119`).
- **Designed empty states** — Play (`EMPTY`), History ("недостаточно данных для
  тренда"), not blank rectangles.
- **Dark theme reads correctly** — all critical pairs pass AA body comfortably
  (evidence 6.0–7.75:1, body 14.1:1, prescription 6.96:1, button 6.62:1); on-device
  render confirmed identical element tree to light.

---

## Contrast audit (computed from Theme.kt hex, WCAG 2.1)

| Pair | Light | Dark | AA body (4.5) |
|------|------:|-----:|:--------------|
| onSurfaceVariant / background (evidence) | 5.46 | 7.75 | PASS |
| onSurfaceVariant / surface (card evidence) | 5.74 | 6.97 | PASS |
| onSurfaceVariant / surfaceVariant (MetaTag) | 4.92 | 6.00 | PASS |
| onSurface body / background | 13.32 | 14.10 | PASS |
| **primary prescription / background** | **3.59** | 6.96 | **light FAIL** / dark PASS |
| **onPrimary / primary (button)** | **3.84** | 6.62 | **light FAIL** / dark PASS |

The two light-mode "FAIL" cells are the should-fix items above (both pass
AA-large 3:1; both are the deliberate sage accent). Every other pair — including
the spec's named risk surface, muted evidence text — passes AA body in both
themes.

---

## Verification performed (DRE-265)

- **On-device render**, `dre_m10` (360dp), light + dark: Onboarding, Today,
  Plan, History, Sources, Settings, Play, Breathing — all render; element trees
  captured (screenshots in run scratch). Dark-mode Today confirmed identical
  tree to light.
- **Contrast** computed from `Theme.kt` hex for every critical text/surface pair
  (table above) — not eyeballed.
- **Structural review** of all surface composables against P1–P7 + the
  anti-pattern negative checks (DESIGN.md).
- Baseline must-fix items 1–8 confirmed fixed at `file:line` anchors (table).

*References: [Iter-0 baseline critique](./design-critique-baseline.md) ·
[DESIGN.md](./DESIGN.md) · [DRE-254 plan](/DRE/issues/DRE-254) · commit
`0286beb` (DRE-259).*
