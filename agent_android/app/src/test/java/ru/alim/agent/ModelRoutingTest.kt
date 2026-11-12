package ru.alim.agent

import org.junit.Assert.*
import org.junit.Test

class ModelRoutingTest {
    @Test fun providerDistinguishesDirectDeepSeekFromYandexAndAnyModel() {
        assertEquals("deepseek", modelProvider("deepseek:deepseek-flash"))
        assertEquals("deepseek", modelProvider("deepseek:deepseek-v4-pro"))
        assertEquals("yandex", modelProvider("yc:deepseek-v4.1-flash"))
        assertEquals("anymodel", modelProvider("ds/deepseek-v4-pro"))
        assertEquals("codex", modelProvider("chatgpt:gpt-6.1-sol"))
    }
    @Test fun directModelsAppearInAllRelevantRoles() {
        val models = listOf("deepseek:deepseek-flash", "deepseek:deepseek-v4-pro")
        for (id in models) {
            assertTrue(Prefs.CHAT_MODELS.contains(id))
            assertTrue(Prefs.HANDS_MODELS.contains(id))
            assertTrue(Prefs.BG_MODELS.contains(id))
        }
    }
    @Test fun codexAutoNeverInheritsYandexOrGoogleModels() {
        assertEquals("cc/claude-sonnet-5", Llm.codexModel("yc:aliceai-llm-flash"))
        assertEquals("cc/claude-sonnet-5", Llm.codexModel("google:gemini-3.5-flash-lite"))
        assertEquals("deepseek:deepseek-flash", Llm.codexModel("deepseek:deepseek-flash"))
        assertEquals("chatgpt:gpt-6.1-sol", Llm.codexModel("chatgpt:gpt-6.1-sol"))
    }
    @Test fun directLabelsIncludeProviderAndModel() {
        assertTrue(modelLabel("deepseek:deepseek-flash").contains("deepseek-flash"))
        assertTrue(modelLabel("deepseek:deepseek-flash").contains("DeepSeek"))
        assertTrue(modelLabel("yc:deepseek-v4.1-flash").contains("Яндекс"))
    }
}
