package ru.alim.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Палитра темы. Светлые — Мята, Песок, Небо; тёмная — Ночь. */
class Pal(
    val id: String, val title: String, val dark: Boolean,
    val bg: Color, val card: Color, val line: Color, val ink: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val violet: Color, val amber: Color, val danger: Color,
    // переписка
    val wall: List<Color>, val dots: List<Color>,
    val inBubble: Color, val outBubble: Color, val outInk: Color, val outMeta: Color, val meta: Color,
    val glass: Color, val glassLine: Color, val online: Color,
)

object Th {
    val all = listOf(
        Pal("cockpit", "Кабина", true,
            bg = Color(0xFF0B1422), card = Color(0xFF13233A), line = Color(0xFF243A55), ink = Color(0xFFEAF2FB), muted = Color(0xFF9DB2C8),
            accent = Color(0xFF6CC6EE), onAccent = Color(0xFF06121F), violet = Color(0xFF8B9CFF), amber = Color(0xFFF2C58A), danger = Color(0xFFFF9A8F),
            wall = listOf(Color(0xFF0B1422), Color(0xFF173149), Color(0xFF0A1420)),
            dots = listOf(Color(0x00000000), Color(0x00000000), Color(0x00000000)),
            inBubble = Color(0xF0142740), outBubble = Color(0xFF2A5079), outInk = Color(0xFFF2F8FF), outMeta = Color(0xFFB7CDE6), meta = Color(0xFF8DA3BA),
            glass = Color(0x8A0F1C2E), glassLine = Color(0x26FFFFFF), online = Color(0xFF5FE3B0)),
        Pal("mint", "Мята", false,
            bg = Color(0xFFF3F6F5), card = Color(0xFFFFFFFF), line = Color(0xFFDCE3E0), ink = Color(0xFF14201C), muted = Color(0xFF5F6E69),
            accent = Color(0xFF0E8C77), onAccent = Color.White, violet = Color(0xFF6D5BD0), amber = Color(0xFFB45309), danger = Color(0xFFC62828),
            wall = listOf(Color(0xFFE6F4D9), Color(0xFFCFEBE3), Color(0xFFD6E4F2)),
            dots = listOf(Color(0x290E8C77), Color(0x1F786EE6), Color(0x24E6A028)),
            inBubble = Color.White, outBubble = Color(0xFFD3F5C6), outInk = Color(0xFF14201C), outMeta = Color(0xFF3F7A55), meta = Color(0xFF6B7A75),
            glass = Color(0x8CFFFFFF), glassLine = Color(0xB3FFFFFF), online = Color(0xFF22C55E)),
        Pal("sand", "Песок", false,
            bg = Color(0xFFF7F3EC), card = Color(0xFFFFFFFF), line = Color(0xFFE6DDCF), ink = Color(0xFF2A2118), muted = Color(0xFF75685A),
            accent = Color(0xFFC46A1E), onAccent = Color.White, violet = Color(0xFF7A5BC8), amber = Color(0xFFB45309), danger = Color(0xFFC62828),
            wall = listOf(Color(0xFFF4E9D6), Color(0xFFEBDDC6), Color(0xFFF1E2D9)),
            dots = listOf(Color(0x29C46A1E), Color(0x1F7A5BC8), Color(0x24A0782D)),
            inBubble = Color.White, outBubble = Color(0xFFFFE2B8), outInk = Color(0xFF2A2118), outMeta = Color(0xFF8A6A3C), meta = Color(0xFF7D7064),
            glass = Color(0x8CFFFFFF), glassLine = Color(0xB3FFFFFF), online = Color(0xFF22C55E)),
        Pal("sky", "Небо", false,
            bg = Color(0xFFF2F5F9), card = Color(0xFFFFFFFF), line = Color(0xFFDCE3EC), ink = Color(0xFF131C28), muted = Color(0xFF5B6878),
            accent = Color(0xFF2F6FD6), onAccent = Color.White, violet = Color(0xFF6D5BD0), amber = Color(0xFFB45309), danger = Color(0xFFC62828),
            wall = listOf(Color(0xFFDDEBFA), Color(0xFFD3E2F4), Color(0xFFE4DDF5)),
            dots = listOf(Color(0x292F6FD6), Color(0x1F8B7CFF), Color(0x2422A0C8)),
            inBubble = Color.White, outBubble = Color(0xFFCFE3FF), outInk = Color(0xFF131C28), outMeta = Color(0xFF41618E), meta = Color(0xFF677486),
            glass = Color(0x8CFFFFFF), glassLine = Color(0xB3FFFFFF), online = Color(0xFF22C55E)),
        Pal("night", "Ночь", true,
            bg = Color(0xFF0E1116), card = Color(0xFF151A21), line = Color(0xFF262C36), ink = Color(0xFFE8EAED), muted = Color(0xFF9AA3AE),
            accent = Color(0xFF3DD9C1), onAccent = Color(0xFF0E1116), violet = Color(0xFF8B7CFF), amber = Color(0xFFF5B83D), danger = Color(0xFFFF8A80),
            wall = listOf(Color(0xFF11251F), Color(0xFF0E1116), Color(0xFF15122A)),
            dots = listOf(Color(0x213DD9C1), Color(0x1A8B7CFF), Color(0x14F5B83D)),
            inBubble = Color(0xFF1A2028), outBubble = Color(0xFF1F6F65), outInk = Color(0xFFF2FFFC), outMeta = Color(0xFFBFEDE5), meta = Color(0xFF8A939E),
            glass = Color(0x85161C24), glassLine = Color(0x12FFFFFF), online = Color(0xFF3DD9C1)),
    )

    var c by mutableStateOf(all[0])
        private set

    fun load() {
        // переход на «Кабину» один раз — дальше Алим может выбрать любую тему сам
        if (!Prefs.cockpitTheme) { Prefs.cockpitTheme = true; Prefs.theme = "cockpit" }
        c = all.firstOrNull { it.id == Prefs.theme } ?: all[0]
    }
    val cockpit: Boolean get() = c.id == "cockpit"
    fun set(id: String) { Prefs.theme = id; load() }
}

// Оттенки, которые раньше были «зашиты» под тёмную тему
val AccentSoft: Color get() = Teal.copy(alpha = if (Th.c.dark) 0.18f else 0.12f)
val AccentSoftInk: Color get() = if (Th.c.dark) Color(0xFFA8DCD3) else Th.c.accent
val WarnBg: Color get() = if (Th.c.dark) Color(0xFF1A160C) else Color(0xFFFFF4E0)
val WarnLine: Color get() = if (Th.c.dark) Color(0xFF4A3A16) else Color(0xFFF2D49A)
val WarnInk: Color get() = if (Th.c.dark) Color(0xFFE3D3B0) else Color(0xFF6B4A10)
val DangerBg: Color get() = if (Th.c.dark) Color(0xFF251516) else Color(0xFFFDECEC)
val DangerLine: Color get() = if (Th.c.dark) Color(0xFF5A2A2A) else Color(0xFFF2B8B8)
val Track: Color get() = if (Th.c.dark) Color(0xFF1B2129) else Color(0xFFE6EBE9)
val ChipInk: Color get() = if (Th.c.dark) Color(0xFFC7CDD4) else Th.c.ink
val OnAccent: Color get() = Th.c.onAccent
