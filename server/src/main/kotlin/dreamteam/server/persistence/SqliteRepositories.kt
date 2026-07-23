package dreamteam.server.persistence

import dreamteam.domain.EvidenceId
import dreamteam.domain.ExerciseId
import dreamteam.domain.NutritionPlanId
import dreamteam.domain.PlanId
import dreamteam.domain.UserId
import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionPlanRepository
import dreamteam.domain.persistence.NutritionRepository
import dreamteam.domain.persistence.ProgressRepository
import dreamteam.domain.persistence.SafetyRuleRepository
import dreamteam.domain.persistence.SymptomRepository
import dreamteam.domain.persistence.TrainingPlanRepository
import dreamteam.domain.persistence.UserRepository
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.User
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Durable, encrypted-at-rest SQLite implementation of the `Repositories.kt`
 * ports (ADR 0003 / DRE-16). Replaces the in-memory repos for the persistent
 * path: data lives in a single SQLite file whose health-signal columns are
 * AES-GCM ciphertext ([PayloadCipher]); the DB file is useless without the
 * injected [EncryptionKey].
 *
 * Storage model — a versioned document store: each aggregate is one JSON
 * document (the domain types are all `@Serializable`) stored as an encrypted
 * BLOB in its own table. Only the non-sensitive query keys (`id`, `user_id`)
 * are plaintext, which is exactly what makes "find by user / by id" cheap
 * without decrypting the whole table. The encrypted boundary is the payload;
 * see ADR 0003 §Decision for the threat model.
 *
 * Reversible: because feature code depends only on the ports, swapping this for
 * a different store (Postgres, on-device Room/SQLCipher) is contained behind
 * the same interfaces — the [dreamteam.server.persistence.RepositoryLayerTest]
 * contract proves both in-memory and SQLite satisfy the same round-trip +
 * allowlist behaviour.
 */

internal val repoJson = Json { ignoreUnknownKeys = true }

internal inline fun <reified T> encodeJson(value: T): String = repoJson.encodeToString(value)
internal inline fun <reified T> decodeJson(text: String): T = repoJson.decodeFromString(text)

/**
 * One encrypted SQLite connection shared by all repos. SQLite is single-writer,
 * so access is serialized with a monitor — boring and correct for this tier.
 *
 * ponytail: single connection + synchronized. Add a real pool (HikariCP) only
 * if backend write throughput ever becomes a measured bottleneck; SQLite would
 * not be the right store at that point regardless (see ADR 0003).
 */
internal class SqliteStore(jdbcUrl: String, private val key: EncryptionKey) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection(jdbcUrl)

    init {
        synchronized(conn) {
            createStatement().use { s ->
                SCHEMA.forEach { s.execute(it) }
            }
            // WAL: readers don't block the writer, durable across process restart.
            createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            // ponytail: single-writer ceiling noted above; busy_timeout rides out lock contention.
            createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
        }
    }

    fun encrypt(text: String): ByteArray = PayloadCipher.encrypt(text.toByteArray(UTF_8), key.bytes())
    fun decrypt(blob: ByteArray): String = String(PayloadCipher.decrypt(blob, key.bytes()), UTF_8)

    fun update(sql: String, vararg params: Any?): Unit = synchronized(conn) {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeUpdate()
        }
    }

    fun <T> queryOne(sql: String, mapper: (ResultSet) -> T, vararg params: Any?): T? = synchronized(conn) {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
        }
    }

    fun <T> queryList(sql: String, mapper: (ResultSet) -> T, vararg params: Any?): List<T> = synchronized(conn) {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(mapper(rs))
                out
            }
        }
    }

    override fun close(): Unit = synchronized(conn) { conn.close() }

    private fun createStatement() = conn.createStatement()

    companion object {
        // One-time, idempotent schema. user_version pins v1 (Schema.CURRENT) for
        // future on-read migrations; the document model means a schema bump is
        // usually just a new table or a transform over encrypted payloads.
        private val SCHEMA = listOf(
            "PRAGMA user_version = 1",
            "CREATE TABLE IF NOT EXISTS evidence_source (id TEXT PRIMARY KEY, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS exercise (id TEXT PRIMARY KEY, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS safety_rule (id TEXT PRIMARY KEY, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS user_account (id TEXT PRIMARY KEY, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS training_plan (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS current_plan (user_id TEXT PRIMARY KEY, plan_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS progress_entry (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, seq INTEGER NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS symptom (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, seq INTEGER NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS nutrition_target (user_id TEXT PRIMARY KEY, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS nutrition_plan (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS current_nutrition_plan (user_id TEXT PRIMARY KEY, plan_id TEXT NOT NULL)",
        )
    }
}

