package com.spotifydashboard.app.utils

import com.google.gson.JsonElement
import com.spotifydashboard.app.api.LLMChatRowDto
import com.spotifydashboard.app.api.LLMStoredMessageDto
import com.spotifydashboard.app.repository.DashboardApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure helpers for the AI assistant. Kept out of the Compose/ViewModel layer
 * so the parsing rules (Open WebUI chat rows) can be unit-tested on the JVM.
 */

/** One displayable turn from a stored Open WebUI chat. */
data class AiChatTurn(val role: String, val content: String)

/**
 * Open WebUI message content is either a plain string or a list of parts
 * (`[{"type":"text","text":"..."}]`). Attachments/other parts are skipped.
 */
fun aiContentText(element: JsonElement?): String {
    if (element == null || element.isJsonNull) return ""
    if (element.isJsonPrimitive) {
        return if (element.asJsonPrimitive.isString) element.asString else ""
    }
    if (element.isJsonArray) {
        return element.asJsonArray.mapNotNull { part ->
            if (!part.isJsonObject) return@mapNotNull null
            val text = part.asJsonObject.get("text") ?: return@mapNotNull null
            if (text.isJsonPrimitive && text.asJsonPrimitive.isString) text.asString else null
        }.joinToString("\n")
    }
    return ""
}

/**
 * Flatten a stored chat into user/assistant turns.
 *
 * Open WebUI keeps both a flat `chat.messages` list (what its UI renders) and
 * a `chat.history.messages` tree. The flat list is authoritative for order;
 * system/tool turns, non-string content and empty turns are skipped.
 */
fun storedChatTurns(row: LLMChatRowDto?): List<AiChatTurn> {
    val messages: List<LLMStoredMessageDto> = row?.chat?.messages ?: return emptyList()
    return messages.mapNotNull { message ->
        val role = message.role
        if (role != "user" && role != "assistant") return@mapNotNull null
        val text = aiContentText(message.content)
        if (text.isBlank()) null else AiChatTurn(role, text)
    }
}

/** Friendly short model label for the status line. */
fun aiModelLabel(model: String?): String {
    if (model.isNullOrBlank()) return "no model"
    val tail = model.substringAfterLast('/').substringAfterLast('\\')
    return tail.replace(Regex("\\.gguf$", RegexOption.IGNORE_CASE), "")
}

/**
 * Open WebUI timestamps are epoch seconds; render today as a time, older
 * chats as a date. [nowMillis] is injectable so the rule stays testable.
 */
fun aiChatTimeLabel(
    epochSeconds: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    val date = Date(epochSeconds * 1000)
    val now = Date(nowMillis)
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val sameDay = day.format(date) == day.format(now)
    val format = SimpleDateFormat(if (sameDay) "HH:mm" else "yyyy-MM-dd", Locale.US)
    return format.format(date)
}

/** True when the server explicitly refuses chat history. */
fun isChatHistoryUnavailable(error: Throwable?): Boolean =
    error is DashboardApiException && error.errorCode == "llm_chat_unavailable"
