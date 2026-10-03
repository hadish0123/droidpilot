package com.mobilemcp.pro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class PrimeTaskType {
    REMINDER,
    AI_BRIEF
}

internal enum class PrimeTaskScheduleKind {
    ONE_TIME,
    PERIODIC
}

internal data class PrimeTaskRecord(
    val id: String,
    val title: String,
    val prompt: String,
    val type: PrimeTaskType,
    val scheduleKind: PrimeTaskScheduleKind,
    val initialDelayMinutes: Long,
    val intervalMinutes: Long?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null
)

internal object PrimeTaskValidation {
    const val MIN_PERIODIC_MINUTES = 15L
    const val MAX_DELAY_MINUTES =
        365L * 24L * 60L
    const val MAX_INTERVAL_MINUTES =
        365L * 24L * 60L

    fun validate(record: PrimeTaskRecord) {
        require(
            record.title.isNotBlank() &&
                record.title.length <= 100
        ) {
            "Task title must be 1–100 characters"
        }
        require(
            record.prompt.isNotBlank() &&
                record.prompt.length <= 8_000
        ) {
            "Task prompt must be 1–8000 characters"
        }
        require(
            record.initialDelayMinutes in
                0..MAX_DELAY_MINUTES
        ) {
            "Initial delay is outside the supported range"
        }

        when (record.scheduleKind) {
            PrimeTaskScheduleKind.ONE_TIME ->
                require(
                    record.intervalMinutes == null
                ) {
                    "One-time task must not have an interval"
                }

            PrimeTaskScheduleKind.PERIODIC -> {
                val interval =
                    record.intervalMinutes
                        ?: throw IllegalArgumentException(
                            "Periodic task requires an interval"
                        )
                require(
                    interval in
                        MIN_PERIODIC_MINUTES..
                            MAX_INTERVAL_MINUTES
                ) {
                    "Recurring interval must be at least 15 minutes"
                }
            }
        }
    }
}

internal object PrimeTaskCodec {
    fun encode(
        records: List<PrimeTaskRecord>
    ): String {
        val array = JSONArray()
        records.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("prompt", task.prompt)
                    .put("type", task.type.name)
                    .put(
                        "scheduleKind",
                        task.scheduleKind.name
                    )
                    .put(
                        "initialDelayMinutes",
                        task.initialDelayMinutes
                    )
                    .put(
                        "intervalMinutes",
                        task.intervalMinutes
                            ?: JSONObject.NULL
                    )
                    .put("enabled", task.enabled)
                    .put("createdAt", task.createdAt)
                    .put("updatedAt", task.updatedAt)
                    .put(
                        "lastRunAt",
                        task.lastRunAt
                            ?: JSONObject.NULL
                    )
                    .put(
                        "lastStatus",
                        task.lastStatus
                            ?: JSONObject.NULL
                    )
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String?
    ): List<PrimeTaskRecord> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (
                    index in 0
                        until array.length()
                ) {
                    val json =
                        array.optJSONObject(
                            index
                        ) ?: continue
                    val id =
                        json.optString("id")
                            .trim()
                    val title =
                        json.optString("title")
                            .trim()
                    val prompt =
                        json.optString("prompt")
                            .trim()
                    if (
                        id.isBlank() ||
                        title.isBlank() ||
                        prompt.isBlank()
                    ) {
                        continue
                    }

                    val type =
                        runCatching {
                            PrimeTaskType
                                .valueOf(
                                    json.optString(
                                        "type",
                                        PrimeTaskType
                                            .REMINDER
                                            .name
                                    )
                                )
                        }.getOrDefault(
                            PrimeTaskType.REMINDER
                        )
                    val schedule =
                        runCatching {
                            PrimeTaskScheduleKind
                                .valueOf(
                                    json.optString(
                                        "scheduleKind",
                                        PrimeTaskScheduleKind
                                            .ONE_TIME
                                            .name
                                    )
                                )
                        }.getOrDefault(
                            PrimeTaskScheduleKind
                                .ONE_TIME
                        )

                    val record =
                        PrimeTaskRecord(
                            id = id,
                            title = title,
                            prompt = prompt,
                            type = type,
                            scheduleKind =
                                schedule,
                            initialDelayMinutes =
                                json.optLong(
                                    "initialDelayMinutes",
                                    0
                                ),
                            intervalMinutes =
                                if (
                                    json.isNull(
                                        "intervalMinutes"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optLong(
                                        "intervalMinutes"
                                    )
                                },
                            enabled =
                                json.optBoolean(
                                    "enabled",
                                    true
                                ),
                            createdAt =
                                json.optLong(
                                    "createdAt"
                                ),
                            updatedAt =
                                json.optLong(
                                    "updatedAt"
                                ),
                            lastRunAt =
                                if (
                                    json.isNull(
                                        "lastRunAt"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optLong(
                                        "lastRunAt"
                                    )
                                },
                            lastStatus =
                                if (
                                    json.isNull(
                                        "lastStatus"
                                    )
                                ) {
                                    null
                                } else {
                                    json.optString(
                                        "lastStatus"
                                    )
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                }
                        )

                    runCatching {
                        PrimeTaskValidation
                            .validate(record)
                    }.onSuccess {
                        add(record)
                    }
                }
            }
        }.getOrDefault(
            emptyList()
        )
    }
}

