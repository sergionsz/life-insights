package dev.sergio.lifeinsights.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sergio.lifeinsights.data.AppSettings
import dev.sergio.lifeinsights.data.SettingsRepository
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.data.export.DataExporter
import dev.sergio.lifeinsights.work.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(
    private val repository: TrackerRepository,
    private val settingsRepository: SettingsRepository,
    private val exporter: DataExporter,
    private val database: dev.sergio.lifeinsights.data.db.AppDatabase,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val tags: StateFlow<List<String>> = repository.observeTags()
        .map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setReminderEnabled(enabled)
            if (enabled) {
                ReminderScheduler.schedule(context, settings.value.reminderTime)
            } else {
                ReminderScheduler.cancel(context)
            }
        }
    }

    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setReminderTime(hour, minute)
            if (settings.value.reminderEnabled) {
                ReminderScheduler.schedule(context, java.time.LocalTime.of(hour, minute))
            }
        }
    }

    fun addTag(name: String) {
        viewModelScope.launch { repository.addTag(name) }
    }

    fun removeTag(name: String) {
        viewModelScope.launch { repository.removeTag(name) }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            repository.deleteAllData()
            _message.value = "All check-ins and metrics deleted."
        }
    }

    /** Writes the export to cache and hands back a share intent; nothing leaves the device unbidden. */
    fun export(context: Context, asCsv: Boolean, onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.Default) {
                if (asCsv) exporter.toCsv() else exporter.toJson()
            }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, if (asCsv) "life-insights.csv" else "life-insights.json")
            file.writeText(content)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            onReady(
                Intent(Intent.ACTION_SEND).apply {
                    type = if (asCsv) "text/csv" else "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Debug builds only: fills the database with a synthetic history of known structure. */
    fun seedDemoData() {
        viewModelScope.launch {
            repository.deleteAllData()
            withContext(Dispatchers.Default) {
                dev.sergio.lifeinsights.data.DebugSeed.seed(database, days = 120)
            }
            _message.value = "Seeded 121 days of synthetic data. Sleep two nights earlier drives " +
                "energy, weekends lift mood, alcohol lowers next-day energy, screen time is " +
                "unrelated to anything."
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var newTag by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(20.dp))
        SectionCard("Reminder") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Daily check-in reminder", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = settings.reminderEnabled,
                    onCheckedChange = { viewModel.setReminderEnabled(context, it) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Time", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(18, 19, 20, 21, 22).forEach { hour ->
                    val selected = settings.reminderHour == hour
                    OutlinedButton(onClick = { viewModel.setReminderTime(context, hour, 0) }) {
                        Text(if (selected) "• %02d:00".format(hour) else "%02d:00".format(hour))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Tags") {
            Text(
                "The chips offered on the check-in screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.removeTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Text("×") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("Add a tag") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        viewModel.addTag(newTag)
                        newTag = ""
                    },
                    enabled = newTag.isNotBlank(),
                ) { Text("Add") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Your data") {
            Text(
                "Everything stays on this device. There is no account, no server and no analytics. " +
                    "Nothing is transmitted anywhere unless you share an export yourself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    viewModel.export(context, asCsv = false) { intent ->
                        context.startActivity(Intent.createChooser(intent, "Export JSON"))
                    }
                }) { Text("Export JSON") }
                OutlinedButton(onClick = {
                    viewModel.export(context, asCsv = true) { intent ->
                        context.startActivity(Intent.createChooser(intent, "Export CSV"))
                    }
                }) { Text("Export CSV") }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirmDelete = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Delete all data") }
        }

        if (dev.sergio.lifeinsights.BuildConfig.DEBUG) {
            Spacer(Modifier.height(16.dp))
            SectionCard("Developer") {
                Text(
                    "Replaces all data with a synthetic 120-day history whose true structure is " +
                        "known, so the Insights screen can be checked against the right answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::seedDemoData) { Text("Seed demo data") }
            }
        }

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "Every check-in and every daily metric will be removed from this device. " +
                        "This cannot be undone, and months of history cannot be reconstructed. " +
                        "Export first if you might want the data later.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEverything()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
