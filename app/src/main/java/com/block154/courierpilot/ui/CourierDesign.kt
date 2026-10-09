package com.block154.courierpilot.ui

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design D ("Daylight + Night pilot"): light grouped lists with the live card's gold→grey verdict
 * colours. Every colour that is not a Material role lives here so light and dark stay in sync.
 */
data class CourierPalette(
    val dark: Boolean,
    val line: Color,
    val chip: Color,
    val chipBorder: Color,
    val chipText: Color,
    val accent: Color,
    val onlineBg: Color,
    val onlineText: Color,
    val offlineBg: Color,
    val offlineText: Color,
    val miniStatBg: Color,
    val bestBg: Color,
    val codeBg: Color,
    val codeText: Color,
    val pinBg: Color,
    val pinText: Color,
    val iconBg: Color,
    val navActive: Color,
    val navInactive: Color,
    val woltBg: Color,
    val woltText: Color,
    val boltBg: Color,
    val boltText: Color,
    val rateFire: Color,
    val rateGood: Color,
    val rateOk: Color,
    val rateBad: Color,
    val rateTerrible: Color,
    val rateEstimate: Color,
    val fireGlow: Color,
)

val LightPalette = CourierPalette(
    dark = false,
    line = Color(0xFFEEF0F3),
    chip = Color.White,
    chipBorder = Color(0xFFE2E8F0),
    chipText = Color(0xFF0F172A),
    accent = Color(0xFF0369A1),
    onlineBg = Color(0xFFDCFCE7),
    onlineText = Color(0xFF166534),
    offlineBg = Color(0xFFF1F5F9),
    offlineText = Color(0xFF64748B),
    miniStatBg = Color(0xFFF5F6F8),
    bestBg = Color(0xFFFFF6D6),
    codeBg = Color(0xFFFFF6D6),
    codeText = Color(0xFF5C4A00),
    pinBg = Color(0xFFEEF6FF),
    pinText = Color(0xFF0369A1),
    iconBg = Color(0xFFF1F5F9),
    navActive = Color(0xFF0F172A),
    navInactive = Color(0xFF94A3B8),
    woltBg = Color(0xFFE0F2FE),
    woltText = Color(0xFF0369A1),
    boltBg = Color(0xFFDCFCE7),
    boltText = Color(0xFF15803D),
    // Deeper golds than the overlay so the number keeps contrast on white.
    rateFire = Color(0xFFB07800),
    rateGood = Color(0xFFA88310),
    rateOk = Color(0xFF8F866B),
    rateBad = Color(0xFF858C96),
    // Muted clay instead of near-transparent grey: the worst offers stay readable in History.
    rateTerrible = Color(0xFFA0705F),
    rateEstimate = Color(0xFF64748B),
    fireGlow = Color(0x59FFBE00),
)

val DarkPalette = CourierPalette(
    dark = true,
    line = Color(0x0FFFFFFF),
    chip = Color(0x0FFFFFFF),
    chipBorder = Color(0x1FFFFFFF),
    chipText = Color(0xFFCBD5E1),
    accent = Color(0xFF7DD3FC),
    onlineBg = Color(0x1F4ADE80),
    onlineText = Color(0xFF86EFAC),
    offlineBg = Color(0x0FFFFFFF),
    offlineText = Color(0xFF94A3B8),
    miniStatBg = Color(0x0AFFFFFF),
    bestBg = Color(0x14FFD60A),
    codeBg = Color(0x1FFFD60A),
    codeText = Color(0xFFFFD60A),
    pinBg = Color(0x1A7DD3FC),
    pinText = Color(0xFF7DD3FC),
    iconBg = Color(0x0DFFFFFF),
    navActive = Color(0xFFFFD60A),
    navInactive = Color(0xFF64748B),
    woltBg = Color(0x2438BDF8),
    woltText = Color(0xFF7DD3FC),
    boltBg = Color(0x244ADE80),
    boltText = Color(0xFF86EFAC),
    // Same colours as the live card overlay.
    rateFire = Color(0xFFFFD60A),
    rateGood = Color(0xFFF2C53D),
    rateOk = Color(0xFFBDB18A),
    rateBad = Color(0xFF8A9099),
    rateTerrible = Color(0xFFB08575),
    rateEstimate = Color(0xFFCBD5E1),
    fireGlow = Color(0x80FFD60A),
)

