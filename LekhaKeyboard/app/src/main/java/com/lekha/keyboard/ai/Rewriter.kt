package com.lekha.keyboard.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Produces corrected rewrites by calling Google's Gemini API. Keeps the same public shape
 * the keyboard already used (suspend fun rewrite -> up to 3 options), so swapping the
 * backend didn't touch the IME. Uses built-in HttpURLConnection + org.json (no SDK).
 *
 * Change MODEL to move to a newer flash model (e.g. "gemini-3.5-flash") if it's free for you.
 * The API key is stored only on this device in private SharedPreferences.
 */
object Rewriter {

    private const val PREFS = "lekha_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val MODEL = "gemini-3.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    @Volatile private var lastError: String? = null
    fun lastError(): String? = lastError

    // ---- API key storage ----

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun hasApiKey(context: Context): Boolean = getApiKey(context) != null

    // ---- public operations ----

    /** Up to 3 corrected rewrites, or empty list on any failure (see lastError()). */
    suspend fun rewrite(context: Context, text: String): List<String> =
        withContext(Dispatchers.IO) {
            val key = getApiKey(context) ?: run {
                lastError = "No API key set"; return@withContext emptyList()
            }
            val (code, body) = postRaw(key, buildRewriteRequest(text))
            if (code !in 200..299) { lastError = parseError(code, body); return@withContext emptyList() }
            lastError = null
            parseCandidates(body, text)
        }

    /** Verify a key works. Returns null on success, else an error message. */
    suspend fun testKey(context: Context, key: String): String? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK.")))
                )
            ).toString()
            val (code, body) = postRaw(key, payload)
            if (code in 200..299) null else parseError(code, body)
        }

    // ---- request / response ----

    private fun buildRewriteRequest(text: String): String {
        val prompt =
            "Rewrite the message below in correct, natural, friendly English for someone " +
            "whose first language is Kannada. Keep the same meaning and a warm, casual tone. " +
            "Do not add new facts. Return a JSON array of exactly 3 rewritten options as plain " +
            "strings, and nothing else.\n\nMessage: \"" + text + "\""
        val userTurn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        val genCfg = JSONObject()
            .put("temperature", 0.4)
            .put("maxOutputTokens", 512)
            .put("responseMimeType", "application/json")
        return JSONObject()
            .put("contents", JSONArray().put(userTurn))
            .put("generationConfig", genCfg)
            .toString()
    }

    private fun postRaw(key: String, payload: String): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            code to body
        } catch (e: java.net.UnknownHostException) {
            -1 to "no_internet"
        } catch (e: java.net.SocketTimeoutException) {
            -2 to "timeout"
        } catch (e: Throwable) {
            -3 to (e.message ?: "request_failed")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseError(code: Int, body: String): String = when (code) {
        -1 -> "No internet connection."
        -2 -> "The request timed out. Try again."
        400 -> extractApiMessage(body) ?: "Bad request. Check your API key."
        403 -> "API key rejected. Make sure the Gemini API is enabled for it."
        404 -> "Model not found. The app may need a newer model name."
        429 -> "Rate limit reached (free tier). Wait a moment and try again."
        in 500..599 -> "Gemini server error. Try again shortly."
        else -> extractApiMessage(body) ?: "Request failed (code $code)."
    }

    private fun extractApiMessage(body: String): String? =
        try { JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } }
        catch (_: Throwable) { null }

    private fun parseCandidates(raw: String, original: String): List<String> {
        val text = try {
            val obj = JSONObject(raw)
            val candidates = obj.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val block = obj.optJSONObject("promptFeedback")?.optString("blockReason")
                lastError = if (!block.isNullOrBlank()) "Blocked by safety filter." else "No response from Gemini."
                return emptyList()
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().trim()
        } catch (t: Throwable) {
            lastError = "Couldn't read Gemini's response."
            return emptyList()
        }

        val options = parseJsonArray(text) ?: parseNumbered(text)
        val cleaned = options
            .map { it.trim().trim('"', '\u201C', '\u201D', '*', '`').trim() }
            .filter { it.isNotEmpty() && !it.equals(original, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(3)
        if (cleaned.isEmpty()) lastError = "Gemini returned nothing usable."
        return cleaned
    }

    private fun parseJsonArray(text: String): List<String>? {
        val t = stripFences(text)
        return try {
            val arr = JSONArray(t)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            if (out.isEmpty()) null else out
        } catch (_: Throwable) { null }
    }

    private fun parseNumbered(text: String): List<String> {
        val numbered = Regex("^\\s*\\d+[.)\\-]\\s*(.+)$")
        val out = ArrayList<String>()
        for (lineRaw in text.lines()) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            out.add((numbered.find(line)?.groupValues?.get(1) ?: line).trim())
        }
        return out
    }

    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end).trim()
        }
        return t
    }
}
