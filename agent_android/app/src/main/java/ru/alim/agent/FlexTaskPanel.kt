package ru.alim.agent

import android.app.Activity
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.collect

/** Coordinates are in the activity window, including system bars. */
@Composable
fun rememberTabletopFold(activity: Activity?): Rect? {
    var fold by remember(activity) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                .firstOrNull { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                ?.bounds?.let { Rect(it) }
        }
    }
    return fold
}

@Composable
fun FlexTaskPanel(core: Agent.Core, here: Scr, go: (Scr) -> Unit, modifier: Modifier = Modifier) {
    val haze = remember { HazeState() }
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress, scroll.value, scroll.maxValue) {
        if (scroll.isScrollInProgress) follow = scroll.value >= scroll.maxValue - 24
    }
    LaunchedEffect(core.steps.size, core.steps.lastOrNull()?.think, core.steps.lastOrNull()?.text) {
        if (follow) { androidx.compose.runtime.withFrameNanos { }; scroll.scrollTo(scroll.maxValue) }
    }
    Column(modifier.background(Bg).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().clip(shp(20.dp)).background(Card).padding(12.dp)) {
            Text("РАЗДУМЬЯ", style = body(12, FontWeight.Bold, Teal))
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (core.steps.isEmpty()) Text("Разбираюсь, с чего начать…", style = body(14, color = Muted))
                core.steps.forEach { step ->
                    Column {
                        Text((if (step.done) "✓  " else "●  ") + step.text, style = body(14, FontWeight.SemiBold))
                        if (step.think.isNotBlank()) Text(step.think, style = body(13, color = Muted))
                    }
                }
                core.pending?.let { ConfirmCard(it, core) }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).clip(shp(16.dp)).background(Color(0xFF101A32))
            .clickable(onClickLabel = "Остановить задачу") { core.stop() }, contentAlignment = Alignment.Center) {
            Text("■  СТОП", style = body(15, FontWeight.Bold, Color.White))
        }
        NavBar(haze, here, go)
    }
}
