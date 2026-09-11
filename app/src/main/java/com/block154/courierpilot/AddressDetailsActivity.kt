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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddressDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val addressId = intent.getLongExtra(EXTRA_ADDRESS_ID, -1L)
        val initialNotificationCodes = intent.getStringArrayListExtra(EXTRA_ACCESS_CODES)
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        setContent {
            CourierPilotTheme {
                var loaded by remember(addressId) { mutableStateOf(false) }
                var data by remember(addressId) { mutableStateOf<AddressDetailsData?>(null) }
                var notificationCodes by remember(addressId) { mutableStateOf(initialNotificationCodes) }
                var showDeleteConfirmation by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf(false) }
                var codePendingDelete by remember { mutableStateOf<AddressCodeSummary?>(null) }
                var deletingCode by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(addressId) {
                    data = withContext(Dispatchers.IO) { loadAddressDetails(this@AddressDetailsActivity, addressId) }
                    loaded = true
                }

                when {
                    !loaded -> LoadingAddressDetails()
                    data == null -> MissingAddress(onBack = ::finish)
                    else -> {
                        val current = data!!
                        AddressDetailsScreen(
                            address = current.address,
                            codes = current.codes,
                            customers = current.customers,
                            notificationCodes = notificationCodes,
                            notificationSource = AddressMemoryUiProjection.findAccessHintSource(
                                current.observations,
                                notificationCodes,
                            ),
                            onBack = ::finish,
                            onMap = { openAddressInMaps(current.address.displayAddress) },
                            onDelete = { showDeleteConfirmation = true },
                            onDeleteCode = { codePendingDelete = it },
                        )

                        if (showDeleteConfirmation) {
                            AlertDialog(
                                onDismissRequest = { if (!deleting) showDeleteConfirmation = false },
                                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                title = { Text("Delete address?") },
                                text = {
                                    Text(
                                        "${current.address.displayAddress}\n\nThis also removes its saved screen history, customer names and access hints from CourierPilot."
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            if (deleting) return@TextButton
                                            deleting = true
                                            scope.launch {
                                                val deleted = withContext(Dispatchers.IO) {
                                                    AddressDeletion.delete(
                                                        this@AddressDetailsActivity,
                                                        current.meta,
                                                        current.address,
                                                    )
                                                }
                                                deleting = false
                                                showDeleteConfirmation = false
                                                if (deleted) finish()
                                            }
                                        },
                                        enabled = !deleting,
                                    ) {
                                        Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showDeleteConfirmation = false },
                                        enabled = !deleting,
                                    ) {
                                        Text("Cancel")
                                    }
                                },
                            )
                        }

                        codePendingDelete?.let { code ->
                            AlertDialog(
                                onDismissRequest = { if (!deletingCode) codePendingDelete = null },
                                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                title = { Text("Delete saved access hint?") },
                                text = {
                                    Text(
                                        "${code.code} · ${current.address.displayAddress}\n\n" +
                                            "CourierPilot will stop using this code as a historical reminder. " +
                                            "The raw delivery-screen history is kept for source attribution. " +
                                            "If the same code appears again on a future delivery screen, it can be learned again."
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            if (deletingCode) return@TextButton
                                            deletingCode = true
                                            scope.launch {
                                                val deleted = withContext(Dispatchers.IO) {
                                                    AccessCodeDeletion.delete(
                                                        database = current.meta,
                                                        buildingKey = current.address.buildingKey,
                                                        code = code.code,
                                                    )
                                                }
                                                if (deleted > 0) {
                                                    AccessCodeSuggestions.clear(this@AddressDetailsActivity)
                                                    notificationCodes = notificationCodes.filterNot { notificationCode ->
                                                        AccessCodeDeletion.equivalent(notificationCode, code.code)
                                                    }
                                                    data = withContext(Dispatchers.IO) {
                                                        loadAddressDetails(this@AddressDetailsActivity, addressId)
                                                    }
                                                }
                                                deletingCode = false
                                                codePendingDelete = null
                                            }
                                        },
                                        enabled = !deletingCode,
                                    ) {
                                        Text(
                                            if (deletingCode) "Deleting…" else "Delete hint",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { codePendingDelete = null },
                                        enabled = !deletingCode,
                                    ) {
                                        Text("Cancel")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS_ID = "address_id"
        const val EXTRA_ACCESS_CODES = "access_codes"
    }
}

private data class AddressDetailsData(
    val meta: CourierMetaDatabase,
    val address: AddressRecord,
    val codes: List<AddressCodeSummary>,
    val customers: List<AddressCustomerSummary>,
    val observations: List<AddressObservationRecord>,
)

private fun loadAddressDetails(
    context: android.content.Context,
    addressId: Long,
): AddressDetailsData? {
    val meta = CourierMetaDatabase.get(context)
    val address = meta.findAddressById(addressId) ?: return null

    // Raw screen history remains durable internal evidence for parsing and source attribution, but
    // the ordinary address screen no longer renders that implementation detail as a timeline.
    val observations = meta.observationsForAddress(address.id, limit = 200)
    val rawCustomers = meta.entitiesForAddress(address.id, CourierMetaDatabase.ENTITY_CUSTOMER, limit = 300)
    val rawCodes = meta.codesForBuilding(address.buildingKey, limit = 20)

    return AddressDetailsData(
        meta = meta,
        address = address,
        codes = AddressMemoryUiProjection.summarizeCodes(rawCodes, observations),
        customers = AddressMemoryUiProjection.summarizeCustomers(rawCustomers),
        observations = observations,
    )
}

@Composable
private fun LoadingAddressDetails() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Loading address…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    codes: List<AddressCodeSummary>,
    customers: List<AddressCustomerSummary>,
    notificationCodes: List<String>,
    notificationSource: AddressObservationRecord?,
    onBack: () -> Unit,
    onMap: () -> Unit,
    onDelete: () -> Unit,
    onDeleteCode: (AddressCodeSummary) -> Unit,
) {
    var customersExpanded by remember(address.id) { mutableStateOf(false) }
    val visibleCustomers = if (customersExpanded) customers else customers.take(COLLAPSED_CUSTOMER_COUNT)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Address memory", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Saved locally from delivery screens",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete address",
                        tint = MaterialTheme.colorScheme.error,
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
                        "${address.platform} · last captured ${addressDate(address.lastSeenAt)}",
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

        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AddressMetric("Captured", address.seenCount.toString(), Modifier.weight(1f))
                    AddressMetric("Customers", customers.size.toString(), Modifier.weight(1f))
                    AddressMetric("Access hints", codes.size.toString(), Modifier.weight(1f))
                }
            }
        }

        if (notificationCodes.isNotEmpty()) {
            item {
                AddressSection(
                    "Why this reminder appeared",
                    "Source of the saved access hint from the notification",
                )
            }
            item {
                AccessHintSourceCard(
                    codes = notificationCodes,
                    source = notificationSource,
                )
            }
        }

        address.latestDetails?.takeIf(String::isNotBlank)?.let { details ->
            item { AddressSection("Latest delivery info", "Newest parsed delivery details") }
            item { AddressInfoCard(details) }
        }

        if (codes.isNotEmpty()) {
            item {
                AddressSection(
                    "Possible access hints",
                    "Derived from saved screens. Remove an outdated hint with the trash button.",
                )
            }
            items(codes, key = { "code-${it.key}" }) { code ->
                CompactMemoryRow(
                    title = code.code,
                    subtitle = "${code.platforms.joinToString(" + ")} · seen ${code.seenCount}× · ${addressDate(code.lastSeenAt)}",
                    titleSize = 18,
                    onDelete = { onDeleteCode(code) },
                )
            }
        }

        item {
            AddressSection(
                "Customers · ${customers.size}",
                "Equivalent names from Wolt/Bolt are grouped for display without deleting source rows",
            )
        }
        if (customers.isEmpty()) {
            item { AddressInfoCard("No customer names saved for this building yet.") }
        } else {
            items(visibleCustomers, key = { "customer-${it.key}" }) { customer ->
                CompactMemoryRow(
                    title = customer.displayName,
                    subtitle = "${customer.platforms.joinToString(" + ")} · captured ${customer.seenCount}× · last ${addressDate(customer.lastSeenAt)}",
                )
            }
            if (customers.size > COLLAPSED_CUSTOMER_COUNT) {
                item {
                    ExpandCollapseButton(
                        expanded = customersExpanded,
                        collapsedLabel = "Show all ${customers.size} customers",
                        onClick = { customersExpanded = !customersExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessHintSourceCard(
    codes: List<String>,
    source: AddressObservationRecord?,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Possible code: ${codes.joinToString(" / ")}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            if (source == null) {
                Text(
                    "This reminder came from saved address history, but the exact originating screen is outside the recent local source window.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    "${source.platform} · captured ${addressDate(source.seenAt)}",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 11.sp,
                )
                source.customerName?.takeIf(String::isNotBlank)?.let {
                    Text(it, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
                Text(
                    accessHintSourceExcerpt(source, codes),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun accessHintSourceExcerpt(
    source: AddressObservationRecord,
    codes: List<String>,
): String {
    val lines = source.rawText
        .lineSequence()
        .map { it.trim() }
        .filter(String::isNotEmpty)
        .toList()
    if (lines.isEmpty()) return source.detailsText.orEmpty()

    val index = lines.indexOfFirst { line ->
        codes.any { code -> AccessCodeHintPolicy.isAlreadyVisible(line, code) }
    }
    if (index < 0) {
        return source.detailsText?.takeIf(String::isNotBlank) ?: lines.take(8).joinToString("\n")
    }

    val start = (index - 3).coerceAtLeast(0)
    val end = (index + 4).coerceAtMost(lines.size)
    return lines.subList(start, end).joinToString("\n")
}

@Composable
private fun AddressMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun AddressSection(title: String, subtitle: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun CompactMemoryRow(
    title: String,
    subtitle: String,
    titleSize: Int = 15,
    onDelete: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = titleSize.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete saved access hint",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandCollapseButton(
    expanded: Boolean,
    collapsedLabel: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Show less" else collapsedLabel)
    }
}

@Composable
private fun AddressInfoCard(body: String) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

private fun addressDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))

private const val COLLAPSED_CUSTOMER_COUNT = 5