internal class PrimeTaskStore(
    context: Context,
    private val secureStore:
        SecureStore =
        SecureStore(
            context.applicationContext
        )
) {
    companion object {
        private const val STORE_KEY =
            "prime_tasks_v1"
        private const val MAX_TASKS = 100
    }

    @Synchronized
    fun list(): List<PrimeTaskRecord> =
        readAll()
            .sortedByDescending {
                it.updatedAt
            }

    @Synchronized
    fun get(id: String):
        PrimeTaskRecord? =
        readAll().firstOrNull {
            it.id == id
        }

    @Synchronized
    fun create(
        title: String,
        prompt: String,
        type: PrimeTaskType,
        scheduleKind:
            PrimeTaskScheduleKind,
        initialDelayMinutes: Long,
        intervalMinutes: Long?
    ): PrimeTaskRecord {
        val records =
            readAll().toMutableList()
        require(
            records.size < MAX_TASKS
        ) {
            "Maximum task count reached"
        }

        val now =
            System.currentTimeMillis()
        val record =
            PrimeTaskRecord(
                id = UUID.randomUUID()
                    .toString(),
                title = title
                    .trim()
                    .take(100),
                prompt = prompt
                    .trim()
                    .take(8_000),
                type = type,
                scheduleKind =
                    scheduleKind,
                initialDelayMinutes =
                    initialDelayMinutes,
                intervalMinutes =
                    intervalMinutes,
                enabled = true,
                createdAt = now,
                updatedAt = now
            )
        PrimeTaskValidation
            .validate(record)
        records += record
        writeAll(records)
        return record
    }

    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean
    ): PrimeTaskRecord? {
        val records =
            readAll().toMutableList()
        val index =
            records.indexOfFirst {
                it.id == id
            }
        if (index < 0) return null

        val updated =
            records[index].copy(
                enabled = enabled,
                updatedAt =
                    System.currentTimeMillis()
            )
        records[index] = updated
        writeAll(records)
        return updated
    }

    @Synchronized
    fun recordRun(
        id: String,
        status: String,
        completedOneTime: Boolean
    ): PrimeTaskRecord? {
        val records =
            readAll().toMutableList()
        val index =
            records.indexOfFirst {
                it.id == id
            }
        if (index < 0) return null

        val now =
            System.currentTimeMillis()
        val current =
            records[index]
        val updated =
            current.copy(
                enabled =
                    if (
                        completedOneTime
                    ) {
                        false
                    } else {
                        current.enabled
                    },
                updatedAt = now,
                lastRunAt = now,
                lastStatus =
                    status.take(300)
            )
        records[index] = updated
        writeAll(records)
        return updated
    }

    @Synchronized
    fun remove(
        id: String
    ): PrimeTaskRecord? {
        val records =
            readAll().toMutableList()
        val index =
            records.indexOfFirst {
                it.id == id
            }
        if (index < 0) return null

        val removed =
            records.removeAt(index)
        writeAll(records)
        return removed
    }

    private fun readAll():
        List<PrimeTaskRecord> =
        PrimeTaskCodec.decode(
            secureStore.getString(
                STORE_KEY
            )
        )

    private fun writeAll(
        records:
            List<PrimeTaskRecord>
    ) {
        secureStore.putString(
            STORE_KEY,
            PrimeTaskCodec
                .encode(records)
        )
    }
}

internal class PrimeTaskScheduler(
    context: Context
) {
    private val appContext =
        context.applicationContext
    private val workManager =
        WorkManager.getInstance(
            appContext
        )

    fun schedule(
        task: PrimeTaskRecord
    ) {
        PrimeTaskValidation
            .validate(task)
        cancel(task.id)

        if (!task.enabled) return

        val input = Data.Builder()
            .putString(
                PrimeTaskWorker.KEY_TASK_ID,
                task.id
            )
            .build()

        val constraints =
            if (
                task.type ==
                    PrimeTaskType.AI_BRIEF
            ) {
                Constraints.Builder()
                    .setRequiredNetworkType(
                        NetworkType.CONNECTED
                    )
                    .build()
            } else {
                Constraints.Builder()
                    .build()
            }

        when (task.scheduleKind) {
            PrimeTaskScheduleKind.ONE_TIME -> {
                val request =
                    OneTimeWorkRequestBuilder<
                        PrimeTaskWorker
                    >()
                        .setInputData(input)
                        .setConstraints(
                            constraints
                        )
                        .setInitialDelay(
                            task.initialDelayMinutes,
                            TimeUnit.MINUTES
                        )
                        .addTag(
                            tag(task.id)
                        )
                        .build()

                workManager
                    .enqueueUniqueWork(
                        unique(task.id),
                        ExistingWorkPolicy
                            .REPLACE,
                        request
                    )
            }

            PrimeTaskScheduleKind.PERIODIC -> {
                val interval =
                    task.intervalMinutes
                        ?: throw IllegalArgumentException(
                            "Periodic task has no interval"
                        )
                val request =
                    PeriodicWorkRequestBuilder<
                        PrimeTaskWorker
                    >(
                        interval,
                        TimeUnit.MINUTES
                    )
                        .setInputData(input)
                        .setConstraints(
                            constraints
                        )
                        .setInitialDelay(
                            task.initialDelayMinutes,
                            TimeUnit.MINUTES
                        )
                        .addTag(
                            tag(task.id)
                        )
                        .build()

                workManager
                    .enqueueUniquePeriodicWork(
                        unique(task.id),
                        ExistingPeriodicWorkPolicy
                            .UPDATE,
                        request
                    )
            }
        }
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(
            unique(id)
        )
    }

    companion object {
        internal fun unique(
            id: String
        ) = "prime-task-" + id

        internal fun tag(
            id: String
        ) = "prime-task-tag-" + id
    }
}

class PrimeTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(
    appContext,
    params
) {
    companion object {
        const val KEY_TASK_ID =
            "prime_task_id"
        private const val CHANNEL_ID =
            "prime_tasks"
        private const val CHANNEL_NAME =
            "PRIME Tasks"
    }

    override suspend fun doWork():
        Result {
        val id =
            inputData.getString(
                KEY_TASK_ID
            )
            ?: return Result.failure()

        val store =
            PrimeTaskStore(
                applicationContext
            )
        val task =
            store.get(id)
                ?: return Result.success()

        if (!task.enabled) {
            return Result.success()
        }

        val oneTime =
            task.scheduleKind ==
                PrimeTaskScheduleKind
                    .ONE_TIME

        return try {
            val text =
                when (task.type) {
                    PrimeTaskType.REMINDER ->
                        task.prompt

                    PrimeTaskType.AI_BRIEF ->
                        generateAiBrief(
                            task
                        )
                }

            notifyTask(
                task,
                text
            )
            store.recordRun(
                id = task.id,
                status = "success",
                completedOneTime =
                    oneTime
            )
            Result.success()
        } catch (
            e: Exception
        ) {
            val status =
                (
                    e.message
                        ?: e.javaClass
                            .simpleName
                    ).take(280)

            store.recordRun(
                id = task.id,
                status =
                    "error: " +
                        status,
                completedOneTime =
                    false
            )

            if (
                runAttemptCount < 2
            ) {
                Result.retry()
            } else {
                notifyTask(
                    task,
                    "Task failed: " +
                        status
                )
                if (oneTime) {
                    store.recordRun(
                        id = task.id,
                        status =
                            "failed",
                        completedOneTime =
                            true
                    )
                }
                Result.failure()
            }
        }
    }

    private suspend fun generateAiBrief(
        task: PrimeTaskRecord
    ): String {
        val auth =
            OpenAIAuthManager(
                applicationContext
            )
        if (!auth.isSignedIn()) {
            throw IllegalStateException(
                "ChatGPT account is not connected"
            )
        }

        val provider =
            PrimeObservedProvider(
                OpenAIResponsesProvider(
                    object :
                        PrimeCredentials {
                        override fun isSignedIn() =
                            auth.isSignedIn()

                        override suspend fun accessToken() =
                            auth.accessToken()
                    }
                ),
                PrimeObservabilityStore(
                    applicationContext
                )
            )
        val models =
            provider.listModels()
        val model =
            models.firstOrNull {
                it.id.contains(
                    "luna",
                    true
                ) ||
                    it.displayName
                        .contains(
                            "luna",
                            true
                        )
            }
                ?: models.firstOrNull()
                ?: throw IllegalStateException(
                    "No model is available"
                )

        return provider.streamText(
            AiTextRequest(
                model = model.id,
                instructions =
                    "You are PRIME P6 running a user-created background task. " +
                        "Reply in the user's language, be concise, and do not claim phone actions were performed.",
                messages =
                    listOf(
                        AiMessage(
                            "user",
                            task.prompt
                        )
                    )
            )
        ).trim().ifBlank {
            "Task completed with no text output."
        }
    }

    private fun notifyTask(
        task: PrimeTaskRecord,
        text: String
    ) {
        val manager =
            applicationContext
                .getSystemService(
                    NotificationManager::class.java
                )

        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager
                        .IMPORTANCE_DEFAULT
                )
            )
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission
                    .POST_NOTIFICATIONS
            ) != PackageManager
                .PERMISSION_GRANTED
        ) {
            return
        }

        val intent =
            Intent(
                applicationContext,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pending =
            PendingIntent.getActivity(
                applicationContext,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val clean =
            text.trim()
                .take(4_000)
        val notification =
            NotificationCompat
                .Builder(
                    applicationContext,
                    CHANNEL_ID
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_popup_reminder
                )
                .setContentTitle(
                    task.title
                )
                .setContentText(
                    clean.take(180)
                )
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(clean)
                )
                .setContentIntent(
                    pending
                )
                .setAutoCancel(true)
                .build()

        runCatching {
            manager.notify(
                task.id.hashCode(),
                notification
            )
        }
    }
}
