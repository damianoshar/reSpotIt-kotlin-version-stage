package com.spotifydashboard.app.utils

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.spotifydashboard.app.api.LLMChatBodyDto
import com.spotifydashboard.app.api.LLMChatRequest
import com.spotifydashboard.app.api.LLMChatRowDto
import com.spotifydashboard.app.api.LLMMessageDto
import com.spotifydashboard.app.api.LLMStatusDto
import com.spotifydashboard.app.api.LLMStoredMessageDto
import com.spotifydashboard.app.repository.DashboardApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AiChatTest {

    private fun text(value: String) = JsonPrimitive(value)

    @Test
    fun `stored chat turns keep ordered user and assistant turns only`() {
        val row = LLMChatRowDto(
            id = "c1",
            chat = LLMChatBodyDto(
                messages = listOf(
                    LLMStoredMessageDto(role = "user", content = text("Hi")),
                    LLMStoredMessageDto(role = "assistant", content = text("Hello")),
                    LLMStoredMessageDto(role = "system", content = text("ignored")),
                    LLMStoredMessageDto(role = "assistant", content = text("   ")),
                    LLMStoredMessageDto(role = "user", content = JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "part")
                        })
                    }),
                    LLMStoredMessageDto(role = "tool", content = text("ignored")),
                )
            )
        )
        assertEquals(
            listOf(
                AiChatTurn("user", "Hi"),
                AiChatTurn("assistant", "Hello"),
                AiChatTurn("user", "part"),
            ),
            storedChatTurns(row)
        )
    }

    @Test
    fun `stored chat turns handle a missing flat list`() {
        assertEquals(emptyList<AiChatTurn>(), storedChatTurns(null))
        assertEquals(emptyList<AiChatTurn>(), storedChatTurns(LLMChatRowDto(id = "c1")))
        assertEquals(
            emptyList<AiChatTurn>(),
            storedChatTurns(LLMChatRowDto(id = "c1", chat = LLMChatBodyDto(messages = emptyList())))
        )
    }

    @Test
    fun `model label strips path and gguf suffix`() {
        assertEquals(
            "Qwen3.6-35B-A3B-UD-Q4_K_XL",
            aiModelLabel("D:\\huggingface\\hub\\Qwen3.6-35B-A3B-UD-Q4_K_XL.gguf")
        )
        assertEquals("Qwen3.6", aiModelLabel("Qwen3.6"))
        assertEquals("no model", aiModelLabel(null))
        assertEquals("no model", aiModelLabel("  "))
    }

    @Test
    fun `time label is empty for missing or invalid timestamps`() {
        assertEquals("", aiChatTimeLabel(null))
        assertEquals("", aiChatTimeLabel(0))
        assertEquals("", aiChatTimeLabel(-5))
    }

    @Test
    fun `time label uses time for today and date for older chats`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = now / 1000 - 60 * 60
        val old = now / 1000 - 3 * 24 * 60 * 60
        assertTrue(aiChatTimeLabel(today, now).matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(aiChatTimeLabel(old, now).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `history unavailable only matches the contract code`() {
        assertTrue(
            isChatHistoryUnavailable(
                DashboardApiException(503, "llm_chat_unavailable", "x")
            )
        )
        assertFalse(
            isChatHistoryUnavailable(
                DashboardApiException(502, "llm_chat_error", "x")
            )
        )
        assertFalse(isChatHistoryUnavailable(Exception("x")))
        assertFalse(isChatHistoryUnavailable(null))
    }

    @Test
    fun `chat request serialises snake case keys including false flags`() {
        val json = Gson().toJson(
            LLMChatRequest(
                messages = listOf(LLMMessageDto("user", "hi")),
                chatId = "c1",
                useHistory = false,
                persist = false,
            )
        )
        assertTrue(json.contains("\"chat_id\":\"c1\""))
        assertTrue(json.contains("\"use_history\":false"))
        assertTrue(json.contains("\"persist\":false"))
        assertTrue(json.contains("\"max_tokens\":1024"))
        assertTrue(json.contains("\"use_tools\":true"))
        assertFalse(json.contains("chatId"))
        assertFalse(json.contains("useHistory"))
    }

    @Test
    fun `stored chat turns parse a full Open WebUI row`() {
        val row = Gson().fromJson(
            """
            {
              "id": "chat-1",
              "title": "Library chat",
              "updated_at": 1793700000,
              "chat": {
                "messages": [
                  {"id": "m1", "role": "user", "content": "Describe my library"},
                  {"id": "m2", "role": "assistant", "content": [{"type": "text", "text": "You own 42 albums."}]}
                ],
                "history": {
                  "messages": {
                    "m1": {"id": "m1", "role": "user", "content": "Describe my library"},
                    "m2": {"id": "m2", "role": "assistant", "content": "You own 42 albums."}
                  },
                  "currentId": "m2"
                }
              }
            }
            """.trimIndent(),
            LLMChatRowDto::class.java
        )
        assertEquals("chat-1", row.id)
        assertEquals(
            listOf(
                AiChatTurn("user", "Describe my library"),
                AiChatTurn("assistant", "You own 42 albums."),
            ),
            storedChatTurns(row)
        )
    }

    @Test
    fun `status parses the chat history flag`() {
        val status = Gson().fromJson(
            """{"configured":true,"available":true,"base_url":"http://owui:8080",
               "model":"qwen.gguf","models":1,"error":null,"chat_history":true}""",
            LLMStatusDto::class.java
        )
        assertTrue(status.configured)
        assertTrue(status.available)
        assertTrue(status.chatHistory)
        assertEquals("http://owui:8080", status.baseUrl)
    }
}