val LocalCourierPalette = staticCompositionLocalOf { LightPalette }

/** Condensed numerals for money; a system font, so it renders identically on every device. */
val RateNumberFamily: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

/** Verdict grade for colour and emoji treatment; mirrors the live card bands. */
enum class RateGrade(val emoji: String, val saturation: Float, val alpha: Float) {
    FIRE("🔥", 1.2f, 1f),
    GOOD("👍", 0.9f, 0.95f),
    OK("😐", 0.45f, 0.8f),
    BAD("👎", 0.3f, 0.75f),
    TERRIBLE("💩", 0.5f, 0.75f),
    ESTIMATE("⏳", 0.6f, 0.8f),
    UNKNOWN("", 0.6f, 0.8f),
}

fun CourierPalette.rateColor(grade: RateGrade): Color = when (grade) {
    RateGrade.FIRE -> rateFire
    RateGrade.GOOD -> rateGood
    RateGrade.OK -> rateOk
    RateGrade.BAD -> rateBad
    RateGrade.TERRIBLE -> rateTerrible
    RateGrade.ESTIMATE, RateGrade.UNKNOWN -> rateEstimate
}

/** `€1.43/km`: big condensed number, small unit, coloured by verdict. */
@Composable
fun RateText(
    value: String,
    unit: String,
    grade: RateGrade,
    valueSize: TextUnit = 19.sp,
    unitSize: TextUnit = 11.sp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCourierPalette.current
    val color = palette.rateColor(grade)
    Text(
        buildAnnotatedString {
            append(value)
            withStyle(SpanStyle(fontSize = unitSize, color = color.copy(alpha = color.alpha * 0.75f))) { append(unit) }
        },
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontFamily = RateNumberFamily,
            fontWeight = FontWeight.Bold,
            fontSize = valueSize,
            color = color,
            shadow = if (grade == RateGrade.FIRE) Shadow(palette.fireGlow, blurRadius = 16f) else null,
        ),
    )
}

/** Emoji that desaturates with a worse verdict, but never so far that a row becomes unreadable. */
@Composable
fun VerdictEmoji(grade: RateGrade, size: TextUnit = 17.sp, modifier: Modifier = Modifier) {
    if (grade.emoji.isEmpty()) return
    val paint = Paint().apply {
        alpha = grade.alpha
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(grade.saturation) })
    }
    Text(
        grade.emoji,
        fontSize = size,
        modifier = modifier.drawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, this.size.width, this.size.height), paint)
                drawContent()
                canvas.restore()
            }
        },
    )
}

@Composable
fun PlatformBadge(platform: String, modifier: Modifier = Modifier) {
    val palette = LocalCourierPalette.current
    val bolt = platform.equals("Bolt", ignoreCase = true)
    Surface(
        modifier = modifier.size(38.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (bolt) palette.boltBg else palette.woltBg,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                platform.take(1).uppercase(),
                color = if (bolt) palette.boltText else palette.woltText,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
            )
        }
    }
}

/** Shape of one row inside a grouped list: only the outer corners are rounded. */
fun groupedRowShape(index: Int, count: Int, radius: Int = 20): Shape {
    val r = radius.dp
    return when {
        count <= 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/**
 * One row of a grouped list. Rows are separate lazy items (cheap on long History pages) that
 * visually join into one rounded block.
 */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalCourierPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = groupedRowShape(index, count),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = if (palette.dark) BorderStroke(1.dp, palette.line) else null,
        shadowElevation = if (palette.dark) 0.dp else 0.5.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            if (index < count - 1) {
                HorizontalDivider(color = palette.line, thickness = 1.dp)
            }
        }
    }
}

@Composable
fun GroupedBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalCourierPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = if (palette.dark) BorderStroke(1.dp, palette.line) else null,
        shadowElevation = if (palette.dark) 0.dp else 0.5.dp,
    ) { content() }
}

@Composable
fun FilterChipD(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalCourierPalette.current
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.onBackground else palette.chip,
        border = if (selected) null else BorderStroke(1.dp, palette.chipBorder),
        modifier = Modifier.height(36.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (selected) scheme.background else palette.chipText,
            )
        }
    }
}