/**
 * Query helper for the simple "primary-keyed encrypted payload" tables
 * (evidence, exercise, safety_rule, user, nutrition). [table]/[idColumn] are
 * internal compile-time identifiers only — guarded so no caller can ever
 * interpolate user input there.
 */
internal class KeyedPayloadTable(
    private val store: SqliteStore,
    private val table: String,
    private val idColumn: String,
) {
    init {
        require(table.matches(IDENT)) { "internal: bad table identifier '$table'" }
        require(idColumn.matches(IDENT)) { "internal: bad id identifier '$idColumn'" }
    }

    fun put(id: String, json: String) = store.update(
        "INSERT INTO $table ($idColumn, payload) VALUES (?, ?) " +
            "ON CONFLICT($idColumn) DO UPDATE SET payload = excluded.payload",
        id, store.encrypt(json),
    )

    fun get(id: String): String? =
        store.queryOne("SELECT payload FROM $table WHERE $idColumn = ?", { it.getBytes(1) }, id)?.let(store::decrypt)

    fun all(): List<String> =
        store.queryList("SELECT payload FROM $table ORDER BY $idColumn", { it.getBytes(1) }).map(store::decrypt)

    fun exists(id: String): Boolean =
        store.queryOne("SELECT EXISTS(SELECT 1 FROM $table WHERE $idColumn = ?)", { it.getBoolean(1) }, id) == true

    private companion object { val IDENT = Regex("^[a-z_][a-z0-9_]*$") }
}

/**
 * Query helper for user-scoped, append-ordered encrypted tables (progress,
 * symptom): `recentFor(user, n)` returns the newest N in chronological order,
 * matching the in-memory `takeLast` contract.
 */
internal class UserAppendPayloadTable(
    private val store: SqliteStore,
    private val table: String,
) {
    init { require(table.matches(IDENT)) { "internal: bad table identifier '$table'" } }

    fun append(id: String, userId: String, json: String) = store.update(
        "INSERT INTO $table (id, user_id, seq, payload) VALUES (?, ?, " +
            "(SELECT COALESCE(MAX(seq), 0) + 1 FROM $table), ?)",
        id, userId, store.encrypt(json),
    )

    fun recentFor(userId: String, limit: Int): List<String> =
        store.queryList(
            "SELECT payload FROM $table WHERE user_id = ? ORDER BY seq DESC LIMIT ?",
            { it.getBytes(1) }, userId, limit,
        ).map(store::decrypt).reversed()

    fun byId(id: String): String? =
        store.queryOne("SELECT payload FROM $table WHERE id = ?", { it.getBytes(1) }, id)?.let(store::decrypt)

    private companion object { val IDENT = Regex("^[a-z_][a-z0-9_]*$") }
}

internal class SqliteEvidenceSourceRepository(store: SqliteStore) : EvidenceSourceRepository {
    private val table = KeyedPayloadTable(store, "evidence_source", "id")
    override fun all(): List<EvidenceSource> = table.all().map { decodeJson<EvidenceSource>(it) }
    override fun byId(id: EvidenceId): EvidenceSource? = table.get(id)?.let { decodeJson<EvidenceSource>(it) }
    override fun contains(id: EvidenceId): Boolean = table.exists(id)
    override fun save(source: EvidenceSource) = table.put(source.id, encodeJson(source))
}

