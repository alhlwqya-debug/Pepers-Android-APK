package com.add.pepers.cloud

import android.content.Context
import android.net.Uri
import com.add.pepers.Database
import com.add.pepers.SupabaseAuth
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Cloud backup provider for Pepers.
 * Only the local database and profile image are uploaded; auth tokens are never uploaded.
 */
internal class SupabaseCloudSync(private val context: Context) : CloudSyncProvider {
    override val providerName: String = "Supabase"

    private val bucket = "pepers-backups"
    private val baseUrl = "https://qieukleyxkvwygzxfgsm.supabase.co"
    private val apiKey = "sb_publishable_5Y18fNgiYV2tRPyJH5_Ojg_lhf-Ww47"

    override suspend fun uploadBackup(uri: Uri): Result<String> = runCatching {
        val session = SupabaseAuth.ensureSession(context) ?: error("لا توجد جلسة دخول")
        val userId = session.userId
        val path = "$userId/latest.zip"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("تعذر قراءة النسخة الاحتياطية")
        val response = requestBytes("POST", "/storage/v1/object/$bucket/$path", bytes, session.accessToken, "application/zip", mapOf("x-upsert" to "true"))
        if (response.code !in 200..299) error(response.body.ifBlank { "تعذر رفع النسخة الاحتياطية" })
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
        val path = "$bucket/${session.userId}/latest.zip"
        val response = requestBytes("GET", "/storage/v1/object/info/$path", null, session.accessToken, null)
        if (response.code !in 200..299) return@runCatching emptyList()
        val j = JSONObject(response.body)
        listOf(CloudBackupItem("latest", "latest.zip", j.optLong("updated_at", 0L), j.optLong("size", 0L)))
    }

    suspend fun syncAutomatically(): Result<String> = runCatching {
        val session = SupabaseAuth.ensureSession(context) ?: return@runCatching "غير مسجل الدخول"
        val db = Database(context)
        db.optimizeDatabase()
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        db.close()

        val localHasData = runCatching { Database(context).use { it.getShops().isNotEmpty() } }.getOrDefault(false)
        val remote = downloadLatestBackup()
        if (!localHasData && remote.isSuccess) {
            restoreCloudZip(remote.getOrThrow())
            return@runCatching "تمت استعادة البيانات من السحابة"
        }

        if (localHasData) {
            val zip = createCloudBackup()
            val uploaded = uploadBackup(Uri.fromFile(zip)).getOrThrow()
            return@runCatching "تمت المزامنة: $uploaded"
        }
        "لا توجد بيانات محلية للمزامنة"
    }

    private fun createCloudBackup(): File {
        val dbFile = context.getDatabasePath("add_paper.db")
        if (!dbFile.exists()) error("قاعدة البيانات غير موجودة")
        val out = File(context.cacheDir, "pepers_cloud_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("database/add_paper.db"))
            FileInputStream(dbFile).use { it.copyTo(zip) }
            zip.closeEntry()
            val image = File(context.filesDir, "profile_image.jpg")
            if (image.exists()) {
                zip.putNextEntry(ZipEntry("files/profile_image.jpg"))
                FileInputStream(image).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val info = "Pepers cloud backup\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
            zip.putNextEntry(ZipEntry("backup_info.txt")); zip.write(info.toByteArray()); zip.closeEntry()
        }
        return out
    }

    private fun restoreCloudZip(uri: Uri) {
        val zipFile = if (uri.scheme == "file") File(uri.path ?: error("مسار النسخة غير صالح")) else {
            val temp = File(context.cacheDir, "cloud_restore_input.zip")
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
            temp
        }
        val tempDir = File(context.cacheDir, "cloud_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(tempDir, entry.name)
                if (!target.canonicalPath.startsWith(tempDir.canonicalPath + File.separator)) error("نسخة احتياطية غير صالحة")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val source = File(tempDir, "database/add_paper.db")
        if (!source.exists()) error("قاعدة البيانات غير موجودة داخل النسخة")
        val target = context.getDatabasePath("add_paper.db")
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        File(context.dataDir, "databases/add_paper.db-wal").delete()
        File(context.dataDir, "databases/add_paper.db-shm").delete()
        val image = File(tempDir, "files/profile_image.jpg")
        if (image.exists()) FileInputStream(image).use { input -> FileOutputStream(File(context.filesDir, "profile_image.jpg")).use { input.copyTo(it) } }
        zipFile.delete()
        tempDir.deleteRecursively()
    }

    private fun updateBackupMetadata(accessToken: String, userId: String, path: String) {
        val body = JSONObject().apply { put("user_id", userId); put("object_path", path); put("updated_at", "now()") }
        // updated_at is server-managed; omit it from the JSON to avoid sending SQL as data.
        body.remove("updated_at")
        val response = requestText("POST", "/rest/v1/pepers_cloud_backups", body.toString(), accessToken, "resolution=merge-duplicates")
        if (response.code !in 200..299) error("تعذر حفظ حالة المزامنة")
    }

    private data class ByteResponse(val code: Int, val body: String, val bytes: ByteArray)

    private fun requestBytes(method: String, path: String, bytes: ByteArray?, bearer: String?, contentType: String?, headers: Map<String, String> = emptyMap()): ByteResponse {
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
            val text = data.toString(Charsets.UTF_8)
            ByteResponse(code, text, data)
        } finally { connection.disconnect() }
    }

    private fun requestText(method: String, path: String, body: String, bearer: String, prefer: String): ByteResponse =
        requestBytes(method, path, body.toByteArray(Charsets.UTF_8), bearer, "application/json", mapOf("Prefer" to prefer))
}
