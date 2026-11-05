package com.spotifydashboard.app.api

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/** Guards the additive /api/v1/llm route strings against typos. */
class DashboardLlmContractTest {

    private fun method(name: String) =
        DashboardApiService::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `llm status and chat routes`() {
        assertEquals("api/v1/llm/status", method("llmStatus").getAnnotation(GET::class.java).value)
        assertEquals("api/v1/llm/chat", method("llmChat").getAnnotation(POST::class.java).value)
    }

    @Test
    fun `llm chat history routes`() {
        assertEquals("api/v1/llm/chats", method("llmChats").getAnnotation(GET::class.java).value)
        assertEquals(
            "api/v1/llm/chats/current",
            method("llmCurrentChat").getAnnotation(GET::class.java).value
        )
        assertEquals(
            "api/v1/llm/chats/{id}",
            method("llmChatById").getAnnotation(GET::class.java).value
        )
        assertEquals(
            "api/v1/llm/chats/{id}",
            method("llmDeleteChat").getAnnotation(DELETE::class.java).value
        )
        assertEquals(
            "api/v1/llm/chats/current",
            method("llmClearCurrentChat").getAnnotation(DELETE::class.java).value
        )
    }

    @Test
    fun `chat request defaults are stateless`() {
        val request = LLMChatRequest(messages = listOf(LLMMessageDto("user", "hi")))
        assertEquals(false, request.useHistory)
        assertEquals(false, request.persist)
        assertEquals(true, request.useTools)
        assertEquals(20, request.historyLimit)
    }
}
