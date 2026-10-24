package app.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrainException(val userMessage: String) : Exception(userMessage)

/** Talks to the Claude Messages API and runs the phone tools Claude asks for. Call [ask] from a worker thread. */
class Brain(private val prefs: Prefs, private val actions: Actions) {
    private val history = ArrayList<JSONObject>()

    fun reset() {
        history.clear()
    }

    fun ask(userText: String): String {
        val key = prefs.apiKey
        if (key.isEmpty()) throw BrainException("आधी सेटिंग्जमध्ये Anthropic API key टाका.")

        val messages = JSONArray()
        for (m in history) messages.put(m)
        messages.put(textMessage("user", userText))

        val system = systemPrompt()
        val tools = actions.toolSpecs()
        val spoken = StringBuilder()
        var finalText = ""

        for (round in 0 until MAX_ROUNDS) {
            val body = JSONObject()
                .put("model", prefs.model)
                .put("max_tokens", 600)
                .put("system", system)
                .put("tools", tools)
                .put("messages", messages)
            val resp = post(key, body)
            val content = resp.optJSONArray("content") ?: JSONArray()
            val stop = resp.optString("stop_reason")

            val texts = StringBuilder()
            val toolUses = ArrayList<JSONObject>()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> texts.append(block.optString("text"))
                    "tool_use" -> toolUses.add(block)
                }
            }

            if (toolUses.isEmpty() || stop != "tool_use") {
                finalText = texts.toString().trim()
                break
            }

            if (texts.isNotBlank()) spoken.append(texts.toString().trim()).append(' ')
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            for (tu in toolUses) {
                val name = tu.optString("name")
                val out = actions.run(name, tu.optJSONObject("input") ?: JSONObject())
                results.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", tu.optString("id"))
                        .put("content", out)
                        .put("is_error", out.startsWith("ERROR"))
                )
                val wait = actions.settleMs(name)
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (e: InterruptedException) {
                    }
                }
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
        }

        if (finalText.isEmpty()) finalText = spoken.toString().trim()
        if (finalText.isEmpty()) finalText = "ठीक आहे."

        history.add(textMessage("user", userText))
        history.add(textMessage("assistant", finalText))
        while (history.size > 16) {
            history.removeAt(0)
            history.removeAt(0)
        }
        return finalText
    }

    private fun textMessage(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("content", text)

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val apps = actions.apps().take(150).joinToString(", ") { it.first }
        return """
You are Jarvis, a fast voice assistant on the user's Android phone. The user mostly speaks Marathi, sometimes Hindi or English.
Reply in the language the user used. Default to simple spoken Marathi in Devanagari.
Your reply is read aloud: one or two short sentences, no markdown, no emoji, no lists, never read out URLs or numbers like ids.
You control the phone with tools. When the user asks for an action, call the tool right away instead of explaining. Ask only when the target is truly ambiguous.
App names: pass the English launcher name (YouTube, WhatsApp, Instagram, Camera, Photos). Contact names: pass the name as the user said it; if no match, retry once with the other script (Devanagari or English).
For screen control use read_screen first to see what is on screen, then tap_text, scroll, type_text or phone_global. Work step by step and re-read the screen after each change. Stop after about ten steps and tell the user where you got to.
Sensitive buttons (send, pay, transfer, buy, delete, order, subscribe) need the user's spoken yes in this conversation. Ask first, and only then call tap_text with confirmed=true.
Be honest about limits: you cannot edit photos or videos by yourself. For editing, open the user's editing app (Google Photos, Snapseed, CapCut, InShot, whichever is installed) and then help by tapping through its screens. You cannot press send in WhatsApp or SMS unless the user confirms. You cannot hear or see anything except the text you are given.
If a tool returns an ERROR, tell the user in one short sentence what went wrong.
Current local time: $now
Installed apps: $apps
""".trimIndent()
    }

    private fun post(key: String, body: JSONObject): JSONObject {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw BrainException(explain(code, text))
            return JSONObject(text)
        } catch (e: IOException) {
            throw BrainException("इंटरनेट किंवा सर्व्हर कनेक्शन अयशस्वी. नेट तपासा.")
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(code: Int, raw: String): String {
        var detail = ""
        try {
            detail = JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
        }
        return when (code) {
            401 -> "API key चुकीची आहे. सेटिंग्जमध्ये तपासा."
            403 -> "या API key ला परवानगी नाही."
            404 -> "मॉडेलचे नाव चुकले असू शकते: ${prefs.model}"
            429 -> "वापर मर्यादा संपली किंवा खूप विनंत्या झाल्या. थोड्या वेळाने बघा."
            400 -> "विनंती नाकारली: $detail"
            else -> if (code >= 500) "Claude सर्व्हर सध्या व्यस्त आहे. पुन्हा प्रयत्न करा." else "त्रुटी $code: $detail"
        }
    }

    companion object {
        const val MAX_ROUNDS = 12
    }
}