internal class SqliteExerciseRepository(store: SqliteStore) : ExerciseRepository {
    private val table = KeyedPayloadTable(store, "exercise", "id")
    override fun all(): List<Exercise> = table.all().map { decodeJson<Exercise>(it) }
    override fun byId(id: ExerciseId): Exercise? = table.get(id)?.let { decodeJson<Exercise>(it) }
    override fun contains(id: ExerciseId): Boolean = table.exists(id)
    override fun save(exercise: Exercise) = table.put(exercise.id, encodeJson(exercise))
}

internal class SqliteSafetyRuleRepository(store: SqliteStore) : SafetyRuleRepository {
    private val table = KeyedPayloadTable(store, "safety_rule", "id")
    override fun all(): List<SafetyRule> = table.all().map { decodeJson<SafetyRule>(it) }
    override fun byId(id: String): SafetyRule? = table.get(id)?.let { decodeJson<SafetyRule>(it) }
    override fun save(rule: SafetyRule) = table.put(rule.id, encodeJson(rule))
}

internal class SqliteUserRepository(store: SqliteStore) : UserRepository {
    private val table = KeyedPayloadTable(store, "user_account", "id")
    override fun byId(id: UserId): User? = table.get(id)?.let { decodeJson<User>(it) }
    override fun save(user: User) = table.put(user.id, encodeJson(user))
}

internal class SqliteTrainingPlanRepository(private val store: SqliteStore) : TrainingPlanRepository {
    override fun currentFor(userId: UserId): TrainingPlan? =
        store.queryOne(
            "SELECT p.payload FROM current_plan c JOIN training_plan p ON p.id = c.plan_id WHERE c.user_id = ?",
            { it.getBytes(1) }, userId,
        )?.let(store::decrypt)?.let { decodeJson<TrainingPlan>(it) }

    override fun byId(id: PlanId): TrainingPlan? =
        store.queryOne("SELECT payload FROM training_plan WHERE id = ?", { it.getBytes(1) }, id)
            ?.let(store::decrypt)?.let { decodeJson<TrainingPlan>(it) }

    /**
     * All retained versions for a user, oldest-first by `createdAt`. Selects by
     * the plaintext `user_id` key, then decodes + orders by the (in-payload)
     * `createdAt` field — matching the in-memory impl's contract. Per-user
     * history is tiny (one row per weekly recalc), so decoding each payload is
     * cheap; no schema column needed for ordering (ADR 0003 document model).
     */
    override fun historyFor(userId: UserId): List<TrainingPlan> =
        store.queryList(
            "SELECT payload FROM training_plan WHERE user_id = ?",
            { it.getBytes(1) }, userId,
        ).map(store::decrypt).map { decodeJson<TrainingPlan>(it) }
            .sortedBy { it.createdAt }

    override fun save(plan: TrainingPlan) {
        // Mirror the in-memory semantics exactly: store by id (history kept),
        // and bump the per-user "current" pointer so last save wins as current.
        store.update(
            "INSERT INTO training_plan (id, user_id, payload) VALUES (?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET user_id = excluded.user_id, payload = excluded.payload",
            plan.id, plan.userId, store.encrypt(encodeJson(plan)),
        )
        store.update(
            "INSERT INTO current_plan (user_id, plan_id) VALUES (?, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET plan_id = excluded.plan_id",
            plan.userId, plan.id,
        )
    }
}

internal class SqliteProgressRepository(store: SqliteStore) : ProgressRepository {
    private val table = UserAppendPayloadTable(store, "progress_entry")
    override fun recentFor(userId: UserId, limit: Int): List<ProgressEntry> =
        table.recentFor(userId, limit).map { decodeJson<ProgressEntry>(it) }
    override fun append(entry: ProgressEntry) = table.append(entry.id, entry.userId, encodeJson(entry))
}

internal class SqliteSymptomRepository(store: SqliteStore) : SymptomRepository {
    private val table = UserAppendPayloadTable(store, "symptom")
    override fun recentFor(userId: UserId, limit: Int): List<Symptom> =
        table.recentFor(userId, limit).map { decodeJson<Symptom>(it) }
    override fun byId(id: String): Symptom? = table.byId(id)?.let { decodeJson<Symptom>(it) }
    override fun append(entry: Symptom) = table.append(entry.id, entry.userId, encodeJson(entry))
}

