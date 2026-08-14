package com.block154.courierpilot

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
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddressDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val addressId = intent.getLongExtra(EXTRA_ADDRESS_ID, -1L)
        setContent {
            CourierPilotTheme {
                val meta = CourierMetaDatabase.get(this)
                val address = meta.findAddressById(addressId)
                if (address == null) {
                    MissingAddress(onBack = ::finish)
                } else {
                    AddressDetailsScreen(
                        address = address,
                        codes = meta.codesForBuilding(address.buildingKey, limit = 20),
                        observations = meta.observationsForAddress(address.id, limit = 30),
                        onBack = ::finish,
                        onMap = { openAddressInMaps(address.displayAddress) },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS_ID = "address_id"
    }
}

@Composable
private fun MissingAddress(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Address not found", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun AddressDetailsScreen(
    address: AddressRecord,
    codes: List<AccessCodeRecord>,
    observations: List<AddressObservationRecord>,
    onBack: () -> Unit,
    onMap: () -> Unit,
) {
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
                    Text("Address", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Local delivery memory",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            Card(
                onClick = onMap,
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Place, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text(address.displayAddress, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "${address.platform} · seen ${address.seenCount}× · last ${addressDate(address.lastSeenAt)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    FilledTonalButton(onClick = onMap, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open in maps")
                    }
                }
            }
        }

        if (codes.isNotEmpty()) {
            item { AddressSection("Access", "Door / intercom codes learned for this building") }
            items(codes, key = { "code-${it.id}" }) { code ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(code.code, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(code.platform, fontWeight = FontWeight.Medium)
                            Text(
                                "seen ${code.seenCount}× · ${addressDate(code.lastSeenAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        address.latestCustomerName?.takeIf(String::isNotBlank)?.let { customer ->
            item {
                AddressInfoCard("Latest customer", customer)
            }
        }

        address.latestDetails?.takeIf(String::isNotBlank)?.let { details ->
            item { AddressSection("Latest details", "Captured around this address") }
            item { AddressInfoCard(null, details) }
        }

        item { AddressSection("Visit history", "Recent local observations") }
        if (observations.isEmpty()) {
            item { AddressInfoCard(null, "No detailed observations saved yet.") }
        } else {
            items(observations, key = { "observation-${it.id}" }) { observation ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(observation.platform, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(addressDate(observation.seenAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        observation.customerName?.takeIf(String::isNotBlank)?.let {
                            Text(it, fontWeight = FontWeight.Medium)
                        }
                        val body = observation.detailsText?.takeIf(String::isNotBlank) ?: observation.rawText
                        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 12)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressSection(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun AddressInfoCard(title: String?, body: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            title?.let {
                Text(it, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

private fun addressDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
