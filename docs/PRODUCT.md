# PRODUCT.md — humanlive product context

> Captured once, read by every design/build pass before touching a surface.
> Authored in Iteration 0 of the [DRE-254](/DRE/issues/DRE-254) UI/UX design
> sprint. This is the **PRODUCT.md** in the impeccable.style sense: the
> product's platform, audience, posture, and evidence — the context a designer
> needs before deciding *what* a surface is for, separate from *how* it looks
> (that is [`DESIGN.md`](./DESIGN.md)).

## Platform

**Android, Kotlin + Jetpack Compose, Material 3.** Offline-first: the plan is
generated locally from an encrypted SQLite store; the **only** network call in
the entire app is a single remote exercise image (Coil2/AsyncImage), which
degrades gracefully to a branded placeholder when offline. No accounts, no
sync, no analytics, no telemetry. Single-user, on-device.

- Package: `dreamteam.app`. Min target widths of concern: **360dp / 411dp /
  600dp**; landscape must not collapse Today/Play into an unusable column.
- Language: **entirely Russian (Cyrillic)**. Long exercise names and descriptions
  must never clip on a narrow phone. Every display face must ship full Cyrillic
  (see DESIGN.md §3 — Lora was chosen over Fraunces for exactly this reason).

## Users

**A single person managing their own training, on their own phone, in Russian.**
Not a "fitness enthusiast" persona — a real user doing today's workout and
logging how it went. They open the app to **act**, not to browse. They are often
reading fast, sometimes one-handed, sometimes mid-workout. They are not a
clinician and the app never treats them like one.

## Mode

**Operate.** (impeccable's four modes: Persuade / Operate / Read / Experience.)
The visitor completes a task: *do today's workout and log how it went.*
Scanability and native mobile expectations outrank expression. Brand lives in
precise details (the serif/sans pairing, the warm palette, the calm motion), not
in hero moments. There is nothing to persuade here and no content to
read-for-comprehension first; the workout leads.

## Positioning

A **calm, evidence-linked training journal** — not a "fitness app," not a coach
replacement. The differentiator is honesty: every prescription is traceable to a
sourced citation, and the app says plainly what it does and does not know. The
product voice is **calm, clinical, no hype** — support framing only, never a
medical claim.

## Evidence on hand

- A **deterministic baseline program** (sessions, exercises, sets/reps/RIR,
  equipment) generated locally from the user's profile + logged symptoms/progress.
- A **catalogued evidence base**: per-exercise citations with author/year,
  key-finding, evidence level, design/application/limitations, and a source URL.
  Citations are a **feature**, not decoration — but they must **recede behind the
  workout**, never compete with it.
- Per-exercise **media** (a card image, how-to steps, video/image links) and an
  optional **AI coach** (gated, user-supplied credentials, off by default).
- Logged self-report: **symptoms**, **progress** (weight), per-exercise
  **notes/outcomes**. These feed the next week's volume via a safety-gated
  de-load signal.

## What the surfaces are for

| Surface | The user's job (one sentence) | Primary action |
|---------|-------------------------------|----------------|
| **Today** | *"What do I do right now, and how do I start?"* | ONE **Play** CTA → opens the workout in PlayScene. A compact summary (day, exercise count, progress) + the day's nutrition/adaptation as quiet labeled sections. |
| **Plan** | *"What does my week look like?"* | Browse a scannable week list (sessions as a list, not dense cards). |
| **Play** | *"Walk me through this one exercise, then the next."* | The current exercise big and legible; **done → next** advance; a progress readout; a breathing entry. One exercise at a time. |
| **History** | *"What did I log?"* | Read-only trend of symptoms/progress (reflects what's logged, never writes). |
| **Evidence Sources** | *"Where does this come from?"* | The full citation catalog (the home of evidence; the workout surface only points to it). |
| **Symptoms / Progress** | *"Log how it went."* | Quick self-report writes that feed the next week's volume. |
| **Onboarding / Settings** | *"Set up / adjust my profile and coach."* | Profile, AI-coach credentials, font scale. |

The load-bearing design decisions (DRE-254) resolve the founder's three
complaints and are binding:

1. **Stop wrapping everything in cards.** Adopt the 3-tier surface hierarchy
   (Paper → labeled section → Card-for-self-contained-unit only). See DESIGN.md §8.
2. **Split the workout into two jobs, never both at once.** Today = summary +
   ONE Play CTA; full per-exercise detail lives in PlayScene. Today must NOT also
   stack the entire `SessionCard`.
3. **Evidence leaves the exercise row** — demoted, not deleted. The default row
   shows the prescription only; equipment/evidence/how-to/citations/media live
   behind a single "Подробнее" → muted detail.

## Brand voice

- **Calm, clinical, no hype.** Russian-language.
- **Support framing only:** "поддержка, а не замена врача." No diagnosis, no
  "у вас …", no treatment/cure claim, no outcome promise. This is a hard
  invariant enforced by a JVM banned-phrase test on every authored string.
- **Verbs that name the outcome:** `Начать тренировку`, `Сохранить`,
  `Готово · далее`, `Записать симптом`. Not nouns, not `Submit`.
- **Say it once.** The disclaimer appears once per surface that needs it, not in
  every card.

## Anti-references (what humanlive is NOT)

- **Not** a "make it pop" fitness app: no purple/blue gradients, no neon glows,
  no glassmorphism, no dark-mode-with-glowing-accents.
- **Not** a card-walled dashboard: no cards wrapping every labeled section, no
  nested cards.
- **Not** a medical device: never a diagnosis, prescription-as-treatment, or
  cure claim. Evidence is surfaced for transparency, not as a treatment
  endorsement.
- **Not** a social/competitive tracker: no streaks-shaming, no leaderboards, no
  "boost your productivity" energy.
- **Not** a wall of text: one primary action per surface; evidence recedes.

---

*Companion: [`DESIGN.md`](./DESIGN.md) (the visual system). Sprint spec:
[DRE-254](/DRE/issues/DRE-254#document-plan).*
