package dev.sergio.lifeinsights.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import dev.sergio.lifeinsights.data.Scale
import dev.sergio.lifeinsights.data.db.SleepSource
import dev.sergio.lifeinsights.data.db.CheckInWithTags
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import java.time.format.DateTimeFormatter

@Composable
fun CheckInScreen(viewModel: CheckInViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val usageAccessGranted by viewModel.usageAccessGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshUsageAccess()
        onPauseOrDispose { }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            state.day.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (form.editingId != 0L) "Editing an earlier entry" else "How is it going?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        ScaleRow(
            title = "Mood",
            lowLabel = "very low",
            highLabel = "very good",
            value = form.mood,
            valueLabel = form.mood?.let(Scale::moodLabel),
            onSelect = viewModel::setMood,
        )

        Spacer(Modifier.height(24.dp))
        ScaleRow(
            title = "Energy",
            lowLabel = "drained",
            highLabel = "wired",
            value = form.energy,
            valueLabel = form.energy?.let(Scale::energyLabel),
            onSelect = viewModel::setEnergy,
        )

        if (state.availableTags.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Anything worth noting?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.availableTags.forEach { tag ->
                    FilterChip(
                        selected = tag in form.selectedTags,
                        onClick = { viewModel.toggleTag(tag) },
                        label = { Text(tag) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = form.note,
            onValueChange = viewModel::setNote,
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = viewModel::save,
                enabled = form.canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (form.editingId != 0L) "Update" else "Save check-in")
            }
            if (form.editingId != 0L) {
                TextButton(onClick = viewModel::cancelEdit) { Text("Cancel") }
            }
        }

        if (state.entries.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("Today's entries", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            state.entries.forEach { entry ->
                EntryRow(entry, onEdit = { viewModel.edit(entry) }, onDelete = {
                    viewModel.delete(entry.checkIn.id)
                })
            }
        }

        Spacer(Modifier.height(28.dp))
        if (!usageAccessGranted) {
            UsageAccessCard(onGrant = { context.startActivity(viewModel.usageAccessSettingsIntent()) })
            Spacer(Modifier.height(16.dp))
        }
        AutoCollectedCard(state.metrics)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ScaleRow(
    title: String,
    lowLabel: String,
    highLabel: String,
    value: Int?,
    valueLabel: String?,
    onSelect: (Int) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                valueLabel ?: "not set",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Scale.VALUES.forEach { point ->
                val selected = value == point
                FilledTonalButton(
                    onClick = { onSelect(point) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = if (selected) {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                    },
                ) {
                    Text(if (point > 0) "+$point" else "$point")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                lowLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                highLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: CheckInWithTags, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Mood ${signed(entry.checkIn.mood)} - energy ${signed(entry.checkIn.energy)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val extras = buildList {
                if (entry.tagNames.isNotEmpty()) add(entry.tagNames.sorted().joinToString(", "))
                entry.checkIn.note?.let { add(it) }
            }
            if (extras.isNotEmpty()) {
                Text(
                    extras.joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete entry")
        }
    }
}

/**
 * Usage access cannot be requested with a normal permission dialog, so this explains what the app
 * wants and why before sending the user into a Settings screen that would otherwise be baffling.
 */
@Composable
private fun UsageAccessCard(onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Track screen time automatically", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "With usage access, the app records screen time, which apps you spend it in, phone " +
                    "pickups, and an estimate of when you slept, all without you logging anything. " +
                    "Android only lets you grant this from its own Settings screen, and it stays " +
                    "on this device like everything else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant) { Text("Open Settings") }
        }
    }
}

@Composable
private fun AutoCollectedCard(metrics: DailyMetricEntity?) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Collected automatically", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val rows = buildList {
                metrics?.sleepMinutes?.let {
                    // Never present an inferred figure as if it were measured.
                    val label = when (metrics.sleepSource) {
                        SleepSource.PROXY.name -> "Sleep (estimated)"
                        SleepSource.MANUAL.name -> "Sleep (you set this)"
                        else -> "Sleep"
                    }
                    add(label to formatMinutes(it))
                }
                metrics?.screenMinutes?.let { add("Screen time" to formatMinutes(it)) }
                metrics?.socialMediaMinutes?.let { add("Social media" to formatMinutes(it)) }
                metrics?.unlockCount?.let { add("Pickups" to it.toString()) }
                metrics?.steps?.let { add("Steps" to it.toInt().toString()) }
                metrics?.exerciseMinutes?.let { add("Exercise" to formatMinutes(it)) }
            }
            if (rows.isEmpty()) {
                Text(
                    "Nothing yet for today. Screen time and the sleep estimate appear once usage " +
                        "access is granted and there is a day of events to summarise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun signed(value: Int) = if (value > 0) "+$value" else "$value"

internal fun formatMinutes(minutes: Double): String {
    val total = minutes.toInt()
    val hours = total / 60
    val rest = total % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}
