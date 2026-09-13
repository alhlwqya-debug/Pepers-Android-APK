package com.add.pepers.cloud

import android.content.Context
import android.net.Uri
import com.add.pepers.Database
import com.add.pepers.SupabaseAuth
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud backup provider for Pepers.
 * Only the local database and profile image are uploaded; auth tokens are never uploaded.
 *
 * Synchronization is deliberately conservative: when both sides contain data, the
 * newest database is kept instead of blindly overwriting a newer cloud backup.
 */
internal class SupabaseCloudSync(private val context: Context) : CloudSyncProvider {
    override val providerName: String = "Supabase"

    private val bucket = "pepers-backups"
    private val baseUrl = "https://qieukleyxkvwygzxfgsm.supabase.co"
    private val apiKey = "sb_publishable_5Y18fNgiYV2tRPyJH5_Ojg_lhf-Ww47"

    companion object {
        private val syncMutex = Mutex()
        private const val DATABASE_NAME = "add_paper.db"
        private const val PROFILE_IMAGE_NAME = "profile_image.jpg"
        private const val CLOCK_SKEW_TOLERANCE_MS = 5000L
    }

    override suspend fun uploadBackup(uri: Uri): Result<String> = runCatching {
        val session = SupabaseAuth.ensureSession(context) ?: error("لا توجد جلسة دخول")
        val userId = session.userId
        val path = "$userId/latest.zip"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("تعذر قراءة النسخة الاحتياطية")
        val response = requestBytes(
            "POST",
            "/storage/v1/object/$bucket/$path",
            bytes,
            session.accessToken,
            "application/zip",
            mapOf("x-upsert" to "true")
        )
        if (response.code !in 200..299) {
            error(response.body.ifBlank { "تعذر رفع النسخة الاحتياطية" })
        }
        updateBackupMetadata(session.accessToken, userId, path)
        path
    }

    override suspend fun downloadLatestBackup(): Result<Uri> = runCatching {
        val session = SupabaseAuth.ensureSession(context) ?: error("لا توجد جلسة دخول")
        val path = "$bucket/${session.userId}/latest.zip"
        val response = requestBytes("GET", "/storage/v1/object/$path", null, session.accessToken, null)
        if (response.code != 200) error(response.body.ifBlank { "لا توجد نسخة احتياطية سحابية" })
        val out = File(context.cacheDir, "cloud_restore_latest.zip")
        FileOutputStream(out).use { it.write(response.bytes) }
        Uri.fromFile(out)
    }

    override suspend fun listBackups(): Result<List<CloudBackupItem>> = runCatching {
        val session = SupabaseAuth.ensureSession(context) ?: error("لا توجد جلسة دخول")
        val metadata = fetchRemoteMetadata(session.accessToken, session.userId) ?: return@runCatching emptyList()
        val response = requestBytes(
            "GET",
            "/storage/v1/object/info/$bucket/${session.userId}/latest.zip",
            null,
            session.accessToken,
            null
        )
        if (response.code !in 200..299) return@runCatching emptyList()
        val j = JSONObject(response.body)
        listOf(
            CloudBackupItem(
                "latest",
                "latest.zip",
                parseTimestamp(metadata.optString("updated_at")) ?: j.optLong("updated_at", 0L),
                j.optLong("size", 0L)
            )
        )
    }

    /** Automatic sync used by startup and WorkManager. */
    suspend fun syncAutomatically(): Result<String> = syncMutex.withLock {
        runCatching {
            val session = SupabaseAuth.ensureSession(context) ?: return@runCatching "غير مسجل الدخول"

            val local = prepareLocalDatabaseForBackup()
            val remoteMetadata = fetchRemoteMetadata(session.accessToken, session.userId)
            val remoteExists = remoteMetadata != null

            when {
                !local.hasData && remoteExists -> {
                    val remote = downloadLatestBackup().getOrThrow()
                    restoreCloudZip(remote)
                    "تمت استعادة البيانات من السحابة"
                }

                local.hasData && remoteExists -> {
                    val remoteUpdatedAt = parseTimestamp(remoteMetadata!!.optString("updated_at"))
                    val localUpdatedAt = local.databaseLastModified
                    if (remoteUpdatedAt != null && remoteUpdatedAt > localUpdatedAt + CLOCK_SKEW_TOLERANCE_MS) {
                        val remote = downloadLatestBackup().getOrThrow()
                        restoreCloudZip(remote)
                        "تمت استعادة النسخة السحابية الأحدث"
                    } else {
                        uploadFreshBackup().getOrThrow()
                        "تمت مزامنة البيانات المحلية إلى السحابة"
                    }
                }

                local.hasData -> {
                    uploadFreshBackup().getOrThrow()
                    "تم رفع النسخة الاحتياطية إلى السحابة"
                }

                else -> "لا توجد بيانات محلية أو سحابية للمزامنة"
            }
        }
    }

    private data class LocalDatabaseState(
        val hasData: Boolean,
        val databaseLastModified: Long
    )

