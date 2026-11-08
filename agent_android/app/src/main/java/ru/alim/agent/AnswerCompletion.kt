package ru.alim.agent

// Обещание в будущем времени не является результатом проверки источника.
private val pendingWebSentence = Regex(
    "[^.!?\\n]*(?:открою|прочитаю|проверю|изучу)\\s+(?:эту\\s+|нужную\\s+|саму\\s+)?(?:публикаци\\p{L}*|стать\\p{L}*|страниц\\p{L}*|источник\\p{L}*)[^.!?\\n]*[.!?]?",
    RegexOption.IGNORE_CASE
)

internal fun unfinishedWebAction(text: String): Boolean = pendingWebSentence.containsMatchIn(text)

internal fun withoutUnfinishedWebAction(text: String): String =
    text.replace(pendingWebSentence, "").trim().let { remaining ->
        listOf(remaining, "Проверку публикации завершить не удалось; подробности не подтверждены.")
            .filter { it.isNotBlank() }.joinToString("\n\n")
    }
