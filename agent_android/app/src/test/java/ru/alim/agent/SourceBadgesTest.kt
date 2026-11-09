package ru.alim.agent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class SourceBadgesTest {
    private val url = "https://tvgram.ru/chp_crimea/82789"
    private fun metadata(date: String) = JSONObject().put("sources", JSONArray().put(
        JSONObject().put("url", url).put("published", date))).toString()

    @Test fun removesOnlyMatchingInlineCitationAndDate() {
        val text = "Пострадавших нет. 🕒 Данные опубликованы 7.11 в 18:27. Источник: ЧП / Крым — $url"
        assertEquals("Пострадавших нет.", answerWithoutSourceLines(text, metadata("2026-11-07T18:27:00")))
    }

    @Test fun preservesDifferentDateAndUnrecordedLink() {
        val text = "Данные опубликованы 7.11 в 18:28. Источник: ЧП — $url"
        assertEquals("Данные опубликованы 7.11 в 18:28.", answerWithoutSourceLines(text, metadata("2026-11-07T18:27:00")))
        assertEquals(text, answerWithoutSourceLines(text, ""))
    }
}
