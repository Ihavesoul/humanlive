# Baseline design-critique — Today / Plan / Play

> Iteration 0 baseline (DRE-254). A prioritized critique of the three primary
> surfaces as they stand **today**, before any sprint change. This is the
> must-fix list the sprint targets; the post-Iteration-4 critique must come back
> with an empty must-fix list. Format follows the
> [design-critique skill](https://…). Findings map to the
> [7-pillar criteria](./DESIGN.md#the-7-pillar-acceptance-criteria) (P1–P7) and
> cite `file:line` anchors.

Pre-critique context: the user opens the app to **act** — do today's workout and
log how it went (Operate mode). Success = they start the workout in one tap and
finish it one exercise at a time. Today is the landing surface; Plan browses the
week; Play walks through one exercise at a time.

---

## Design critique: Today (`DreamTeamApp.kt:600-719`)

### Must-fix (blocks the sprint's stated goal)

- **P2 / Cardocalypse — workout duplication + wall:** Today stacks a full-width
  **Play Button AND the entire `SessionCard`** (all exercises + chips +
  collapsible detail + notes + coach) inside one item
  (`DreamTeamApp.kt:654-655`). Tapping Play opens PlayScene (one-exercise-at-a-time)
  while the full list is *also* right there — two ways to do the same workout,
  plus a wall of text. **Try:** replace the inline `SessionCard` with a compact
  `SessionSummaryCard` (day/label, exercise count, progress, ONE Play CTA).
  DRE-254 Decision 2. *(C2.1, C7.2)*
- **P2 — nutrition & adaptation are bordered Cards:** the nutrition block
  (`:664`) and adaptation block (`:681`) each wrap their content in a `Card`.
  Cards wrapping every labeled section is the core anti-pattern. **Try:** render
  them as labeled sections (label + content, `Spacing.section` separation, no
  border). DRE-254 Decision 1. *(C2.2)*
- **P2 — monotonous spacing, no section rhythm:** the `LazyColumn` uses
  `Arrangement.spacedBy(Spacing.lg)` (`:632`) — 16dp between every item,
  including between top-level groups. No calm break between sections. **Try:**
  ≥ `Spacing.section` (32dp) between top-level groups; tight grouping inside a
  block. *(C2.1)*
- **P4 — bare `Text` + `.clickable` as a button:** the "Подробнее / Скрыть"
  detail toggle (`:966-968`) is a `Text` with `.clickable`, not a real
  `TextButton`. **Try:** a real `TextButton` affordance. *(C4.1)*
- **P4 — bare clickable Row for citations:** the "Источники (N)" affordance
  (`:1083-1086`) is a `Row` with `.clickable`. **Try:** a `TextButton`. *(C4.1)*
- **P7 — chip soup on the exercise row:** `exerciseDensityChips` (`:795-811`)
  emits up to 5 tags (sets, reps, RIR, equipment, evidence-level) that compete
  with the exercise name. **Try:** collapse to ONE prescription chip
  `"{sets}×{repScheme} @{RIR} RIR"`; move equipment/evidence-level to the
  detail view. DRE-254 Decision 4. *(C1.3, C7.3)*

### Should-fix (before broader rollout)

- **P1 — muddled label→content grouping:** the "Тренировка сегодня" label
  (`:649`) sits 2dp (`tightGap`) above a Card that holds *both* the Play button
  and the full SessionCard. Once the duplication is removed, the label should
  title the summary block cleanly.
- **P6 — verify muted evidence text contrast:** citation/evidence rows use
  `onSurfaceVariant` (`Stone` `#6B645B`) — confirm ≥ WCAG AA on the warm
  `Paper` canvas; this is the risk surface for contrast. *(C6.1)*

### Nice-to-fix (when there's room)

- **P7 — redundant disclaimer:** `LOG_HINT` (`:699`) repeats "поддержка, а не
  замена врача," which also appears in the nutrition disclaimer (`:670`). One
  surface, two disclaimers. **Try:** state it once per surface. *(C7.2)*

### Strengths to keep

- The **Play CTA is a real full-width `Button`** (`:654`) — the right primary
  action; it just needs the inline card removed so it stops being duplicated.
- The **side-by-side export/diagnostics row** (`:709`) already splits two
  actions across the row (weight 1f each) — the 2:1-gutter pattern the sprint
  generalizes.
- **`QuickLogActions`** (`:838`) is width-adaptive (`BoxWithConstraints` →
  `FlowRow` on <600dp) — responsive, never truncates the long RU labels.

---

## Design critique: Plan (`DreamTeamApp.kt:508-583`)

### Must-fix (blocks the sprint's stated goal)

- **P2 / Cardocalypse — one giant week Card:** the week header is a single
  `Card` (`:526`) wrapping the week title, the full nutrition plan, warnings,
  and the adaptation note all together (`:536-567`). Nested, card-walled,
  unscannable. **Try:** week title as a section heading; nutrition/adaptation as
  labeled sections (no card); the browsable week as a scannable list. *(C2.1,
  C2.2)*
- **P2 — sessions are dense `SessionCard`s:** each session renders as a full
  `SessionCard` (`:572`) with all exercises + chips + collapsible detail. The
  browsable week reads as a wall of dense cards. **Try:** a scannable
  one-line-per-session list (day/label, exercise count), detail behind a tap.
  *(C2.1)*

### Should-fix (before broader rollout)

- **P2 — monotonous spacing:** `Arrangement.spacedBy(Spacing.lg)` (`:521`) — same
  16dp-everywhere rhythm as Today. *(C2.1)*
- **P7 — evidence interleaved with the week summary:** nutrition citations
  (`:551`) and the disclaimer (`:552`) sit inline inside the week Card with no
  separation from the week title. Demote evidence; separate the chunks.

### Nice-to-fix (when there's room)

- **P1 — two equal-weight headings compete:** the week title (`:536`,
  `titleLarge`) and each session title (`:905`, `titleLarge`) are the same role
  and size, so nothing on the screen reads as the dominant heading. **Try:**
  week title as `headlineMedium`, sessions as `titleLarge`/`titleMedium`.

### Strengths to keep

- The **redundant week-phase chip row was already removed** (`:528-535`
  comment) — good de-duplication instinct; the same instinct drives this sprint.
- **`QuickLogActions` is reused** (`:580`) — consistency with Today.

---

## Design critique: Play (`PlayScene.kt:48-159`)

The cleanest of the three. This is the model Today should move toward — one
exercise, big and legible, one advance path.

### Must-fix (blocks the sprint's stated goal)

- _None blocking._ Play already largely meets the criteria; it is the
  destination for the Play CTA, not a source of the founder's complaints.

### Should-fix (before broader rollout)

- **P5 — no motion on enter / advance:** `markCurrentDone` (`:69-75`) swaps
  state instantly; the screen has no enter transition and the exercise-to-exercise
  swap is a snap. **Try:** screen enter at `Motion.largeMs`; cross-fade the
  exercise swap (`Motion.gentle`). *(C5.2)*
- **P4 — breathing entry competes with the primary action:** the breathing
  `FilledTonalButton` (`:109`) sits above the exercise and reads nearly as loud
  as the Done button. **Try:** demote it (quieter placement/style) so "Готово ·
  далее" stays the unambiguous primary.

### Nice-to-fix (when there's room)

- **States — no success/completion confirmation:** the empty state is designed
  (`PlayStrings.EMPTY`, `:82`), but completion only changes the button label to
  FINISH (`:150-151`). **Try:** a calm completion state that closes the loop
  (design-critique §4 success state).
- **P6 — prescription in `primary`:** the prescription line is `primary`-colored
  (`:140`). Defensible — it's the focus element, not evidence — but verify it
  reads as accent-on-purpose, not accidental. (DESIGN.md keeps step badges in
  `primary`; accent on the focus element is consistent.)

### Strengths to keep

- **Unambiguous hierarchy:** the current exercise is `headlineMedium`, bold,
  centered (`:131-136`) — the largest element on the screen. Exactly right.
- **Single mark-done → advance path** (`:153-156`) — no dual semantics; one
  clear primary action.
- **Derived-from-completion position** (`:64-67`) — a half-done session resumes
  on the next exercise to do, never exercise 1 again. Excellent.
- **Prescription is one line** (`PlayStrings.prescription`, `:171-172`) — the
  model for the exercise-row prescription chip on Today/Plan.

---

## Sprint targeting summary

The founder's three complaints map cleanly onto the must-fix list:

| Complaint | Root cause | Must-fix owner |
|-----------|-----------|----------------|
| "too much text" | workout duplication (Play + full SessionCard) + chip soup + monotonous dense cards | Today must-fix #1, #6; Plan must-fix #2 |
| "exercises jumbled with source notes" | evidence-level/`Источники (N)` chips interleaved with the prescription | Today must-fix #6; Plan should-fix #2 |
| "no UI/UX person worked on it" | Cardocalypse + bare-text-buttons + no section rhythm | Today must-fix #2,#3,#4,#5; Plan must-fix #1 |

Play is the positive reference: it already does one-primary-action,
derived-state, single-prescription-line, big-legible-focus right. The sprint
brings Today/Plan up to Play's standard.
