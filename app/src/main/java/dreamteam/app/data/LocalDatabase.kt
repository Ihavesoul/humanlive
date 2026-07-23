package dreamteam.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable

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
    }

    companion object {
        private const val NAME = "dreamteam.db"
        private const val VERSION = 2 // M5-A (DRE-61): +progress_log (additive v1→v2).
        private const val TABLE_PROFILE = "profile"
        private const val TABLE_WORKOUT = "workout_log"
        private const val TABLE_SYMPTOM = "symptom_log"
        private const val TABLE_PROGRESS = "progress_log" // M5-A (DRE-61)
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
