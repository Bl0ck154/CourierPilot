package com.block154.courierpilot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.EmptyBlock
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LinkRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.PlatformBadge
import com.block154.courierpilot.ui.RateNumberFamily
import com.block154.courierpilot.ui.RateText
import com.block154.courierpilot.ui.SectionLabel
import com.block154.courierpilot.ui.SquareIconButton
import com.block154.courierpilot.ui.TextTile
import com.block154.courierpilot.ui.VerdictEmoji
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfferDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val offerId = intent.getLongExtra(EXTRA_OFFER_ID, -1L)
        setContent {
            CourierPilotTheme {
                var loaded by remember(offerId) { mutableStateOf(false) }
                var data by remember(offerId) { mutableStateOf<OfferDetailsData?>(null) }

                LaunchedEffect(offerId) {
                    data = withContext(Dispatchers.IO) {
                        runCatching {
                            val offer = OfferDatabase.get(this@OfferDetailsActivity)
                                .findById(offerId)
                                ?.withCurrentParsedStructure()
                                ?: return@runCatching null

                            // Address memory is useful decoration, not a prerequisite for opening the
                            // offer. One malformed historical address must never turn the whole details
                            // screen into an endless spinner.
                            val savedAddresses = runCatching {
                                val meta = CourierMetaDatabase.get(this@OfferDetailsActivity)
                                (offer.pickupAddresses + offer.dropoffAddresses)
                                    .asSequence()
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .distinct()
                                    .mapNotNull { address ->
                                        runCatching { meta.findAddressForDisplayAddress(address) }
                                            .getOrNull()
                                            ?.let { address to it }
                                    }
                                    .toMap()
                            }.onFailure { error ->
                                CaptureEventLog.append(
                                    this@OfferDetailsActivity,
                                    stage = "offer_details_address_lookup_failed",
                                    platform = offer.platform,
                                    message = "Saved-address lookup failed: ${error.javaClass.simpleName}",
                                    dedupeWindowMs = 5_000L,
                                )
                            }.getOrDefault(emptyMap())

                            OfferDetailsData(offer, savedAddresses)
                        }.onFailure { error ->
                            CaptureEventLog.append(
                                this@OfferDetailsActivity,
                                stage = "offer_details_load_failed",
                                message = "Offer #$offerId failed to load: ${error.javaClass.simpleName}",
                                dedupeWindowMs = 5_000L,
                            )
                        }.getOrNull()
                    }
                    // Always leave the loading state. Previously any parser/SQLite/address-memory
                    // exception skipped this assignment and left `Loading offer…` on screen forever.
                    loaded = true
                }

                when {
                    !loaded -> LoadingOfferDetails()
                    data == null -> MissingOffer(onBack = ::finish)
                    else -> OfferDetailsScreen(
                        offer = data!!.offer,
                        savedAddresses = data!!.savedAddresses,
                        onBack = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_OFFER_ID = "offer_id"
    }
}

private data class OfferDetailsData(
    val offer: OfferRecord,
    val savedAddresses: Map<String, AddressRecord>,
)

@Composable
private fun LoadingOfferDetails() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        EmptyBlock("Loading offer…")
    }
}

@Composable
private fun MissingOffer(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Offer details", null, onBack) }
        item { Spacer(Modifier.height(16.dp)); EmptyBlock("This offer is no longer in history.") }
    }
}

private data class OfferStop(
    val tile: String,
    val title: String,
    val address: String?,
    val saved: AddressRecord?,
)

