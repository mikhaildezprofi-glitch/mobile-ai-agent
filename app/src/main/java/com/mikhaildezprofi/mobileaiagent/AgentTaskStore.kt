package com.mikhaildezprofi.mobileaiagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

enum class AgentPlanStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }
enum class AgentStepStatus { PENDING, RUNNING, COMPLETED, FAILED }

data class SavedAgentStep(
    val step: AgentTaskStep,
    val status: AgentStepStatus,
    val result: String = "",
    val error: String? = null
)

data class SavedAgentPlan(
    val plan: AgentTaskPlan,
    val status: AgentPlanStatus,
    val steps: List<SavedAgentStep>,
    val updatedAt: Long
) {
    val canResume: Boolean
        get() = status in setOf(AgentPlanStatus.QUEUED, AgentPlanStatus.RUNNING, AgentPlanStatus.PAUSED, AgentPlanStatus.FAILED) &&
            steps.any { it.status != AgentStepStatus.COMPLETED }
}

interface AgentTaskPersistence {
    fun savePlan(plan: AgentTaskPlan)
    fun setPlanStatus(planId: String, status: AgentPlanStatus)
    fun setStepStatus(planId: String, stepId: String, status: AgentStepStatus, error: String? = null)
    fun completeStep(planId: String, stepId: String, result: String)
    fun loadPlan(planId: String): SavedAgentPlan?
    fun listResumable(limit: Int = 10): List<SavedAgentPlan>
}

/**
 * Small on-device SQLite journal. It stores only task text/results, never API keys or attachments.
 * All write operations are transactional and synchronous; callers should use Dispatchers.IO.
 */
class AgentTaskStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
), AgentTaskPersistence {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE plans (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                original_prompt TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE steps (
                plan_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                title TEXT NOT NULL,
                prompt TEXT NOT NULL,
                agent_role TEXT NOT NULL,
                depends_on TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT NOT NULL DEFAULT '',
                error TEXT,
                PRIMARY KEY(plan_id, step_id),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_plans_updated ON plans(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first persistent task journal. Future migrations must be additive.
    }

    override fun savePlan(plan: AgentTaskPlan) {
        plan.validate()
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT id FROM plans WHERE id = ?", arrayOf(plan.id))
            val exists = existing.use { it.moveToFirst() }
            val values = ContentValues().apply {
                put("id", plan.id)
                put("title", plan.title)
                put("original_prompt", plan.originalPrompt)
                put("status", if (exists) AgentPlanStatus.RUNNING.name else AgentPlanStatus.QUEUED.name)
                put("updated_at", now)
                if (!exists) put("created_at", now)
            }
            if (exists) {
                db.update("plans", values, "id = ?", arrayOf(plan.id))
            } else {
                db.insertOrThrow("plans", null, values)
                plan.steps.forEachIndexed { index, step ->
                    val stepValues = ContentValues().apply {
                        put("plan_id", plan.id)
                        put("step_id", step.id)
                        put("ordinal", index)
                        put("title", step.title)
                        put("prompt", step.prompt)
                        put("agent_role", step.agentRole)
                        put("depends_on", JSONArray(step.dependsOn).toString())
                        put("status", AgentStepStatus.PENDING.name)
                        put("result", "")
                    }
                    db.insertOrThrow("steps", null, stepValues)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun setPlanStatus(planId: String, status: AgentPlanStatus) {
        writableDatabase.update(
            "plans",
            ContentValues().apply {
                put("status", status.name)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(planId)
        )
    }

    override fun setStepStatus(planId: String, stepId: String, status: AgentStepStatus, error: String?) {
        val values = ContentValues().apply {
            put("status", status.name)
            put("error", error)
        }
        writableDatabase.update("steps", values, "plan_id = ? AND step_id = ?", arrayOf(planId, stepId))
        touchPlan(planId)
    }

    override fun completeStep(planId: String, stepId: String, result: String) {
        val values = ContentValues().apply {
            put("status", AgentStepStatus.COMPLETED.name)
            put("result", result)
            putNull("error")
        }
        writableDatabase.update("steps", values, "plan_id = ? AND step_id = ?", arrayOf(planId, stepId))
        touchPlan(planId)
    }

    override fun loadPlan(planId: String): SavedAgentPlan? = readPlan(
        readableDatabase.rawQuery("SELECT * FROM plans WHERE id = ?", arrayOf(planId))
    )

    /** Returns recent plans, including completed ones, for the task history screen. */
    fun listHistory(limit: Int = 30): List<SavedAgentPlan> {
        val cursor = readableDatabase.rawQuery(
            "SELECT id FROM plans ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        )
        val ids = cursor.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return ids.mapNotNull(::readPlanById)
    }

    /** Deletes a plan and its steps in one transaction; no FK pragma assumptions. */
    fun deletePlan(planId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("steps", "plan_id = ?", arrayOf(planId))
            val deleted = db.delete("plans", "id = ?", arrayOf(planId)) > 0
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    override fun listResumable(limit: Int): List<SavedAgentPlan> {
        val cursor = readableDatabase.rawQuery(
            "SELECT id FROM plans WHERE status IN (?, ?, ?, ?) ORDER BY updated_at DESC LIMIT ?",
            arrayOf(
                AgentPlanStatus.QUEUED.name, AgentPlanStatus.RUNNING.name,
                AgentPlanStatus.PAUSED.name, AgentPlanStatus.FAILED.name,
                limit.coerceIn(1, 50).toString()
            )
        )
        val ids = cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0))
            }
        }
        return ids.mapNotNull(::readPlanById)
    }

    private fun readPlan(cursor: android.database.Cursor): SavedAgentPlan? = cursor.use { c ->
        if (!c.moveToFirst()) return null
        val id = c.getString(c.getColumnIndexOrThrow("id"))
        val plan = AgentTaskPlan(
            id = id,
            title = c.getString(c.getColumnIndexOrThrow("title")),
            originalPrompt = c.getString(c.getColumnIndexOrThrow("original_prompt")),
            steps = readSteps(id)
        )
        val status = AgentPlanStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")))
        val updated = c.getLong(c.getColumnIndexOrThrow("updated_at"))
        SavedAgentPlan(plan, status, readSavedSteps(id, plan.steps), updated)
    }

    private fun readPlanById(planId: String): SavedAgentPlan? {
        return readPlan(readableDatabase.rawQuery("SELECT * FROM plans WHERE id = ?", arrayOf(planId)))
    }

    private fun readSteps(planId: String): List<AgentTaskStep> {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM steps WHERE plan_id = ? ORDER BY ordinal", arrayOf(planId)
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val dependencies = JSONArray(c.getString(c.getColumnIndexOrThrow("depends_on")))
                    add(AgentTaskStep(
                        id = c.getString(c.getColumnIndexOrThrow("step_id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        prompt = c.getString(c.getColumnIndexOrThrow("prompt")),
                        dependsOn = List(dependencies.length()) { dependencies.getString(it) },
                        agentRole = c.getString(c.getColumnIndexOrThrow("agent_role"))
                    ))
                }
            }
        }
    }

    private fun readSavedSteps(planId: String, steps: List<AgentTaskStep>): List<SavedAgentStep> {
        val cursor = readableDatabase.rawQuery(
            "SELECT step_id, status, result, error FROM steps WHERE plan_id = ? ORDER BY ordinal",
            arrayOf(planId)
        )
        val states = cursor.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), Triple(c.getString(1), c.getString(2), c.getString(3)))
                }
            }
        }
        return steps.map { step ->
            val state = states[step.id]
            SavedAgentStep(
                step = step,
                status = state?.first?.let(AgentStepStatus::valueOf) ?: AgentStepStatus.PENDING,
                result = state?.second.orEmpty(),
                error = state?.third
            )
        }
    }

    private fun touchPlan(planId: String) {
        writableDatabase.update(
            "plans", ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?", arrayOf(planId)
        )
    }

    companion object {
        private const val DB_NAME = "agent_tasks.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: AgentTaskStore? = null

        fun get(context: Context): AgentTaskStore =
            instance ?: synchronized(this) {
                instance ?: AgentTaskStore(context.applicationContext).also { instance = it }
            }
    }
}
