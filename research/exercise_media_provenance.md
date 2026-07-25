# Exercise media provenance — DRE-79 (M8-A1) + DRE-103 (follow-up)

Audit trail for the exercise media populated in `data/exercises.json`
(`video_url`, `how_to_steps_ru`, `image_refs`). Maintained by the
Evidence & Research Analyst. Every media ref resolves to a real,

license-clean source — primarily **Wikimedia Commons** (PD / CC0 / CC BY /
CC BY-SA); DRE-103 additionally admits **Flickr CC BY** photos from a
contributor already vetted in this catalog (Eric Astrauskas / PTinTO —
`bent_rotational_row`) when no Commons match exists. Credit + license are
recorded below for every ref, Commons or Flickr.

## Method

- Source: Wikimedia Commons API (`commons.wikimedia.org/w/api.php`), namespace 6
  (File); for DRE-103 gaps, Openverse (`api.openverse.org`) aggregating Flickr CC.
- License gate: only `public domain`, `cc0`, `cc by *`, `cc by-sa *`, `gfdl` accepted.
- Relevance gate: the file/photo title had to contain an exercise-specific token
  (e.g. `goblet`, `squat`); the DRE-103 Commons sweep additionally restricted
  exercise schematics to purpose-made contributors (Everkinetic, CDC, Danielflefil)
  and Flickr photos to an exact-title match from the already-vetted PTinTO album.
  Noisy matches were dropped (see Rejected below).
- Every chosen URL was verified to resolve (HTTP 200/206) at sourcing time.

## How the app surfaces these (license-defensibility)

`data/exercises.json` stores the **source page URL** — the Commons file-page URL
(`https://commons.wikimedia.org/wiki/File:…`) or, for the two Flickr refs added in
DRE-103, the Flickr photo-page URL. The references card
(`ClientExerciseReferences.ReferencesCard`) renders each ref as a button →
`openUrl()` (it **links**, never reproduces inline). Tapping opens the source page,
which prominently shows author + license + source — i.e. attribution is available
at the link target. Linking to a work is not a CC-restricted act, so this is
license-defensible for PD/CC0/CC BY/CC BY-SA alike.

**If the UI ever switches to inline image rendering** (download + `Image()`), the
schema must carry `{url, license, credit}` per ref so CC BY/BY-SA attribution can be
shown at display. The raw image URLs + license + credit are preserved in the table
below for exactly that promotion. That is the trigger to extend the schema

(currently `video_url: String?`, `image_refs: List<String>`, DRE-86).

## Coverage

- how-to steps: **all 36** exercises.
- media: **27/36** exercises (2 video, 33 images; +8 exercises sourced in DRE-103).
- how-to-only (no clean, verifiable-relevance match found after the DRE-103 sweep
  of Commons + Openverse/Flickr CC): **9** — wall_axial_elongation,
  single_leg_rdl_supported, one_arm_row_supported, prone_ytw, dead_bug, wall_slide,
  cable_woodchop, landmine_rotation, loaded_good_morning_rotation.

## Sourced media (full provenance)