@Composable
private fun OfferDetailsScreen(
    offer: OfferRecord,
    savedAddresses: Map<String, AddressRecord>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalCourierPalette.current
    var rawExpanded by remember { mutableStateOf(false) }

    fun stops(prefix: String, names: List<String>, addresses: List<String>, fallback: String): List<OfferStop> {
        val count = if (addresses.isNotEmpty()) addresses.size else names.size
        return (0 until count).map { index ->
            val address = addresses.getOrNull(index)?.takeIf(String::isNotBlank)
            OfferStop(
                tile = prefix + if (count > 1) "${index + 1}" else "",
                title = names.getOrNull(index)?.takeIf(String::isNotBlank) ?: fallback,
                address = address,
                saved = address?.trim()?.let(savedAddresses::get),
            )
        }
    }
    val pickups = stops("P", OfferPresentation.merchantTitles(offer), offer.pickupAddresses, "Pickup")
    val dropoffs = stops("D", offer.customerNames, offer.dropoffAddresses, "Customer")
    fun open(stop: OfferStop) {
        val saved = stop.saved
        if (saved != null) {
            context.startActivity(
                Intent(context, AddressDetailsActivity::class.java)
                    .putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, saved.id)
            )
        } else if (stop.address != null) {
            context.openAddressInMaps(stop.address)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Offer details", offerDate(offer.capturedAt), onBack) }
        item {
            Spacer(Modifier.height(16.dp))
            OfferHero(offer)
        }

        if (pickups.isNotEmpty()) {
            item { SectionLabel(if (pickups.size == 1) "Pickup" else "Pickups") }
            itemsIndexed(pickups, key = { index, _ -> "p$index" }) { index, stop ->
                OfferStopRow(stop, index, pickups.size, palette.pinBg, palette.pinText, onOpen = { open(stop) }) {
                    stop.address?.let(context::openAddressInMaps)
                }
            }
        }
        if (dropoffs.isNotEmpty()) {
            item { SectionLabel(if (dropoffs.size == 1) "Drop-off" else "Drop-offs") }
            itemsIndexed(dropoffs, key = { index, _ -> "d$index" }) { index, stop ->
                OfferStopRow(stop, index, dropoffs.size, palette.onlineBg, palette.onlineText, onOpen = { open(stop) }) {
                    stop.address?.let(context::openAddressInMaps)
                }
            }
        }
        if (pickups.isEmpty() && dropoffs.isEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                EmptyBlock("Route details were not exposed clearly enough to classify this offer.")
            }
        }

        item { SectionLabel("Captured data") }
        val hasScreenshot = offer.screenshotUri.isNotBlank()
        item {
            LinkRow(
                index = 0,
                count = 2,
                icon = Icons.Rounded.Image,
                title = "Proof screenshot",
                subtitle = if (hasScreenshot) "Open the saved image" else "Not saved for this offer",
                onClick = if (hasScreenshot) ({ openOfferScreenshot(context, offer.screenshotUri) }) else null,
            )
        }
        item {
            ActionRow(index = 1, count = 2, text = if (rawExpanded) "Hide captured text" else "Show captured text") {
                rawExpanded = !rawExpanded
            }
        }
        if (!hasScreenshot) {
            item { Footnote("Screenshot saving was off. OCR, when needed, ran in memory only.") }
        }
        if (rawExpanded) {
            item {
                Spacer(Modifier.height(10.dp))
                GroupedBlock {
                    Text(
                        offer.rawText.ifBlank { "No raw text stored." },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfferHero(offer: OfferRecord) {
    val palette = LocalCourierPalette.current
    val rate = remember(offer) { OfferRowRatePolicy.rate(offer) }
    GroupedBlock {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(offer.platform)
                Spacer(Modifier.size(12.dp))
                Text(
                    OfferPresentation.merchantSummary(offer),
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatOfferMoney(offer),
                    fontFamily = RateNumberFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (rate != null) {
                    RateText(rate.value, rate.unit, rate.grade, valueSize = 28.sp, unitSize = 13.sp)
                    Spacer(Modifier.size(6.dp))
                    VerdictEmoji(rate.grade, size = 20.sp)
                }
            }
            val facts = buildList {
                val realRoute = offer.trustedMarketRouteDistanceMeters
                val platformDistance = offer.distanceMeters?.takeIf { it > 0 }
                platformDistance?.let { add(OfferFact(offer.platform, "%.1f km".format(Locale.US, it / 1000.0))) }
                if (realRoute != null && (platformDistance == null || kotlin.math.abs(realRoute - platformDistance) >= 100)) {
                    add(OfferFact("Calculated", "%.1f km".format(Locale.US, realRoute / 1000.0)))
                }
                offer.deliveryCount?.let { add(OfferFact(if (it == 1) "Delivery" else "Deliveries", it.toString())) }
                offerEta(offer)?.let { add(OfferFact("ETA, min", it.removeSuffix(" min"))) }
            }
            if (facts.isNotEmpty()) {
                facts.chunked(3).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { fact ->
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = palette.miniStatBg) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(fact.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
                                    Text(fact.value, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private data class OfferFact(val label: String, val value: String)

@Composable
private fun OfferStopRow(
    stop: OfferStop,
    index: Int,
    count: Int,
    tileBackground: Color,
    tileForeground: Color,
    onOpen: () -> Unit,
    onMap: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    GroupedRow(
        index = index,
        count = count,
        onClick = if (stop.saved != null || stop.address != null) onOpen else null,
    ) {
        TextTile(stop.tile, tileBackground, tileForeground)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stop.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            stop.address?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (stop.saved != null) {
                Surface(shape = RoundedCornerShape(8.dp), color = palette.pinBg) {
                    Text(
                        "Saved address",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = palette.pinText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (stop.address != null) {
            SquareIconButton(Icons.Rounded.Map, "Open in maps", onMap)
        }
    }
}

private fun openOfferScreenshot(context: android.content.Context, uriString: String) {
    if (uriString.isBlank()) return
    val uri = Uri.parse(uriString)
    val readable = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
    if (!readable) {
        Toast.makeText(
            context,
            "Saved screenshot is no longer available. It may have been removed by your retention setting.",
            Toast.LENGTH_LONG,
        ).show()
        CaptureEventLog.append(
            context,
            stage = "screenshot_not_available",
            message = "Stored screenshot URI is no longer readable",
            dedupeWindowMs = 5_000L,
        )
        return
    }

    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }.onFailure {
        Toast.makeText(context, "No app could open this screenshot.", Toast.LENGTH_SHORT).show()
        CaptureEventLog.append(
            context,
            stage = "ui_error",
            message = "Could not open stored screenshot: ${it.javaClass.simpleName}",
            dedupeWindowMs = 5_000L,
        )
    }
}

private fun offerEta(record: OfferRecord): String? = when {
    record.estimatedMinutesMin != null && record.estimatedMinutesMax != null ->
        "${record.estimatedMinutesMin}–${record.estimatedMinutesMax} min"
    record.estimatedMinutesMin != null -> "${record.estimatedMinutesMin} min"
    else -> null
}

private fun offerMoney(record: OfferRecord): MoneyAmount = MoneyAmount(
    amountMinor = record.priceCents.toLong(),
    currencyCode = record.currencyCode,
    fractionDigits = record.currencyFractionDigits,
)

private fun formatOfferMoney(record: OfferRecord): String {
    val money = offerMoney(record)
    val major = money.major().toPlainString()
    return if (money.currencyCode.equals("EUR", ignoreCase = true)) "€$major" else "${money.currencyCode} $major"
}

private fun offerMoneyPerKm(record: OfferRecord): Double? {
    val distance = record.effectiveRouteDistanceMeters ?: return null
    if (distance <= 0) return null
    return offerMoney(record).major().toDouble() * 1000.0 / distance
}

private fun formatOfferRate(currencyCode: String, rate: Double): String =
    if (currencyCode.equals("EUR", ignoreCase = true)) "€%.2f/km".format(Locale.US, rate)
    else "$currencyCode %.2f/km".format(Locale.US, rate)

private fun offerDate(timestamp: Long): String =
    SimpleDateFormat("EEE, d MMM · HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
