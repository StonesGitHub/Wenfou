package com.wenfou.app

import java.net.URI
import java.security.MessageDigest

/** Only public share routes are eligible; private conversation and arbitrary URLs are rejected. */
object ShareImport {
    const val MAX_INPUT = 120_000
    data class Link(val platform: String, val url: String)
    data class Turn(val question: String, val answer: String)
    data class Message(val role: String, val text: String)
    val platforms = listOf("DeepSeek", "ChatGPT", "豆包", "Kimi", "其他 / 未知")
    private val hosts = mapOf(
        "chat.deepseek.com" to "DeepSeek", "deepseek.com" to "DeepSeek",
        "chatgpt.com" to "ChatGPT", "www.chatgpt.com" to "ChatGPT", "chat.openai.com" to "ChatGPT",
        "doubao.com" to "豆包", "www.doubao.com" to "豆包", "v.doubao.com" to "豆包",
        "kimi.com" to "Kimi", "www.kimi.com" to "Kimi", "kimi.ai" to "Kimi", "www.kimi.ai" to "Kimi", "kimi.moonshot.cn" to "Kimi"
    )
    fun link(text: String): Link? = Regex("https://[^\\s<>\"'，。！？）】]+", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
        val value = match.value.trimEnd('.', ',', ';', ')', ']', '}', '：', '；')
        runCatching {
            val uri = URI(value)
            val platform = hosts[uri.host?.lowercase()] ?: return@runCatching null
            if (uri.scheme.lowercase() != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return@runCatching null
            val path = uri.rawPath.orEmpty()
            val valid = when (platform) {
                "豆包" -> path.matches(Regex("/(thread|s|share)/[A-Za-z0-9_-]+/?")) || (uri.host == "v.doubao.com" && path.matches(Regex("/[A-Za-z0-9_-]+/?")))
                else -> path.matches(Regex("/share/[A-Za-z0-9_-]+/?"))
            }
            if (!valid) null else Link(platform, "https://${uri.host.lowercase()}$path")
        }.getOrNull()
    }.firstOrNull()

    fun safeNavigation(url: String, platform: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && link(url)?.let {
            it.platform == platform && URI(it.url).host.equals(uri.host, ignoreCase = true)
        } == true
    }.getOrDefault(false)
    fun fingerprint(text: String) = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    fun canSuggest(text: String): Boolean = text.length <= MAX_INPUT && (link(text) != null || parseText(text).any { it.question.isNotBlank() && it.answer.isNotBlank() } ||
        (text.trim().length >= 40 && !text.trim().matches(Regex("[A-Za-z0-9_+/=.-]+")) && !text.trim().startsWith("http")))
    fun platformHint(text: String): String = link(text)?.platform ?: platforms.dropLast(1).firstOrNull { text.contains(it, ignoreCase = true) } ?: "其他 / 未知"

    /** Role delimiters are recognized only at line starts and outside fenced code. */
    fun parseText(raw: String): List<Turn> {
        require(raw.length <= MAX_INPUT) { "内容过长，请只复制需要分享的一轮问答（最多 12 万字符）。" }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isBlank()) return emptyList()
        val labelled = Regex("^(?:#{1,6}\\s*)?(?:\\*\\*)?(问题|提问|问|用户|我|你说|You said|User|Q|回答|答案|助手|答|A|Assistant|ChatGPT said|ChatGPT 说|ChatGPT|DeepSeek|豆包|Kimi)(?:\\*\\*)?\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
        val standalone = Regex("^(You said|ChatGPT said|用户|提问|问题|回答|答案|助手|ChatGPT|DeepSeek|豆包|Kimi)[:：]?$", RegexOption.IGNORE_CASE)
        val userRoles = setOf("问题", "提问", "问", "用户", "我", "你说", "you said", "user", "q")
        val messages = mutableListOf<Message>()
        var role = ""
        val preamble = StringBuilder()
        val buffer = StringBuilder()
        var fence = ""
        fun flush() { if (role.isNotEmpty() && buffer.isNotBlank()) messages += Message(role, buffer.toString().trim()); buffer.clear() }
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (fence.isEmpty()) fence = marker else if (fence == marker) fence = ""
                if (role.isNotEmpty()) buffer.appendLine(line) else preamble.appendLine(line)
            } else {
                val match = if (fence.isEmpty()) labelled.matchEntire(trimmed) else null
                val alone = if (fence.isEmpty() && match == null) standalone.matchEntire(trimmed) else null
                if (match != null || alone != null) {
                    flush()
                    val name = (match ?: alone)!!.groupValues[1].lowercase()
                    role = if (name in userRoles) "user" else "assistant"
                    if (match != null) buffer.appendLine(match.groupValues[2])
                } else if (role.isNotEmpty()) buffer.appendLine(line) else preamble.appendLine(line)
            }
        }
        flush()
        return if (messages.isEmpty()) listOf(Turn("", text)) else (if (preamble.isBlank()) emptyList() else listOf(Turn("", preamble.toString().trim()))) + pair(messages)
    }

    fun pair(messages: List<Message>): List<Turn> {
        val result = mutableListOf<Turn>()
        var question = ""
        var answer = ""
        fun flush() { if (question.isNotBlank() || answer.isNotBlank()) result += Turn(question.trim(), answer.trim()); question = ""; answer = "" }
        messages.forEach {
            when (it.role) {
                "user" -> { flush(); question = it.text }
                "assistant" -> { answer += (if (answer.isEmpty()) "" else "\n\n") + it.text }
            }
        }
        flush()
        return result
    }
}
