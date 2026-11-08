package ru.alim.agent

import org.junit.Assert.*
import org.junit.Test

class AnswerCompletionTest {
    @Test fun recognizesAndRemovesPromise() {
        val text = "Найдено сообщение. Открою публикацию, чтобы понять, касается ли это твоего района."
        assertTrue(unfinishedWebAction(text))
        val result = withoutUnfinishedWebAction(text)
        assertTrue(result.contains("Найдено сообщение."))
        assertFalse(result.contains("Открою"))
        assertTrue(result.contains("не удалось"))
    }

    @Test fun completedAndUnrelatedAnswersAreNotPromises() {
        assertFalse(unfinishedWebAction("Прочитал публикацию: адреса не указаны."))
        assertFalse(unfinishedWebAction("Проверено: источник не открылся."))
        assertFalse(unfinishedWebAction("Могу проверить товар позже."))
    }
}
