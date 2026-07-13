package com.Fluent.keyboard.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Google's Gemini API. Three jobs:
 *   - listModels(): ask Google which models THIS key can use with generateContent
 *     (so the app never guesses a model name).
 *   - rewrite(): up to 3 corrected rewrites of the message.
 *   - rewriteMore(): 3 DIFFERENT rewrites, excluding ones already shown (the "More" button).
 *
 * Key and chosen model are stored only on-device. Built-in HttpURLConnection + org.json.
 */
object Rewriter {

    private const val PREFS = "Fluent_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_MODEL = "gemini_model"
    private const val DEFAULT_MODEL = "gemini-2.5-flash"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    @Volatile private var lastError: String? = null
    fun lastError(): String? = lastError

    // ---- stored settings ----

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setApiKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_API, key.trim()).apply()

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun hasApiKey(context: Context): Boolean = getApiKey(context) != null

    fun setModel(context: Context, model: String) =
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    // ---- operations ----

    /** Models this key can call with generateContent (flash first). Empty on failure. */
    suspend fun listModels(context: Context, key: String): List<String> =
        withContext(Dispatchers.IO) {
            val (code, body) = getRaw("$BASE/models", key)
            if (code !in 200..299) { lastError = parseError(code, body); return@withContext emptyList() }
            lastError = null
            parseModels(body)
        }

    /** Up to 3 corrected rewrites using the stored model. */
    suspend fun rewrite(context: Context, text: String): List<String> =
        generate(context, text, exclude = emptyList(), temperature = 0.4)

    /** 3 different rewrites, avoiding the ones already shown. */
    suspend fun rewriteMore(context: Context, text: String, exclude: List<String>): List<String> =
        generate(context, text, exclude = exclude, temperature = 0.95)

    private suspend fun generate(context: Context, text: String, exclude: List<String>, temperature: Double): List<String> =
        withContext(Dispatchers.IO) {
            val key = getApiKey(context) ?: run { lastError = "No API key set"; return@withContext emptyList() }
            val url = "$BASE/models/${getModel(context)}:generateContent"
            val (code, body) = postRaw(url, key, buildRewriteRequest(text, exclude, temperature))
            if (code !in 200..299) { lastError = parseError(code, body); return@withContext emptyList() }
            lastError = null
            parseCandidates(body, text, exclude)
        }

    // ---- HTTP ----

    private fun getRaw(url: String, key: String) = request(url, key, "GET", null)
    private fun postRaw(url: String, key: String, payload: String) = request(url, key, "POST", payload)

    private fun request(urlStr: String, key: String, method: String, payload: String?): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("x-goog-api-key", key)
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (payload != null) conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
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

    // ---- request / response bodies ----

    private fun buildRewriteRequest(text: String, exclude: List<String>, temperature: Double): String {
        val sb = StringBuilder()
        sb.append("Rewrite the message below in correct, natural, friendly English for someone ")
        sb.append("whose first language is Kannada. Keep the same meaning and a warm, casual tone. ")
        sb.append("Do not add new facts. ")
        if (exclude.isNotEmpty()) {
            sb.append("Give 3 options that are clearly DIFFERENT in wording from these already-shown ones:\n")
            for (e in exclude.take(9)) sb.append("- ").append(e).append("\n")
        }
        sb.append("Return a JSON array of exactly 3 rewritten options as plain strings, and nothing else.\n\n")
        sb.append("Message: \"").append(text).append("\"")

        val userTurn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", sb.toString())))
        val genCfg = JSONObject()
            .put("temperature", temperature)
            .put("maxOutputTokens", 512)
            .put("responseMimeType", "application/json")
        return JSONObject()
            .put("contents", JSONArray().put(userTurn))
            .put("generationConfig", genCfg)
            .toString()
    }

    private fun parseModels(body: String): List<String> = try {
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            var supports = false
            if (methods != null) for (j in 0 until methods.length())
                if (methods.optString(j) == "generateContent") { supports = true; break }
            if (!supports) continue
            val name = m.optString("name").removePrefix("models/")
            if (name.isNotBlank()) out.add(name)
        }
        out.sortedWith(compareBy({ !it.startsWith("gemini") }, { !it.contains("flash") }, { it }))
    } catch (_: Throwable) { emptyList() }

    private fun parseError(code: Int, body: String): String = when (code) {
        -1 -> "No internet connection."
        -2 -> "The request timed out. Try again."
        400 -> extractApiMessage(body) ?: "Bad request. Check your API key."
        403 -> "API key rejected. Make sure the Gemini API is enabled for it."
        404 -> "Not found. This model may not be available to your key."
        429 -> "Rate limit reached (free tier). Wait a moment and try again."
        in 500..599 -> "Gemini server error. Try again shortly."
        else -> extractApiMessage(body) ?: "Request failed (code $code)."
    }

    private fun extractApiMessage(body: String): String? =
        try { JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } }
        catch (_: Throwable) { null }

    private fun parseCandidates(raw: String, original: String, exclude: List<String>): List<String> {
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
            .filter { it.isNotEmpty() && !it.equals(original, ignoreCase = true) && exclude.none { e -> e.equals(it, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
            .take(3)
        if (cleaned.isEmpty()) lastError = "Gemini returned nothing new."
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
