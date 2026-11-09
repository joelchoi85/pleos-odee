package com.hyundai.pleos.syncdrive.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyundai.pleos.syncdrive.domain.CompanionVehicleUiModel
import com.hyundai.pleos.syncdrive.domain.GeoPoint
import com.hyundai.pleos.syncdrive.ui.theme.PleosColors

/** 차량 마커 색: 이탈=Amber, 나=Cyan, 통화중=Green, 그 외=Slate. 색만으로 구분하지 않도록 번호 병기. */
@Composable
private fun markerColor(v: CompanionVehicleUiModel): Color = when {
    v.isOffRoute -> PleosColors.AmberAlert
    v.isSelfHost -> PleosColors.PleosCyan
    v.inVoiceCall -> PleosColors.EmeraldVoice
    else -> PleosColors.TextSecondary
}

/** 진행률이 마커 지름(minGap) 이내로 붙은 차는 좌(-1)·우(+1)로 번갈아 비켜 놓는다. 입력은 진행률 오름차순. */
internal fun assignLanes(sortedProgress: List<Float>, minGap: Float): List<Int> {
    val lanes = ArrayList<Int>(sortedProgress.size)
    sortedProgress.forEachIndexed { i, f ->
        lanes += if (i > 0 && f - sortedProgress[i - 1] < minGap) {
            when (lanes[i - 1]) { 0 -> 1; 1 -> -1; else -> 0 }
        } else 0
    }
    return lanes
}

/**
 * 세로 대열선: 위=도착, 아래=출발. 차량을 경로 진행률 위치에 번호로 표시 → 선두(위)·후미(아래)가 한눈에.
 */
@Composable
fun ConvoyVerticalLine(route: List<GeoPoint>, vehicles: List<CompanionVehicleUiModel>, modifier: Modifier = Modifier) {
    val trackColor = PleosColors.RouteLine
    val doneColor = PleosColors.PleosCyan
    val markerSize = 44.dp
    val positioned = vehicles.mapIndexedNotNull { i, v ->
        val lat = v.lat ?: return@mapIndexedNotNull null
        val lng = v.lng ?: return@mapIndexedNotNull null
        Triple(i + 1, v, progressAlongRoute(route, GeoPoint(lat, lng)))
    }.sortedBy { it.third }
    val selfProgress = positioned.firstOrNull { it.second.isSelfHost }?.third ?: 0f

    BoxWithConstraints(modifier.fillMaxHeight().width(124.dp)) {
        val usable = maxHeight - markerSize
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2
            val top = markerSize.toPx() / 2
            val bottom = size.height - markerSize.toPx() / 2
            val w = 10.dp.toPx()
            drawLine(trackColor, Offset(x, top), Offset(x, bottom), w, StrokeCap.Round)
            drawLine(doneColor, Offset(x, bottom), Offset(x, bottom - (bottom - top) * selfProgress), w, StrokeCap.Round) // 내가 지나온 구간
            drawCircle(doneColor, 12.dp.toPx(), Offset(x, top))                                     // 도착(채운 원)
            drawCircle(trackColor, 12.dp.toPx(), Offset(x, bottom), style = Stroke(5.dp.toPx()))    // 출발(빈 원)
        }
        val lanes = assignLanes(positioned.map { it.third }, (markerSize / usable).coerceAtMost(1f))
        positioned.forEachIndexed { idx, (number, v, f) ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 40.dp * lanes[idx], y = usable * (1f - f))
                    .size(markerSize)
                    .background(markerColor(v), CircleShape)
                    .then(if (v.isSelfHost) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                    .semantics { contentDescription = "${v.displayName} ${(f * 100).toInt()}% 지점${if (v.isOffRoute) ", 경로 이탈" else ""}" },
                contentAlignment = Alignment.Center
            ) {
                Text("$number", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
