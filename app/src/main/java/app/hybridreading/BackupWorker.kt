package app.hybridreading

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Недельный бэкап профиля в «Загрузки/HybridReading».
 *
 * Страховка от потери данных (переустановка, очистка данных системы): файл лежит вне песочницы
 * приложения и открывается любым файловым менеджером. Куки в него не попадают.
 */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        return runCatching {
            val json = ProfileBackup.buildJson(context)
            val name = "hybrid-reading-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloads(context, name, json)
            } else {
                saveToAppFolder(context, name, json)
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(
        context: Context,
        name: String,
        json: String,
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Старую копию с тем же именем перезаписываем, а не плодим дубликаты.
        resolver.delete(
            collection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%HybridReading%"),
        )
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HybridReading")
            }
        val uri = resolver.insert(collection, values) ?: error("не удалось создать файл")
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("нет доступа к файлу")
    }

    private fun saveToAppFolder(
        context: Context,
        name: String,
        json: String,
    ) {
        val dir = File(context.getExternalFilesDir(null), "backup").apply { mkdirs() }
        File(dir, name).writeText(json)
        // Оставляем только пять последних копий.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    companion object {
        private const val WORK_NAME = "profile-backup"

        fun schedule(context: Context) {
            runCatching {
                val request =
                    PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                        )
                        .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
