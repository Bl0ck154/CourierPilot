package com.block154.courierpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.DaySummary
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun OfferHeatmap(
    days: List<DaySummary>,
    weeks: Int,
    selectedDay: String?,
    onSelected: (DaySummary?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDay = remember(days) { days.associateBy { it.day } }
    val start = remember(weeks) {
        LocalDate.now()
            .minusWeeks((weeks - 1).toLong())
            .with(DayOfWeek.MONDAY)
    }
    val maxCount = remember(days) { days.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1 }
    val scroll = rememberScrollState()

    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(weeks) { week ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { dayIndex ->
                    val date = start.plusDays((week * 7L) + dayIndex)
                    val key = date.toString()
                    val summary = byDay[key]
                    val future = date.isAfter(LocalDate.now())
                    val fill = when {
                        future -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        summary == null || summary.count == 0 -> MaterialTheme.colorScheme.surfaceVariant
                        else -> BrandBlue.copy(alpha = 0.28f + 0.72f * (summary.count.toFloat() / maxCount))
                    }
                    val selected = selectedDay == key
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(fill)
                            .then(
                                if (selected) Modifier.border(2.dp, BrandCyan, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable(enabled = !future) { onSelected(summary) }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "Less activity    ▪  ▪  ▪  ▪    more",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}