internal class SqliteNutritionRepository(store: SqliteStore) : NutritionRepository {
    private val table = KeyedPayloadTable(store, "nutrition_target", "user_id")
    override fun currentFor(userId: UserId): NutritionTarget? =
        table.get(userId)?.let { decodeJson<NutritionTarget>(it) }
    override fun save(target: NutritionTarget) = table.put(target.userId, encodeJson(target))
}

internal class SqliteNutritionPlanRepository(private val store: SqliteStore) : NutritionPlanRepository {
    override fun currentFor(userId: UserId): NutritionPlan? =
        store.queryOne(
            "SELECT p.payload FROM current_nutrition_plan c JOIN nutrition_plan p ON p.id = c.plan_id WHERE c.user_id = ?",
            { it.getBytes(1) }, userId,
        )?.let(store::decrypt)?.let { decodeJson<NutritionPlan>(it) }

    override fun byId(id: NutritionPlanId): NutritionPlan? =
        store.queryOne("SELECT payload FROM nutrition_plan WHERE id = ?", { it.getBytes(1) }, id)
            ?.let(store::decrypt)?.let { decodeJson<NutritionPlan>(it) }

    /**
     * All retained nutrition-plan versions for a user, oldest-first by
     * `createdAt` — mirrors [SqliteTrainingPlanRepository.historyFor]. Per-user
     * history is tiny (one row per recalc), so decoding each payload to order by
     * the in-payload `createdAt` is cheap; no schema column needed (ADR 0003).
     */
    override fun historyFor(userId: UserId): List<NutritionPlan> =
        store.queryList(
            "SELECT payload FROM nutrition_plan WHERE user_id = ?",
            { it.getBytes(1) }, userId,
        ).map(store::decrypt).map { decodeJson<NutritionPlan>(it) }
            .sortedBy { it.createdAt }

    override fun save(plan: NutritionPlan) {
        // Mirror the training-half semantics: store by id (history kept), bump
        // the per-user "current" pointer so last save wins as current.
        store.update(
            "INSERT INTO nutrition_plan (id, user_id, payload) VALUES (?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET user_id = excluded.user_id, payload = excluded.payload",
            plan.id, plan.userId, store.encrypt(encodeJson(plan)),
        )
        store.update(
            "INSERT INTO current_nutrition_plan (user_id, plan_id) VALUES (?, ?) " +
                "ON CONFLICT(user_id) DO UPDATE SET plan_id = excluded.plan_id",
            plan.userId, plan.id,
        )
    }
}

/**
 * The durable bundle: one encrypted SQLite file, nine repos behind the ports.
 * Construct via [open]; close to release the connection (the file survives for
 * the next open — that is the whole point of "not in-memory").
 */
class SqliteRepositories internal constructor(
    private val store: SqliteStore,
    val evidence: EvidenceSourceRepository,
    val exercises: ExerciseRepository,
    val safetyRules: SafetyRuleRepository,
    val users: UserRepository,
    val plans: TrainingPlanRepository,
    val progress: ProgressRepository,
    val symptoms: SymptomRepository,
    val nutrition: NutritionRepository,
    val nutritionPlans: NutritionPlanRepository,
) : AutoCloseable {
    override fun close() = store.close()

    companion object {
        /**
         * @param jdbcUrl a SQLite JDBC url, e.g. `jdbc:sqlite:./data/dreamteam.db`.
         * @param key the injected AES-256 key (ADR 0003). Never derived in code.
         */
        fun open(jdbcUrl: String, key: EncryptionKey): SqliteRepositories {
            val store = SqliteStore(jdbcUrl, key)
            return SqliteRepositories(
                store,
                SqliteEvidenceSourceRepository(store),
                SqliteExerciseRepository(store),
                SqliteSafetyRuleRepository(store),
                SqliteUserRepository(store),
                SqliteTrainingPlanRepository(store),
                SqliteProgressRepository(store),
                SqliteSymptomRepository(store),
                SqliteNutritionRepository(store),
                SqliteNutritionPlanRepository(store),
            )
        }
    }
}
