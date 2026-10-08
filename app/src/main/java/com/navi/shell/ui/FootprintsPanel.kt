package com.navi.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navi.shell.data.NaviTrip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 足迹。
 *
 * 「纪念意义」：去过哪、什么时候、怎么去的、走了多远。
 * 点一条就把它那条线画到地图上；上面「全部路线」能一次看全部。
 *
 * **轨迹是很私人的东西** —— 这里只读不写，也不上传到任何第三方，只存你自己的后端。
 */
@Composable
fun FootprintsPanel(
    trips: List<NaviTrip>,
    loading: Boolean,
    tracksOn: Boolean,
    onClose: () -> Unit,
    onPickTrip: (NaviTrip) -> Unit,
    onToggleTracks: () -> Unit,
    onClearDrawing: () -> Unit,
) {
    val totalM = trips.sumOf { it.distance_m }
    val byMode = trips.groupingBy { it.mode }.eachCount()

    Column(
        Modifier.fillMaxSize()
            .background(UiColors.Background.copy(alpha = 0.97f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("足迹", color = UiColors.AccentBright, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "关闭",
                color = UiColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onClose() }.padding(6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (loading) {
            Text("读取中…", color = UiColors.TextDim, fontSize = 13.sp)
        } else if (trips.isEmpty()) {
            Text(
                "还没有记录。\n导航过一次、到达之后就会存下一条。",
                color = UiColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )
        } else {
            Text(
                "一共 ${trips.size} 次 · 合计 ${fmtDistance(totalM)}",
                color = UiColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOf("drive" to "开车", "walk" to "走路", "ride" to "骑车")
                    .filter { (byMode[it.first] ?: 0) > 0 }
                    .joinToString(" · ") { "${it.second} ${byMode[it.first]}" },
                color = UiColors.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )

            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    text = if (tracksOn) "关掉路线" else "全部路线",
                    on = tracksOn,
                    onClick = onToggleTracks,
                )
                PillButton(text = "清掉图上的线", on = false, onClick = onClearDrawing)
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trips) { t -> TripRow(t) { onPickTrip(t) } }
        }
    }
}

@Composable
private fun PillButton(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (on) UiColors.Accent else UiColors.Surface,
            RoundedCornerShape(12.dp),
        ).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color = if (on) UiColors.Background else UiColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TripRow(t: NaviTrip, onClick: () -> Unit) {
    val whenText = remember(t.started_at) {
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t.started_at * 1000))
    }
    Row(
        Modifier.fillMaxWidth()
            .background(UiColors.Surface, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${t.from_name.ifBlank { "起点" }} → ${t.to_name.ifBlank { "终点" }}",
                color = UiColors.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "$whenText · ${modeLabel(t.mode)}" + if ((t.points ?: 0) > 0) " · 有线" else "",
                color = UiColors.TextDim,
                fontSize = 11.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                fmtDistance(t.distance_m),
                color = UiColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(fmtTime(t.duration_s), color = UiColors.TextDim, fontSize = 11.sp)
        }
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "walk" -> "走路"
    "ride" -> "骑车"
    else -> "开车"
}
