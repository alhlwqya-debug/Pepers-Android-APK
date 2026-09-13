package com.add.pepers

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight Supabase Auth client using the public publishable key. */
internal object SupabaseAuth {
    private const val SUPABASE_URL = "https://qieukleyxkvwygzxfgsm.supabase.co"
    // Publishable client key. Never put a service-role/secret key in the APK.
    private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_5Y18fNgiYV2tRPyJH5_Ojg_lhf-Ww47"
    private const val PREFS = "add_paper_user"
    private const val SESSION = "supabase_session"
    private const val PROFILE = "supabase_profile"

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val expiresAt: Long
    )

    data class Profile(val name: String, val phone: String, val email: String)

    fun currentSession(context: Context): Session? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SESSION, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            Session(j.getString("access_token"), j.optString("refresh_token"), j.getString("user_id"), j.optLong("expires_at", 0L))
        }.getOrNull()
    }

    fun savedProfile(context: Context): Profile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(p.getString("user_name", "") ?: "", p.getString("user_phone", "") ?: "", p.getString("user_email", "") ?: "")
    }

    fun signUp(context: Context, name: String, phone: String, email: String, password: String): Result<Session?> = runCatching {
        val body = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
            put("data", JSONObject().apply {
                put("full_name", name.trim())
                put("name", name.trim())
                put("phone", phone.trim())
            })
        }
        val response = request("POST", "/auth/v1/signup", body.toString(), null)
        if (response.code !in 200..299) error(authError(response.body))
        val j = JSONObject(response.body)
        val session = sessionFrom(j)
        if (session != null) {
            saveSession(context, session)
            saveProfile(context, Profile(name.trim(), phone.trim(), email.trim()))
            upsertProfile(session, name.trim(), phone.trim(), email.trim())
        }
        session
    }

    fun signIn(context: Context, email: String, password: String): Result<Session> = runCatching {
        val body = JSONObject().apply { put("email", email.trim()); put("password", password) }
        val response = request("POST", "/auth/v1/token?grant_type=password", body.toString(), null)
        if (response.code !in 200..299) error(authError(response.body))
        val session = sessionFrom(JSONObject(response.body)) ?: error("لم يتم إنشاء جلسة دخول")
        saveSession(context, session)
        val user = JSONObject(response.body).optJSONObject("user")
        val metadata = user?.optJSONObject("user_metadata")
        val name = metadata?.optString("full_name")?.takeIf { !it.isNullOrBlank() } ?: metadata?.optString("name", "") ?: ""
        val phone = metadata?.optString("phone", "") ?: ""
        val mail = user?.optString("email", email.trim()) ?: email.trim()
        saveProfile(context, Profile(name, phone, mail))
        fetchAndMergeProfile(context, session)
        session
    }

    fun refresh(context: Context): Result<Session> = runCatching {
        val old = currentSession(context) ?: error("لا توجد جلسة")
        if (old.refreshToken.isBlank()) error("رمز تحديث الجلسة غير موجود")
        val response = request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", old.refreshToken).toString(), null)
        if (response.code !in 200..299) error(authError(response.body))
        val session = sessionFrom(JSONObject(response.body)) ?: error("تعذر تحديث الجلسة")
        saveSession(context, session)
        fetchAndMergeProfile(context, session)
        session
    }

    fun ensureSession(context: Context): Session? {
        val s = currentSession(context) ?: return null
        if (s.expiresAt == 0L || s.expiresAt > System.currentTimeMillis() / 1000L + 60) return s
        return refresh(context).getOrNull()
    }

    fun signOut(context: Context) {
        val s = currentSession(context)
        if (s != null) runCatching { request("POST", "/auth/v1/logout", "{}", s.accessToken) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SESSION).apply()
    }

    private fun fetchAndMergeProfile(context: Context, session: Session) {
        val response = request("GET", "/rest/v1/pepers_profiles?select=full_name,phone,email&user_id=eq.${session.userId}&limit=1", null, session.accessToken)
        if (response.code !in 200..299) return
        val array = runCatching { org.json.JSONArray(response.body) }.getOrNull() ?: return
        if (array.length() == 0) return
        val row = array.optJSONObject(0) ?: return
        val old = savedProfile(context)
        val profile = Profile(
            row.optString("full_name", old.name).ifBlank { old.name },
            row.optString("phone", old.phone).ifBlank { old.phone },
            row.optString("email", old.email).ifBlank { old.email }
        )
        saveProfile(context, profile)
    }

    private fun upsertProfile(session: Session, name: String, phone: String, email: String) {
        val body = JSONObject().apply {
            put("user_id", session.userId)
            put("full_name", name)
            put("phone", phone)
            put("email", email)
        }
        val response = request("POST", "/rest/v1/pepers_profiles", body.toString(), session.accessToken, "resolution=merge-duplicates")
        if (response.code !in 200..299) error("تعذر حفظ الملف الشخصي في السحابة")
    }

    private fun saveSession(context: Context, session: Session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SESSION, JSONObject().apply {
                put("access_token", session.accessToken)
                put("refresh_token", session.refreshToken)
                put("user_id", session.userId)
                put("expires_at", session.expiresAt)
            }.toString()).apply()
    }

    private fun saveProfile(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("user_name", profile.name)
            .putString("user_phone", profile.phone)
            .putString("user_email", profile.email)
            .apply()
    }

    private fun sessionFrom(j: JSONObject): Session? {
        val access = j.optString("access_token", "").takeIf { it.isNotBlank() } ?: return null
        val refresh = j.optString("refresh_token", "")
        val user = j.optJSONObject("user") ?: return null
        val userId = user.optString("id", "").takeIf { it.isNotBlank() } ?: return null
        val expiresIn = j.optLong("expires_in", 3600L)
        val expiresAt = j.optLong("expires_at", System.currentTimeMillis() / 1000L + expiresIn)
        return Session(access, refresh, userId, expiresAt)
    }

    private data class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String?, bearer: String?, prefer: String? = null): Response {
        val connection = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            if (!prefer.isNullOrBlank()) setRequestProperty("Prefer", prefer)
            doInput = true
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            Response(code, text)
        } finally { connection.disconnect() }
    }

    private fun authError(body: String): String {
        return runCatching {
            val j = JSONObject(body)
            j.optString("msg").ifBlank { j.optString("error_description") }.ifBlank { j.optString("message") }
        }.getOrNull()?.ifBlank { null } ?: "فشل تسجيل الدخول"
    }
}
