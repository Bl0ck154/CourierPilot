package com.block154.courierpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/*
 * Shared design-D screen scaffolding. Every Compose screen uses these so headers, section labels,
 * grouped blocks and link rows look identical across Dashboard, details and settings screens.
 * Screen content: LazyColumn with contentPadding 16.dp horizontal, no spacedBy (spacing comes
 * from SectionLabel / Spacer), rows inside GroupedRow / SettingsGroup / GroupedBlock.
 */

/** 44 dp rounded icon button on the soft icon background. */
@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = LocalCourierPalette.current.iconBg,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

/** Top-level tab title: 26 sp extra-bold with a muted subtitle. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 4.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** Pushed screen header: back button, title and optional subtitle. */
@Composable
fun DetailHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** Small uppercase label above a grouped block. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One rounded block holding toggle rows or free content, separated by [SettingsDivider]. */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    GroupedBlock {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(color = LocalCourierPalette.current.line, thickness = 1.dp)
}

/** Muted explanatory text under a block. */
@Composable
fun Footnote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    )
}

/** Grouped-list row with a tinted icon tile, title, subtitle and chevron. */
@Composable
fun LinkRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val palette = LocalCourierPalette.current
    GroupedRow(index = index, count = count, onClick = onClick) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = if (subtitleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else subtitleColor,
                    fontSize = 12.5.sp,
                )
            }
        }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 38 dp tinted tile with an icon, as used for address pins and settings links. */
@Composable
fun IconTile(icon: ImageVector) {
    val palette = LocalCourierPalette.current
    Surface(shape = RoundedCornerShape(12.dp), color = palette.pinBg, modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = palette.pinText, modifier = Modifier.size(20.dp))
        }
    }
}

/** 38 dp tinted tile with short text such as P1 / D2. */
@Composable
fun TextTile(text: String, background: Color, foreground: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = background, modifier = Modifier.size(38.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = foreground, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
    }
}

/** Grey placeholder text in a rounded block (loading / empty states). */
@Composable
fun EmptyBlock(text: String) {
    GroupedBlock {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full-width tappable text row (secondary action) inside a grouped list. */
@Composable
fun ActionRow(index: Int, count: Int, text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    GroupedRow(index = index, count = count, onClick = onClick) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (color == Color.Unspecified) LocalCourierPalette.current.accent else color,
        )
    }
}