    private fun prepareLocalDatabaseForBackup(): LocalDatabaseState {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return LocalDatabaseState(false, 0L)

        // Checkpoint and close SQLite before copying the main database file.
        Database(context).use { db ->
            db.optimizeDatabase()
            try {
                db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    while (cursor.moveToNext()) Unit
                }
            } catch (_: Exception) {
                // Best effort when WAL is disabled or unavailable.
            }
        }

        val hasData = runCatching {
            Database(context).use { it.getShops().isNotEmpty() }
        }.getOrDefault(false)
        return LocalDatabaseState(hasData, dbFile.lastModified())
    }

    private suspend fun uploadFreshBackup(): String {
        val zip = createCloudBackup()
        return try {
            uploadBackup(Uri.fromFile(zip)).getOrThrow()
        } finally {
            zip.delete()
        }
    }

    private fun createCloudBackup(): File {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) error("قاعدة البيانات غير موجودة")

        val out = File(context.cacheDir, "pepers_cloud_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("database/$DATABASE_NAME"))
            FileInputStream(dbFile).use { it.copyTo(zip) }
            zip.closeEntry()

            val image = File(context.filesDir, PROFILE_IMAGE_NAME)
            if (image.exists()) {
                zip.putNextEntry(ZipEntry("files/$PROFILE_IMAGE_NAME"))
                FileInputStream(image).use { it.copyTo(zip) }
                zip.closeEntry()
            }

            val info = buildString {
                append("Pepers cloud backup\n")
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                append('\n')
            }
            zip.putNextEntry(ZipEntry("backup_info.txt"))
            zip.write(info.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out
    }

    private fun restoreCloudZip(uri: Uri) {
        val zipFile = if (uri.scheme == "file") {
            File(uri.path ?: error("مسار النسخة غير صالح"))
        } else {
            val temp = File(context.cacheDir, "cloud_restore_input.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { input.copyTo(it) }
            } ?: error("تعذر قراءة النسخة السحابية")
            temp
        }

        val tempDir = File(context.cacheDir, "cloud_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(tempDir, entry.name)
                    if (!target.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) {
                        error("نسخة احتياطية غير صالحة")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val source = File(tempDir, "database/$DATABASE_NAME")
            if (!source.exists()) error("قاعدة البيانات غير موجودة داخل النسخة")

            // No Database instance is open here. Remove stale WAL/SHM files before
            // the restored database can be opened again.
            val databaseDir = File(context.dataDir, "databases")
            databaseDir.mkdirs()
            val target = File(databaseDir, DATABASE_NAME)
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { input.copyTo(it) }
            }
            File(databaseDir, "$DATABASE_NAME-wal").delete()
            File(databaseDir, "$DATABASE_NAME-shm").delete()

            val image = File(tempDir, "files/$PROFILE_IMAGE_NAME")
            if (image.exists()) {
                FileInputStream(image).use { input ->
                    FileOutputStream(File(context.filesDir, PROFILE_IMAGE_NAME)).use { input.copyTo(it) }
                }
            }
        } finally {
            zipFile.delete()
            tempDir.deleteRecursively()
        }
    }

    private fun fetchRemoteMetadata(accessToken: String, userId: String): JSONObject? {
        val response = requestBytes(
            "GET",
            "/rest/v1/pepers_cloud_backups?user_id=eq.$userId&select=user_id,object_path,updated_at&limit=1",
            null,
            accessToken,
            null
        )
        if (response.code !in 200..299) return null
        val array = runCatching { JSONArray(response.body) }.getOrNull() ?: return null
        return if (array.length() > 0) array.optJSONObject(0) else null
    }

    private fun updateBackupMetadata(accessToken: String, userId: String, path: String) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("object_path", path)
        }
        val response = requestText(
            "POST",
            "/rest/v1/pepers_cloud_backups",
            body.toString(),
            accessToken,
            "resolution=merge-duplicates,return=minimal"
        )
        if (response.code !in 200..299) error("تعذر حفظ حالة المزامنة")
    }

    private fun parseTimestamp(value: String): Long? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(normalized)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private data class ByteResponse(val code: Int, val body: String, val bytes: ByteArray)

    private fun requestBytes(
        method: String,
        path: String,
        bytes: ByteArray?,
        bearer: String?,
        contentType: String?,
        headers: Map<String, String> = emptyMap()
    ): ByteResponse {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("apikey", apiKey)
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            if (!contentType.isNullOrBlank()) setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doInput = true
            if (bytes != null) doOutput = true
        }
        return try {
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            ByteResponse(code, data.toString(Charsets.UTF_8), data)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestText(
        method: String,
        path: String,
        body: String,
        bearer: String,
        prefer: String
    ): ByteResponse = requestBytes(
        method,
        path,
        body.toByteArray(Charsets.UTF_8),
        bearer,
        "application/json",
        mapOf("Prefer" to prefer)
    )
}
