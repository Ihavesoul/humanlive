package dreamteam.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * On-device offline-first store for the native client. Plain [SQLiteOpenHelper]
 * — no Room/KSP, no extra native deps, keeping the build boring (ADR 0001/0002).
 *
 * What lives here: the user profile, workout-completion logs, and symptom logs.
 * The plan itself is **not** stored: it is a pure deterministic function of the
 * profile (via [:core:domain] [dreamteam.domain.training.DeterministicPlanGenerator]),
 * regenerated on each launch. Single source of truth = the profile; the plan is
 * computed, never cached, so it can never drift from the safety gate.
 *
 * Schema version is fixed at 1 (baseline). A future bump is a migration in
 * [onUpgrade]; health-signal data is never silently dropped.
 *
 * Encryption: this is the unencrypted M2-A store. SQLCipher (encrypted at rest)
 * is the M3 hardening for this on-device surface (ADR 0003). ponytail: plain
 * SQLite now; slot SQLCipher when the device threat model is wired.
 */
class LocalDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    fun saveProfile(profile: Profile) = writableDatabase.useProfileRow { db ->
        val cv = ContentValues().apply {
            put(COL_SEX, profile.sex)
            put(COL_AGE, profile.age)
            put(COL_HEIGHT, profile.height)
            put(COL_WEIGHT, profile.weight)
            put(COL_BODY_FAT, profile.bodyFat)
            put(COL_SCOLIOSIS, if (profile.scoliosisReported) 1 else 0)
            put(COL_RED_FLAGS, profile.redFlags.joinToString(","))
            put(COL_CREATED_ON, profile.createdOn)
        }
        db.insertWithOnConflict(TABLE_PROFILE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadProfile(): Profile? = readableDatabase.useProfileRow { db ->
        db.query(TABLE_PROFILE, null, "$COL_ID=0", null, null, null, null).use { c ->
            if (!c.moveToFirst()) null else Profile(
                sex = c.getString(c.getColumnIndexOrThrow(COL_SEX)),
                age = c.getInt(c.getColumnIndexOrThrow(COL_AGE)),
                height = c.getDouble(c.getColumnIndexOrThrow(COL_HEIGHT)),
                weight = c.getDouble(c.getColumnIndexOrThrow(COL_WEIGHT)),
                bodyFat = if (c.isNull(c.getColumnIndexOrThrow(COL_BODY_FAT))) null else c.getDouble(c.getColumnIndexOrThrow(COL_BODY_FAT)),
                scoliosisReported = c.getInt(c.getColumnIndexOrThrow(COL_SCOLIOSIS)) == 1,
                redFlags = c.getString(c.getColumnIndexOrThrow(COL_RED_FLAGS))
                    ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                createdOn = c.getString(c.getColumnIndexOrThrow(COL_CREATED_ON)),
            )
        }
    }

    fun logWorkout(sessionId: String, exerciseId: String, doneOn: String) = writableDatabase.useProfileRow { db ->
        val cv = ContentValues().apply {
            put(COL_SESSION, sessionId); put(COL_EXERCISE, exerciseId); put(COL_DONE_ON, doneOn)
        }
        db.insertWithOnConflict(TABLE_WORKOUT, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun completedExercises(sessionId: String): Set<String> = readableDatabase.useProfileRow { db ->
        db.query(true, TABLE_WORKOUT, arrayOf(COL_EXERCISE), "$COL_SESSION=?", arrayOf(sessionId), null, null, null, null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun appendSymptom(text: String, recordedOn: String) = writableDatabase.useProfileRow { db ->
        val cv = ContentValues().apply { put(COL_RECORDED_ON, recordedOn); put(COL_TEXT, text) }
        db.insert(TABLE_SYMPTOM, null, cv)
    }

    fun recentSymptoms(limit: Int = 20): List<SymptomEntry> = readableDatabase.useProfileRow { db ->
        db.query(TABLE_SYMPTOM, arrayOf(COL_RECORDED_ON, COL_TEXT), null, null, null, null, "$COL_RECORDED_ON DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(SymptomEntry(c.getString(0), c.getString(1))) }
        }
    }

    /**
     * M5-A ([DRE-61](/DRE/issues/DRE-61)): append a body-weight progress point.
     * Mirrors [appendSymptom]. Body weight is the MVP field the adaptation loop's
     * RapidWeightLoss trigger consumes (Decision_Rules `r < -0.0075`); a single
     * point is trend noise, the *trend* is the signal. No diagnosis is stored —
     * this is raw user-measured input, framed as support data, not medical.
     */
    fun appendProgress(weightKg: Double, recordedOn: String) = writableDatabase.useProfileRow { db ->
        val cv = ContentValues().apply { put(COL_RECORDED_ON, recordedOn); put(COL_WEIGHT, weightKg) }
        db.insert(TABLE_PROGRESS, null, cv)
    }

    /** Newest-first [ProgressRow]s for the local adaptation signal + UI list. */
    fun recentProgress(limit: Int = 20): List<ProgressRow> = readableDatabase.useProfileRow { db ->
        db.query(TABLE_PROGRESS, arrayOf(COL_RECORDED_ON, COL_WEIGHT), null, null, null, null, "$COL_RECORDED_ON DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(ProgressRow(c.getString(0), c.getDouble(1))) }
        }
    }

    /**
     * M8-B ([DRE-78](/DRE/issues/DRE-78)): upsert a free-text note for one exercise
     * in one session ("что вышло / что нет / боль"). UNIQUE(session, exercise) +
     * REPLACE ⇒ latest note wins (mirrors [logWorkout]'s per-(session, exercise)
     * keying — a note is a field on the exercise, not an append-only time series).
     * Stored verbatim as the user's self-report; no interpretation, no diagnosis.
     */
    /**
     * M9-A ([DRE-112](/DRE/issues/DRE-112)): the note now carries a structured
     * outcome flag (`ok / hard / painful / skipped`) alongside the free text —
     * a light user self-report. Stored verbatim; never interpreted, never acted
     * on (a `painful` flag is recorded, not a plan change — adaptation from notes
     * is the gated M9-D).
     */
    fun appendExerciseNote(
        sessionId: String,
        exerciseId: String,
        note: String,
        outcome: ExerciseNoteOutcome?,
        recordedOn: String,
    ) = writableDatabase.useProfileRow { db ->
            val cv = ContentValues().apply {
                put(COL_SESSION, sessionId)
                put(COL_EXERCISE, exerciseId)
                put(COL_TEXT, note)
                put(COL_OUTCOME, outcome?.storage)
                put(COL_RECORDED_ON, recordedOn)
            }
            db.insertWithOnConflict(TABLE_EXERCISE_NOTE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

    /**
     * The current note for one (session, exercise) — text + the M9-A outcome flag
     * — or null if none was saved. Latest wins (upsert).
     */
    fun exerciseNote(sessionId: String, exerciseId: String): ExerciseNoteRow? = readableDatabase.useProfileRow { db ->
        db.query(
            TABLE_EXERCISE_NOTE, arrayOf(COL_TEXT, COL_OUTCOME, COL_RECORDED_ON),
            "$COL_SESSION=? AND $COL_EXERCISE=?", arrayOf(sessionId, exerciseId),
            null, null, null, "1",
        ).use { c ->
            if (!c.moveToFirst()) null else {
                val idxOutcome = c.getColumnIndexOrThrow(COL_OUTCOME)
                ExerciseNoteRow(
                    sessionId = sessionId,
                    exerciseId = exerciseId,
                    note = c.getString(c.getColumnIndexOrThrow(COL_TEXT)),
                    outcome = ExerciseNoteOutcome.fromStorage(if (c.isNull(idxOutcome)) null else c.getString(idxOutcome)),
                    recordedOn = c.getString(c.getColumnIndexOrThrow(COL_RECORDED_ON)),
                )
            }
        }
    }

    /** All notes for a session — the coach (M8-C) reads these as its per-exercise input. */
    fun sessionExerciseNotes(sessionId: String): List<ExerciseNoteRow> = readableDatabase.useProfileRow { db ->
        db.query(
            TABLE_EXERCISE_NOTE, arrayOf(COL_EXERCISE, COL_TEXT, COL_OUTCOME, COL_RECORDED_ON),
            "$COL_SESSION=?", arrayOf(sessionId),
            null, null, "$COL_EXERCISE ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val outcome = if (c.isNull(2)) null else c.getString(2)
                    add(ExerciseNoteRow(sessionId, c.getString(0), c.getString(1), ExerciseNoteOutcome.fromStorage(outcome), c.getString(3)))
                }
            }
        }
    }

    /** M8-B (DRE-78): every exercise-note row, exercise-then-date order, for export completeness. */
    fun allExerciseNotes(): List<ExerciseNoteRow> = readableDatabase.useProfileRow { db ->
        db.query(
            TABLE_EXERCISE_NOTE, arrayOf(COL_SESSION, COL_EXERCISE, COL_TEXT, COL_OUTCOME, COL_RECORDED_ON),
            null, null, null, null, "$COL_EXERCISE, $COL_RECORDED_ON",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val outcome = if (c.isNull(3)) null else c.getString(3)
                    add(ExerciseNoteRow(c.getString(0), c.getString(1), c.getString(2), ExerciseNoteOutcome.fromStorage(outcome), c.getString(4)))
                }
            }
        }
    }

    /**
     * M7-A ([DRE-72](/DRE/issues/DRE-72)): every workout-completion row, in a
     * stable deterministic order (session_id, exercise_id) for export. Unlike
     * [completedExercises] (a per-session id set for the UI checkbox state),
     * this is the verbatim row set the export copies out — none silently dropped.
     */
    fun allWorkouts(): List<WorkoutCompletion> = readableDatabase.useProfileRow { db ->
        db.query(
            TABLE_WORKOUT, arrayOf(COL_SESSION, COL_EXERCISE, COL_DONE_ON),
            null, null, null, null, "$COL_SESSION, $COL_EXERCISE",
        ).use { c ->
            buildList { while (c.moveToNext()) add(WorkoutCompletion(c.getString(0), c.getString(1), c.getString(2))) }
        }
    }

    /**
     * M7-A (DRE-72): every symptom row, newest-first (the order [recentSymptoms]
     * preserves). Unbounded read for export completeness — a default-limit change
     * on [recentSymptoms] can never silently truncate an export.
     */
    fun allSymptoms(): List<SymptomEntry> =
        readableDatabase.useProfileRow { it.query(TABLE_SYMPTOM, arrayOf(COL_RECORDED_ON, COL_TEXT), null, null, null, null, "$COL_RECORDED_ON DESC").use { c -> buildList { while (c.moveToNext()) add(SymptomEntry(c.getString(0), c.getString(1))) } } }

    /** M7-A (DRE-72): every progress row, newest-first (mirrors [recentProgress]). */
    fun allProgress(): List<ProgressRow> =
        readableDatabase.useProfileRow { it.query(TABLE_PROGRESS, arrayOf(COL_RECORDED_ON, COL_WEIGHT), null, null, null, null, "$COL_RECORDED_ON DESC").use { c -> buildList { while (c.moveToNext()) add(ProgressRow(c.getString(0), c.getDouble(1))) } } }

    // writableDatabase/readableDatabase return a cached handle the helper manages;
    // we close nothing manually (the helper is app-scoped).
    private inline fun <T> SQLiteDatabase.useProfileRow(block: (SQLiteDatabase) -> T): T = block(this)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROFILE(
                $COL_ID INTEGER PRIMARY KEY DEFAULT 0,
                $COL_SEX TEXT NOT NULL,
                $COL_AGE INTEGER NOT NULL,
                $COL_HEIGHT REAL NOT NULL,
                $COL_WEIGHT REAL NOT NULL,
                $COL_BODY_FAT REAL,
                $COL_SCOLIOSIS INTEGER NOT NULL,
                $COL_RED_FLAGS TEXT NOT NULL,
                $COL_CREATED_ON TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_WORKOUT(
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION TEXT NOT NULL,
                $COL_EXERCISE TEXT NOT NULL,
                $COL_DONE_ON TEXT NOT NULL,
                UNIQUE($COL_SESSION, $COL_EXERCISE)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_workout_session ON $TABLE_WORKOUT($COL_SESSION)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_SYMPTOM(
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_RECORDED_ON TEXT NOT NULL,
                $COL_TEXT TEXT NOT NULL
            )
            """.trimIndent(),
        )
        // M5-A (DRE-61): additive table. New on a v2 install; onUpgrade adds it
        // for existing installs without touching prior rows.
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROGRESS(
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_RECORDED_ON TEXT NOT NULL,
                $COL_WEIGHT REAL NOT NULL
            )
            """.trimIndent(),
        )
        // M8-B (DRE-78): additive table. Per-(session, exercise) note field;
        // UNIQUE key ⇒ latest note wins (upsert via REPLACE, like workout_log).
        db.execSQL(
            """
            CREATE TABLE $TABLE_EXERCISE_NOTE(
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION TEXT NOT NULL,
                $COL_EXERCISE TEXT NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_OUTCOME TEXT,
                $COL_RECORDED_ON TEXT NOT NULL,
                UNIQUE($COL_SESSION, $COL_EXERCISE)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_exercise_note_session ON $TABLE_EXERCISE_NOTE($COL_SESSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // M5-A (DRE-61): v1 → v2 adds the progress table ADDITIVELY. No prior
        // table is touched, no row is dropped — health-signal data (symptom/workout
        // logs) is retained for audit/rollback, as in M3-B/M4-B. Future bumps keep
        // stacking additive `if (oldVersion < N)` branches here; never ALTER-down.
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PROGRESS(
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_RECORDED_ON TEXT NOT NULL,
                    $COL_WEIGHT REAL NOT NULL
                )
                """.trimIndent(),
            )
        }
        // M8-B (DRE-78): v2 → v3 adds the exercise-note table ADDITIVELY. No prior
        // table is touched, no row dropped — health-signal data (notes are
        // pain/performance self-report) is retained for audit/rollback, as in M5-A.
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EXERCISE_NOTE(
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SESSION TEXT NOT NULL,
                    $COL_EXERCISE TEXT NOT NULL,
                    $COL_TEXT TEXT NOT NULL,
                    $COL_RECORDED_ON TEXT NOT NULL,
                    UNIQUE($COL_SESSION, $COL_EXERCISE)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exercise_note_session ON $TABLE_EXERCISE_NOTE($COL_SESSION)")
        }
        // M9-A ([DRE-112](/DRE/issues/DRE-112)): v3 → v4 adds the structured outcome
        // column ADDITIVELY (nullable; existing notes keep NULL outcome). No prior
        // table touched, no row dropped — the flag is retained self-report data.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_EXERCISE_NOTE ADD COLUMN $COL_OUTCOME TEXT")
        }
    }

    companion object {
        private const val NAME = "dreamteam.db"
        private const val VERSION = 4 // M9-A (DRE-112): +exercise_note_log.outcome (additive v3→v4).
        private const val TABLE_PROFILE = "profile"
        private const val TABLE_WORKOUT = "workout_log"
        private const val TABLE_SYMPTOM = "symptom_log"
        private const val TABLE_PROGRESS = "progress_log" // M5-A (DRE-61)
        private const val TABLE_EXERCISE_NOTE = "exercise_note_log" // M8-B (DRE-78)
        private const val COL_ID = "id"
        private const val COL_SEX = "sex"
        private const val COL_AGE = "age"
        private const val COL_HEIGHT = "height_cm"
        private const val COL_WEIGHT = "weight_kg"
        private const val COL_BODY_FAT = "body_fat_percent"
        private const val COL_SCOLIOSIS = "scoliosis_reported"
        private const val COL_RED_FLAGS = "red_flags"
        private const val COL_CREATED_ON = "created_on"
        private const val COL_SESSION = "session_id"
        private const val COL_EXERCISE = "exercise_id"
        private const val COL_DONE_ON = "done_on"
        private const val COL_RECORDED_ON = "recorded_on"
        private const val COL_TEXT = "text"
        private const val COL_OUTCOME = "outcome" // M9-A (DRE-112): structured self-report flag (nullable).
    }
}

@Serializable
data class Profile(
    val sex: String,
    val age: Int,
    val height: Double,
    val weight: Double,
    val bodyFat: Double?,
    val scoliosisReported: Boolean,
    val redFlags: List<String>,
    val createdOn: String,
)

@Serializable
data class SymptomEntry(val recordedOn: String, val text: String)

/**
 * A locally-logged body-weight measurement (M5-A / [DRE-61](/DRE/issues/DRE-61)).
 * App-local row mirrored on [SymptomEntry]; bridged to the domain
 * [dreamteam.domain.progress.ProgressEntry] by [dreamteam.app.clientProgress]
 * (the progress analogue of [dreamteam.app.clientSymptoms]). Weight only — the
 * MVP field the RapidWeightLoss adaptation trigger needs; no body-fat/waist yet.
 */
@Serializable
data class ProgressRow(val recordedOn: String, val weightKg: Double)

/**
 * M7-A ([DRE-72](/DRE/issues/DRE-72)): one verbatim workout_log row for export —
 * (session_id, exercise_id, done_on). The raw completion record the user logged;
 * no interpretation added. [doneOn] is the user's done-date string verbatim.
 */
@Serializable
data class WorkoutCompletion(val sessionId: String, val exerciseId: String, val doneOn: String)

/**
 * M8-B ([DRE-78](/DRE/issues/DRE-78)): a free-text note the user attached to one
 * exercise in one session — "что вышло / что нет / боль". Verbatim self-report
 * (mirrors [SymptomEntry] / [ProgressRow]): no diagnosis, no interpretation. The
 * coach (M8-C) reads these as its per-exercise input; the latest note per
 * (session, exercise) wins ([LocalDatabase.appendExerciseNote] upserts via REPLACE).
 *
 * M9-A ([DRE-112](/DRE/issues/DRE-112)): now also carries a structured [outcome]
 * flag — a light self-report of how the exercise went. Recorded-only: it MUST NOT
 * drive any plan change in this slice (adaptation from notes is the gated M9-D).
 * Default null so a v2 export (pre-outcome) still decodes forwards-compatible.
 */
@Serializable
data class ExerciseNoteRow(
    val sessionId: String,
    val exerciseId: String,
    val note: String,
    @Serializable(with = ExerciseNoteOutcomeTokenSerializer::class)
    val outcome: ExerciseNoteOutcome? = null,
    val recordedOn: String,
)

/**
 * M9-A ([DRE-112](/DRE/issues/DRE-112)): the structured outcome flag a user can
 * attach to a per-exercise note — a light self-report of how it went
 * (`ok / hard / painful / skipped`). The user's own input, never an
 * interpretation or diagnosis. [PAINFUL] is **recorded, not acted on**: it is
 * surfaced + exported, and MUST NOT auto-suppress or alter any plan in this
 * slice. [storage] is the stable on-disk + export token; [labelRu] is the
 * app-authored chip label (scanned for banned phrases via [ExerciseNoteStrings]).
 */
@Serializable
enum class ExerciseNoteOutcome(val storage: String, val labelRu: String) {
    OK("ok", "Норм"),
    HARD("hard", "Тяжело"),
    PAINFUL("painful", "Боль"),
    SKIPPED("skipped", "Пропустил");

