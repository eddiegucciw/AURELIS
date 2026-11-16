package com.aurelis.app

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Online intelligence: Google Gemini (free tier) over HTTPS. */
object CloudBrain {

    class CloudError(val userMessage: String, val isNetwork: Boolean) : Exception(userMessage)

    /** Blocking. Returns the reply text or throws [CloudError]. */
    fun ask(apiKey: String, model: String, system: String, history: List<Msg>, user: String): String {
        val contents = JSONArray()
        history.takeLast(12).forEach { m ->
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))
        )
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw when (code) {
                    429 -> CloudError("I've reached the free usage limit for the moment.", false)
                    400, 401, 403 -> CloudError("My cloud API key isn't being accepted. Please check it in Settings.", false)
                    404 -> CloudError("The cloud model \"$model\" wasn't found. Please check the model name in Settings.", false)
                    else -> CloudError("The cloud service returned error $code.", code >= 500)
                }
            }
            val text = conn.inputStream.bufferedReader().readText()
            val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString().trim()
        } catch (e: CloudError) {
            throw e
        } catch (e: IOException) {
            throw CloudError("I can't reach the cloud right now.", true)
        } catch (e: Exception) {
            throw CloudError("The cloud reply was unreadable.", false)
        } finally {
            conn.disconnect()
        }
    }
}

/** Offline intelligence: a small language model running on the phone. */
object LocalBrain {

    data class LocalModel(
        val id: String,
        val name: String,
        val url: String,
        val file: String,
        val sizeMb: Int,
        val note: String
    )

    val MODELS = listOf(
        LocalModel(
            "qwen05", "Qwen2.5 0.5B · fast",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "qwen2.5-0.5b-instruct-q8.task", 547,
            "Recommended for 4–6 GB RAM phones"
        ),
        LocalModel(
            "qwen15", "Qwen2.5 1.5B · smarter",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "qwen2.5-1.5b-instruct-q8.task", 1600,
            "Needs 6 GB+ RAM; slower replies"
        )
    )

    private var llm: LlmInference? = null
    private var loadedId: String? = null

    fun file(ctx: Context, m: LocalModel) = File(File(ctx.filesDir, "models"), m.file)

    fun installed(ctx: Context) = MODELS.filter { file(ctx, it).exists() }

    fun active(ctx: Context): LocalModel? {
        val inst = installed(ctx)
        val wanted = Prefs(ctx).localModelId
        return inst.firstOrNull { it.id == wanted } ?: inst.firstOrNull()
    }

    fun install(ctx: Context, m: LocalModel) {
        AppState.modelDownload.value = m.id to 0
        try {
            Downloader.download(m.url, file(ctx, m)) { AppState.modelDownload.value = m.id to it }
        } finally {
            AppState.modelDownload.value = null
        }
        if (Prefs(ctx).localModelId.isBlank()) Prefs(ctx).localModelId = m.id
    }

    @Synchronized
    fun uninstall(ctx: Context, m: LocalModel) {
        if (loadedId == m.id) close()
        file(ctx, m).delete()
    }

    @Synchronized
    fun close() {
        try {
            llm?.close()
        } catch (e: Throwable) {
        }
        llm = null
        loadedId = null
    }

    @Synchronized
    private fun ensureLoaded(ctx: Context): Boolean {
        val m = active(ctx) ?: return false
        if (llm != null && loadedId == m.id) return true
        close()
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(ctx, m).absolutePath)
                .setMaxTokens(1024)
                .build()
            llm = LlmInference.createFromOptions(ctx, options)
            loadedId = m.id
            true
        } catch (e: Throwable) {
            close()
            false
        }
    }

    /** Blocking. Returns null if no local model could run. */
    @Synchronized
    fun generate(ctx: Context, system: String, history: List<Msg>, user: String): String? {
        if (!ensureLoaded(ctx)) return null
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        history.takeLast(4).forEach { m ->
            val role = if (m.role == "user") "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n").append(m.text.take(400)).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(user).append("<|im_end|>\n<|im_start|>assistant\n")
        return try {
            llm?.generateResponse(sb.toString())
                ?.replace("<|im_end|>", "")
                ?.replace("<|endoftext|>", "")
                ?.trim()
        } catch (e: Throwable) {
            null
        }
    }
}
