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
import androidx.compose.foundation.lazy.itemsIndexed
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.EmptyBlock
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.IconTile
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
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
                var customerPendingDelete by remember { mutableStateOf<AddressCustomerSummary?>(null) }
                var deletingCustomer by remember { mutableStateOf(false) }
                var showLatestDetailsDeleteConfirmation by remember { mutableStateOf(false) }
                var deletingLatestDetails by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                suspend fun reload() {
                    data = withContext(Dispatchers.IO) {
                        loadAddressDetails(this@AddressDetailsActivity, addressId)
                    }
                }

                LaunchedEffect(addressId) {
                    reload()
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
                            onDeleteCustomer = { customerPendingDelete = it },
                            onDeleteLatestDetails = { showLatestDetailsDeleteConfirmation = true },
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
                                                    reload()
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

                        customerPendingDelete?.let { customer ->
                            AlertDialog(
                                onDismissRequest = { if (!deletingCustomer) customerPendingDelete = null },
                                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                title = { Text("Delete saved customer?") },
                                text = {
                                    Text(
                                        "${customer.displayName} · ${current.address.displayAddress}\n\n" +
                                            "This removes the remembered customer name from this address. " +
                                            "Raw delivery-screen history is kept. If the name appears again on a future delivery, it can be learned again."
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            if (deletingCustomer) return@TextButton
                                            deletingCustomer = true
                                            scope.launch {
                                                val deleted = withContext(Dispatchers.IO) {
                                                    AddressMemoryEntryDeletion.deleteCustomer(
                                                        database = current.meta,
                                                        addressId = current.address.id,
                                                        customerKey = customer.key,
                                                    )
                                                }
                                                if (deleted > 0) reload()
                                                deletingCustomer = false
                                                customerPendingDelete = null
                                            }
                                        },
                                        enabled = !deletingCustomer,
                                    ) {
                                        Text(
                                            if (deletingCustomer) "Deleting…" else "Delete customer",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { customerPendingDelete = null },
                                        enabled = !deletingCustomer,
                                    ) {
                                        Text("Cancel")
                                    }
                                },
                            )
                        }

                        if (showLatestDetailsDeleteConfirmation) {
                            AlertDialog(
                                onDismissRequest = {
                                    if (!deletingLatestDetails) showLatestDetailsDeleteConfirmation = false
                                },
                                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                title = { Text("Clear latest delivery info?") },
                                text = {
                                    Text(
                                        "This removes the current parsed delivery-info summary for ${current.address.displayAddress}. " +
                                            "Raw delivery-screen history stays saved, and a future delivery can populate this field again."
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            if (deletingLatestDetails) return@TextButton
                                            deletingLatestDetails = true
                                            scope.launch {
                                                val deleted = withContext(Dispatchers.IO) {
                                                    AddressMemoryEntryDeletion.clearLatestDeliveryInfo(
                                                        database = current.meta,
                                                        addressId = current.address.id,
                                                    )
                                                }
                                                if (deleted) reload()
                                                deletingLatestDetails = false
                                                showLatestDetailsDeleteConfirmation = false
                                            }
                                        },
                                        enabled = !deletingLatestDetails,
                                    ) {
                                        Text(
                                            if (deletingLatestDetails) "Clearing…" else "Clear info",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showLatestDetailsDeleteConfirmation = false },
                                        enabled = !deletingLatestDetails,
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        EmptyBlock("Loading address…")
    }
}

@Composable
private fun MissingAddress(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Address", null, onBack) }
        item { Spacer(Modifier.height(16.dp)); EmptyBlock("This address is no longer saved.") }
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
    onDeleteCustomer: (AddressCustomerSummary) -> Unit,
    onDeleteLatestDetails: () -> Unit,
) {
    var customersExpanded by remember(address.id) { mutableStateOf(false) }
    val visibleCustomers = if (customersExpanded) customers else customers.take(COLLAPSED_CUSTOMER_COUNT)
    val hasMoreCustomers = customers.size > COLLAPSED_CUSTOMER_COUNT
    val palette = LocalCourierPalette.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Address", "Saved from delivery screens", onBack) }

        item {
            Spacer(Modifier.height(16.dp))
            GroupedBlock {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Place)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(address.displayAddress, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text(
                                "${address.platform} · last ${addressDate(address.lastSeenAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddressMetric("Visits", address.seenCount.toString(), Modifier.weight(1f))
                        AddressMetric("Customers", customers.size.toString(), Modifier.weight(1f))
                        AddressMetric("Codes", codes.size.toString(), Modifier.weight(1f))
                    }
                    Surface(
                        onClick = onMap,
                        shape = RoundedCornerShape(14.dp),
                        color = palette.pinBg,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Map, contentDescription = null, tint = palette.pinText, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Open in maps", color = palette.pinText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        if (notificationCodes.isNotEmpty()) {
            item { SectionLabel("Why this reminder appeared") }
            item { AccessHintSourceBlock(codes = notificationCodes, source = notificationSource) }
        }

        address.latestDetails?.takeIf(String::isNotBlank)?.let { details ->
            item { SectionLabel("Latest delivery info") }
            item {
                GroupedRow(index = 0, count = 1) {
                    Text(
                        details,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    )
                    DeleteButton("Clear latest delivery info", onDeleteLatestDetails)
                }
            }
            item { Footnote("Clearing it keeps the raw delivery-screen history.") }
        }

        if (codes.isNotEmpty()) {
            item { SectionLabel("Access codes") }
            itemsIndexed(codes, key = { _, code -> "code-${code.key}" }) { index, code ->
                GroupedRow(index = index, count = codes.size) {
                    Surface(shape = RoundedCornerShape(10.dp), color = palette.codeBg) {
                        Text(
                            "🔑 ${code.code}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = palette.codeText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                        )
                    }
                    Text(
                        "${code.platforms.joinToString(" + ")} · seen ${code.seenCount}× · ${addressDate(code.lastSeenAt)}",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DeleteButton("Delete saved access hint") { onDeleteCode(code) }
                }
            }
            item { Footnote("Found on saved delivery screens. Remove an outdated code with the bin.") }
        }

        item { SectionLabel("Customers · ${customers.size}") }
        if (customers.isEmpty()) {
            item { EmptyBlock("No customer names saved for this building yet.") }
        } else {
            val rowCount = visibleCustomers.size + if (hasMoreCustomers) 1 else 0
            itemsIndexed(visibleCustomers, key = { _, customer -> "customer-${customer.key}" }) { index, customer ->
                GroupedRow(index = index, count = rowCount) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(customer.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "${customer.platforms.joinToString(" + ")} · ${customer.seenCount}× · last ${addressDate(customer.lastSeenAt)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DeleteButton("Delete saved customer") { onDeleteCustomer(customer) }
                }
            }
            if (hasMoreCustomers) {
                item {
                    ActionRow(
                        index = rowCount - 1,
                        count = rowCount,
                        text = if (customersExpanded) "Show less" else "Show all ${customers.size} customers",
                    ) { customersExpanded = !customersExpanded }
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }
        item {
            ActionRow(index = 0, count = 1, text = "Delete address", color = MaterialTheme.colorScheme.error, onClick = onDelete)
        }
        item { Footnote("Removes this address with its customers, codes and saved screens from this phone.") }
    }
}

@Composable
private fun AccessHintSourceBlock(
    codes: List<String>,
    source: AddressObservationRecord?,
) {
    val palette = LocalCourierPalette.current
    GroupedBlock {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = palette.codeBg) {
                Text(
                    "🔑 ${codes.joinToString(" / ")}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = palette.codeText,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                )
            }
            if (source == null) {
                Text(
                    "This reminder came from saved address history, but the exact originating screen is outside the recent local source window.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                )
            } else {
                Text(
                    "${source.platform} · captured ${addressDate(source.seenAt)}" +
                        (source.customerName?.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    accessHintSourceExcerpt(source, codes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
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
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = LocalCourierPalette.current.miniStatBg) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
private fun DeleteButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun addressDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))

private const val COLLAPSED_CUSTOMER_COUNT = 5