    companion object {
        /** Decode a stored/exported token; unknown or null → null (forwards-compatible). */
        fun fromStorage(s: String?): ExerciseNoteOutcome? =
            s?.let { v -> entries.firstOrNull { it.storage == v } }
    }
}

/**
 * M9-A follow-up ([DRE-115](/DRE/issues/DRE-115)): the export-JSON token for an
 * [ExerciseNoteOutcome] is its stable [ExerciseNoteOutcome.storage] string
 * (NOT the kotlinx enum `name`), and an unknown or absent token decodes to null
 * — the exact [ExerciseNoteOutcome.fromStorage] contract the SQLite path already
 * honours. Bound to [ExerciseNoteRow.outcome] so the two persistence layers
 * (SQLite + export JSON) agree and stay forwards-compatible across an enum
 * rename or a future-added outcome. Localized to this field: it does NOT relax
 * decode leniency for any other enum in the export document (a global
 * `coerceInputValues` would silently coerce unknown domain values elsewhere).
 *
 * ponytail: the descriptor is intentionally non-nullable, so kotlinx handles the
 * null mark for the nullable field — serialize/deserialize are invoked only for a
 * present value; deserialize returns null on an unknown token via fromStorage.
 */
object ExerciseNoteOutcomeTokenSerializer : KSerializer<ExerciseNoteOutcome?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExerciseNoteOutcome", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExerciseNoteOutcome?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.storage)
    }

    override fun deserialize(decoder: Decoder): ExerciseNoteOutcome? =
        ExerciseNoteOutcome.fromStorage(decoder.decodeString())
}
