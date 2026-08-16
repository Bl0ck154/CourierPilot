package com.block154.courierpilot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.block154.courierpilot.ui.BrandBlue
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfferDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val offerId = intent.getLongExtra(EXTRA_OFFER_ID, -1L)
        setContent {
            CourierPilotTheme {
                val offer = OfferDatabase.get(this).findById(offerId)?.withCurrentParsedStructure()
                if (offer == null) {
                    MissingOffer(onBack = ::finish)
                } else {
                    OfferDetailsScreen(offer = offer, onBack = ::finish)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OFFER_ID = "offer_id"
    }
}

@Composable
private fun MissingOffer(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Offer not found", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun OfferDetailsScreen(offer: OfferRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val meta = CourierMetaDatabase.get(context)
    var rawExpanded by remember { mutableStateOf(false) }
    val merchant = offer.merchantNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?: offer.restaurant
        ?: "Venue not detected"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Offer details", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(offerDate(offer.capturedAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (offer.platform == "Wolt") Color(0xFFEAF5FF) else Color(0xFFEAF8EE),
                            ) {
                                Text(
                                    offer.platform,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = if (offer.platform == "Wolt") BrandBlue else Success,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(merchant, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.size(14.dp))
                        Text(
                            "€${"%.2f".format(offer.priceCents / 100.0)}",
                            fontSize = 31.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    val facts = buildList {
                        offer.distanceMeters?.let { add("%.2f km".format(it / 1000.0)) }
                        offer.deliveryCount?.let { add("$it ${if (it == 1) "delivery" else "deliveries"}") }
                        offerEta(offer)?.let { add("ETA $it") }
                        offerEurPerKm(offer)?.let { add("€%.2f/km".format(it)) }
                    }
                    if (facts.isNotEmpty()) {
                        Text(
                            facts.joinToString("  ·  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (offer.merchantNames.isNotEmpty() || offer.pickupAddresses.isNotEmpty()) {
            item { OfferSection("Pickup", "Venues and pickup addresses") }
            // Physical route stops are address-led. Extra summary/name nodes must not fabricate P2/P3
            // cards when Accessibility exposed only one canonical pickup address.
            val count = if (offer.pickupAddresses.isNotEmpty()) offer.pickupAddresses.size else offer.merchantNames.size
            items(count) { index ->
                val address = offer.pickupAddresses.getOrNull(index)
                val saved = address?.let(meta::findAddressForDisplayAddress)
                OfferStopCard(
                    badge = "P${if (count > 1) index + 1 else ""}",
                    title = offer.merchantNames.getOrNull(index) ?: "Pickup",
                    address = address,
                    accent = BrandBlue,
                    savedAddress = saved,
                    onMap = { if (!address.isNullOrBlank()) context.openAddressInMaps(address) },
                    onSavedAddress = {
                        saved?.let {
                            context.startActivity(
                                Intent(context, AddressDetailsActivity::class.java)
                                    .putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, it.id)
                            )
                        }
                    },
                )
            }
        }

        if (offer.customerNames.isNotEmpty() || offer.dropoffAddresses.isNotEmpty()) {
            item { OfferSection("Drop-off", "Customer and destination") }
            // As above, one canonical destination is one D card. A generic fallback `Customer`
            // must never create a second card beside the named customer for the same address.
            val count = if (offer.dropoffAddresses.isNotEmpty()) offer.dropoffAddresses.size else offer.customerNames.size
            items(count) { index ->
                val address = offer.dropoffAddresses.getOrNull(index)
                val saved = address?.let(meta::findAddressForDisplayAddress)
                OfferStopCard(
                    badge = "D${if (count > 1) index + 1 else ""}",
                    title = offer.customerNames.getOrNull(index) ?: "Customer",
                    address = address,
                    accent = Success,
                    savedAddress = saved,
                    onMap = { if (!address.isNullOrBlank()) context.openAddressInMaps(address) },
                    onSavedAddress = {
                        saved?.let {
                            context.startActivity(
                                Intent(context, AddressDetailsActivity::class.java)
                                    .putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, it.id)
                            )
                        }
                    },
                )
            }
        }

        if (offer.customerNames.isEmpty() && offer.dropoffAddresses.isEmpty() && offer.pickupAddresses.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Text(
                        "Route details were not exposed clearly enough to classify this offer.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        item { OfferSection("Original", "Screenshot and captured text") }
        item {
            FilledTonalButton(
                onClick = { openOfferScreenshot(context, offer.screenshotUri) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open original screenshot")
            }
        }
        item {
            TextButton(onClick = { rawExpanded = !rawExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (rawExpanded) "Hide captured text" else "Show captured text")
            }
        }
        if (rawExpanded) {
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Text(
                        offer.rawText.ifBlank { "No raw text stored." },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfferStopCard(
    badge: String,
    title: String,
    address: String?,
    accent: Color,
    savedAddress: AddressRecord?,
    onMap: () -> Unit,
    onSavedAddress: () -> Unit,
) {
    val openPrimary = if (savedAddress != null) onSavedAddress else onMap
    Card(onClick = openPrimary, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.10f)) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                address?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
                }
                if (savedAddress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Saved address", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (!address.isNullOrBlank()) {
                IconButton(onClick = onMap) {
                    Icon(Icons.Rounded.Map, contentDescription = "Open in maps")
                }
            }
            if (savedAddress != null) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun OfferSection(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private fun openOfferScreenshot(context: android.content.Context, uriString: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }.onFailure {
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

private fun offerEurPerKm(record: OfferRecord): Double? {
    val distance = record.distanceMeters ?: return null
    if (distance <= 0) return null
    return record.priceCents * 10.0 / distance
}

private fun offerDate(timestamp: Long): String =
    SimpleDateFormat("EEE, d MMM · HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