| Exercise | Type | License | Credit | Commons file | File page | Raw asset |
|---|---|---|---|---|---|---|
| `warm_breathing` | image | CC BY-SA 4.0 | Renato yoga | File:The Basic of Pranayama Cycle.jpg | [page](https://commons.wikimedia.org/wiki/File:The_Basic_of_Pranayama_Cycle.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/a/a4/The_Basic_of_Pranayama_Cycle.jpg/960px-The_Basic_of_Pranayama_Cycle.jpg) |
| `quadruped_rockback` | image | CC BY-SA 4.0 | Nolabob | File:ChildsPose3.jpg | [page](https://commons.wikimedia.org/wiki/File:ChildsPose3.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/3/3e/ChildsPose3.jpg/960px-ChildsPose3.jpg) |
| `split_squat` | image | CC BY-SA 3.0 | Everkinetic | File:Side-split-squats-1-1024x600.png | [page](https://commons.wikimedia.org/wiki/File:Side-split-squats-1-1024x600.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/2/2e/Side-split-squats-1-1024x600.png/960px-Side-split-squats-1-1024x600.png) |
| `split_squat` | image | CC BY-SA 3.0 | Everkinetic | File:Side-split-squats-2-1024x600.png | [page](https://commons.wikimedia.org/wiki/File:Side-split-squats-2-1024x600.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/1/17/Side-split-squats-2-1024x600.png/960px-Side-split-squats-2-1024x600.png) |
| `goblet_squat` | video | CC BY 3.0 | FitnessScape | File:Squat - exercise demonstration video.webm | [page](https://commons.wikimedia.org/wiki/File:Squat_-_exercise_demonstration_video.webm) | [asset](https://upload.wikimedia.org/wikipedia/commons/5/5c/Squat_-_exercise_demonstration_video.webm) |
| `reverse_lunge` | video | CC BY-SA 4.0 | Taco fleur | File:Dead Snatch into Reverse Lunge.webm | [page](https://commons.wikimedia.org/wiki/File:Dead_Snatch_into_Reverse_Lunge.webm) | [asset](https://upload.wikimedia.org/wikipedia/commons/b/bb/Dead_Snatch_into_Reverse_Lunge.webm) |
| `reverse_lunge` | image | Public domain | Centers for Disease Control and Prevention | File:Lunge-CDC strength training for older adults.gif | [page](https://commons.wikimedia.org/wiki/File:Lunge-CDC_strength_training_for_older_adults.gif) | [asset](https://upload.wikimedia.org/wikipedia/commons/a/af/Lunge-CDC_strength_training_for_older_adults.gif) |
| `b_stance_rdl` | image | CC BY 2.5 | Luis Javier Rodriguez / Yupi666 | File:Deadlift illustration.jpg | [page](https://commons.wikimedia.org/wiki/File:Deadlift_illustration.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/2/2e/Deadlift_illustration.jpg) |
| `glute_bridge` | image | CC BY-SA 4.0 | Marianne Gilbak | File:Glute-bridge.png | [page](https://commons.wikimedia.org/wiki/File:Glute-bridge.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/3/34/Glute-bridge.png/960px-Glute-bridge.png) |
| `pushup` | image | CC BY-SA 3.0 | Everkinetic | File:Push-up-1.png | [page](https://commons.wikimedia.org/wiki/File:Push-up-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/9/9e/Push-up-1.png) |
| `pushup` | image | CC BY-SA 3.0 | Everkinetic | File:Push-up-2.png | [page](https://commons.wikimedia.org/wiki/File:Push-up-2.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/c/cf/Push-up-2.png) |
| `db_floor_press` | image | CC BY-SA 3.0 | Everkinetic | File:Decline-dumbbell-bench-press-1.png | [page](https://commons.wikimedia.org/wiki/File:Decline-dumbbell-bench-press-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/0/0e/Decline-dumbbell-bench-press-1.png) |
| `bird_dog` | image | CC BY 2.0 | PTPioneer | File:Bird dog exercise.jpg | [page](https://commons.wikimedia.org/wiki/File:Bird_dog_exercise.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/8/82/Bird_dog_exercise.jpg/960px-Bird_dog_exercise.jpg) |
| `suitcase_hold_equal` | image | CC BY-SA 3.0 | Artur Andrzej | File:Farmer's Walk.jpg | [page](https://commons.wikimedia.org/wiki/File:Farmer's_Walk.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/1/1c/Farmer%27s_Walk.jpg/960px-Farmer%27s_Walk.jpg) |
| `brisk_walk` | image | CC BY 2.0 | bluesbby from Mountain View, USA | File:Walking exercise (16818224061).jpg | [page](https://commons.wikimedia.org/wiki/File:Walking_exercise_(16818224061).jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/c/ca/Walking_exercise_%2816818224061%29.jpg/960px-Walking_exercise_%2816818224061%29.jpg) |
| `gentle_yoga_flow` | image | CC BY 4.0 | Vyacheslav Argenberg | File:Koh Wai, Thailand, Yoga, Asana.jpg | [page](https://commons.wikimedia.org/wiki/File:Koh_Wai,_Thailand,_Yoga,_Asana.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/a/ad/Koh_Wai%2C_Thailand%2C_Yoga%2C_Asana.jpg/960px-Koh_Wai%2C_Thailand%2C_Yoga%2C_Asana.jpg) |
| `barbell_back_squat` | image | CC0 | RickyBennison | File:Barbell pad back squat.jpg | [page](https://commons.wikimedia.org/wiki/File:Barbell_pad_back_squat.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/3/32/Barbell_pad_back_squat.jpg/960px-Barbell_pad_back_squat.jpg) |
| `barbell_back_squat` | image | CC BY 2.0 | Nenad Stojkovic | File:Woman doing squat workout in gym with barbell, back view.jpg | [page](https://commons.wikimedia.org/wiki/File:Woman_doing_squat_workout_in_gym_with_barbell,_back_view.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/f/f9/Woman_doing_squat_workout_in_gym_with_barbell%2C_back_view.jpg/960px-Woman_doing_squat_workout_in_gym_with_barbell%2C_back_view.jpg) |
| `overhead_barbell_press` | image | CC BY-SA 4.0 | RangerJim | File:How to do an Overhead Press.jpg | [page](https://commons.wikimedia.org/wiki/File:How_to_do_an_Overhead_Press.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/2/25/How_to_do_an_Overhead_Press.jpg/960px-How_to_do_an_Overhead_Press.jpg) |
| `overhead_barbell_press` | image | CC BY-SA 4.0 | Richardkiwi | File:Military press ez-bar 25022008.jpg | [page](https://commons.wikimedia.org/wiki/File:Military_press_ez-bar_25022008.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/8/8d/Military_press_ez-bar_25022008.jpg/960px-Military_press_ez-bar_25022008.jpg) |
| `barbell_deadlift` | image | CC BY-SA 2.0 | stu_spivack | File:Deadlift Barbell.JPG | [page](https://commons.wikimedia.org/wiki/File:Deadlift_Barbell.JPG) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/1/13/Deadlift_Barbell.JPG/960px-Deadlift_Barbell.JPG) |
| `barbell_deadlift` | image | Public domain | U.S. Air Force photo by Senior Airman Clayton Lenhardt | File:Deadlift grip.JPG | [page](https://commons.wikimedia.org/wiki/File:Deadlift_grip.JPG) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Deadlift_grip.JPG/960px-Deadlift_grip.JPG) |
| `heavy_farmer_carry` | image | Public domain | Artur Andrzej | File:Farmer's Walk equipment.jpg | [page](https://commons.wikimedia.org/wiki/File:Farmer's_Walk_equipment.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/0/05/Farmer%27s_Walk_equipment.jpg/960px-Farmer%27s_Walk_equipment.jpg) |
| `heavy_farmer_carry` | image | CC BY-SA 2.0 | stu_spivack | File:Farmers Walk.JPG | [page](https://commons.wikimedia.org/wiki/File:Farmers_Walk.JPG) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/9/97/Farmers_Walk.JPG/960px-Farmers_Walk.JPG) |
| `bent_rotational_row` | image | CC BY-SA 3.0 | Everkinetic | File:Barbell-rear-delt-row-1.png | [page](https://commons.wikimedia.org/wiki/File:Barbell-rear-delt-row-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/a/af/Barbell-rear-delt-row-1.png) |
| `bent_rotational_row` | image | CC BY 2.0 | Eric Astrauskas, www.PTinTO.com | File:Landmine Bent-Over Rows.jpg | [page](https://commons.wikimedia.org/wiki/File:Landmine_Bent-Over_Rows.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/4/46/Landmine_Bent-Over_Rows.jpg/960px-Landmine_Bent-Over_Rows.jpg) |
| `heavy_rotational_carry` | image | CC BY-SA 4.0 | Mstephen247 | File:Man carrying suitcase and bag.jpg | [page](https://commons.wikimedia.org/wiki/File:Man_carrying_suitcase_and_bag.jpg) | [asset](https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/Man_carrying_suitcase_and_bag.jpg/960px-Man_carrying_suitcase_and_bag.jpg) |
| `barbell_front_squat` | image | CC BY-SA 3.0 | Everkinetic | File:Front-squat-1-857x1024.png | [page](https://commons.wikimedia.org/wiki/File:Front-squat-1-857x1024.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/c/c3/Front-squat-1-857x1024.png) |
| `barbell_front_squat` | image | CC BY-SA 3.0 | Everkinetic | File:Front-squat-2-857x1024.png | [page](https://commons.wikimedia.org/wiki/File:Front-squat-2-857x1024.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/0/02/Front-squat-2-857x1024.png) |
| `barbell_good_morning` | image | CC BY-SA 3.0 | Everkinetic | File:Good-mornings-1.png | [page](https://commons.wikimedia.org/wiki/File:Good-mornings-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/a/a1/Good-mornings-1.png) |
| `barbell_good_morning` | image | CC BY-SA 3.0 | Everkinetic | File:Good-mornings-2.png | [page](https://commons.wikimedia.org/wiki/File:Good-mornings-2.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/7/70/Good-mornings-2.png) |
| `side_plank_equal` | image | CC BY-SA 3.0 | Everkinetic | File:Side-plank-1.png | [page](https://commons.wikimedia.org/wiki/File:Side-plank-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/f/f0/Side-plank-1.png) |
| `side_plank_equal` | image | CC BY-SA 3.0 | Everkinetic | File:Side-plank-2.png | [page](https://commons.wikimedia.org/wiki/File:Side-plank-2.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/0/0a/Side-plank-2.png) |
| `reverse_fly` | image | CC BY-SA 3.0 | Everkinetic | File:Lying-rear-lateral-raise-1.png | [page](https://commons.wikimedia.org/wiki/File:Lying-rear-lateral-raise-1.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/5/51/Lying-rear-lateral-raise-1.png) |
| `reverse_fly` | image | CC BY-SA 3.0 | Everkinetic | File:Lying-rear-lateral-raise-2.png | [page](https://commons.wikimedia.org/wiki/File:Lying-rear-lateral-raise-2.png) | [asset](https://upload.wikimedia.org/wikipedia/commons/b/b4/Lying-rear-lateral-raise-2.png) |
| `wall_hip_abduction` | image | Public domain | Centers for Disease Control and Prevention | File:Hip abduction-CDC strength training for older adults.gif | [page](https://commons.wikimedia.org/wiki/File:Hip_abduction-CDC_strength_training_for_older_adults.gif) | [asset](https://upload.wikimedia.org/wikipedia/commons/d/db/Hip_abduction-CDC_strength_training_for_older_adults.gif) |
| `pike_pushup_optional` | image | CC BY-SA 4.0 | Danielflefil | File:Pike Push Ups.gif | [page](https://commons.wikimedia.org/wiki/File:Pike_Push_Ups.gif) | [asset](https://upload.wikimedia.org/wikipedia/commons/e/e2/Pike_Push_Ups.gif) |
| `bulgarian_split_squat` | image | CC BY 2.0 | Eric Astrauskas, www.PTinTO.com | Flickr 42990005625 “Bulgarian Squats” | [page](https://www.flickr.com/photos/121183998@N08/42990005625) | [asset](https://live.staticflickr.com/930/42990005625_326c9171c8_b.jpg) |
| `loaded_russian_twist` | image | CC BY 2.0 | Eric Astrauskas, www.PTinTO.com | Flickr 30283139408 “Russian Twists” | [page](https://www.flickr.com/photos/121183998@N08/30283139408) | [asset](https://live.staticflickr.com/1882/30283139408_6ef2d3250d_b.jpg) |

## DRE-103 sourcing notes (relevance caveats)

- `reverse_fly`: the Lying-rear-lateral-raise pair depicts the prone-supported
  rear-delt fly — the same posterior-deltoid pattern as the bent-over reverse
  fly; included as the movement-pattern reference (Commons has no bent-over
  reverse-fly schematic).
- `wall_hip_abduction`: the CDC gif depicts a **standing band-assisted** hip
  abduction, not the wall-isometric. Same frontal-plane movement pattern; shown
  as the pattern reference until a wall-isometric-specific depiction is sourced.
- `bulgarian_split_squat` and `loaded_russian_twist`: Flickr CC BY 2.0 photos by
  **Eric Astrauskas / PTinTO** — the same contributor already vetted in this
  catalog for `bent_rotational_row`. Sourced on (a) vetted contributor + (b)
  exact exercise-name title. **The depiction was not visually inspected by the
  Evidence Analyst** (no image-viewing capability in the run); the references
  card only links, so the user verifies the depiction at the Flickr page. The
  raw staticflickr asset + license + credit are preserved above for the
  inline-render promotion.

## Rejected at curation (relevance noise)

Auto-search surfaced files whose title matched a token but did not depict the
movement. All were dropped (not shipped) — listed so the gap is explicit:

- `glute_bridge`: river/road bridges (architecture) — token `bridge` matched geography.
- `pike_pushup_optional`: photos of hockey player *Alf Pike* — token `pike` matched a surname.
- `cable_woodchop`: *Calendar Plate…Chopping Wood* (LACMA art) — token `wood`/`chop` matched a painting.
- `reverse_fly`: *Toyota Crown…Fly-Drive* (car) — tokens matched a motoring event.
- `wall_hip_abduction`: *Kappa Caught by Clamshell* (LACMA art) — token `clamshell` matched art.
- `loaded_russian_twist`: *Twist braid* (hairstyle) — token `twist` matched a braid.
- `landmine_rotation`: *Landmine Press* (LeBron James) — a press, not a rotation.
- `one_arm_row_supported`: *Leg Rowing Fisherman* / seated cable row — not a one-arm DB row.
- `heavy_rotational_carry` 2nd ref: *Suitcase (AM 2007…)* — a museum object, not a carry.

## How-to-only exercises — why (9 remaining after DRE-103)

DRE-103 swept Commons (Everkinetic / CDC / Danielflefil / PTPioneer /
FitnessScape / Taco fleur sets + broad title search) and Openverse/Flickr CC
for all 17. **8 closed** (above). These **9** have no clean, verifiable-relevance
match — they stay how-to text only (the references card surfaces the transparent
MEDIA_PENDING marker, never a fabricated link). They do **not** block the UI
chain or the M8 gate [DRE-91](/DRE/issues/DRE-91). Recommended next steps for a
follow-up: commission original CC0 line illustrations for the rehab-specific
movements (wall axial elongation, prone YTW, wall slide, dead bug) where no
licence-clean photo exists; for the gym movements (single-leg RDL, one-arm row,
cable woodchop, landmine rotation, good-morning rotation) re-scan Flickr CC BY
by visually confirming each candidate at sourcing time.

- `wall_axial_elongation` — Осевое вытяжение у стены
- `single_leg_rdl_supported` — Одноногая тяга с опорой
- `one_arm_row_supported` — Тяга гантели одной рукой с опорой
- `prone_ytw` — Y–T–W лёжа
- `dead_bug` — Dead bug
- `wall_slide` — Скольжение руками по стене
- `cable_woodchop` — Дровосек на кроссовере (cable woodchop)
- `landmine_rotation` — Ротация со штангой в земле (landmine)
- `loaded_good_morning_rotation` — Гудморнинг со штангой с ротацией
