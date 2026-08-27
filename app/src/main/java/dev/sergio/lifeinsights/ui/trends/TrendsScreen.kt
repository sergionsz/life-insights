package dev.sergio.lifeinsights.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.insights.MetricSeries
import dev.sergio.lifeinsights.insights.SeriesOps
import dev.sergio.lifeinsights.ui.components.ChartSeries
import dev.sergio.lifeinsights.ui.components.LineChart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TrendsState(
    val rangeDays: Int = 30,
    val outcomes: List<MetricSeries> = emptyList(),
    val predictors: List<MetricSeries> = emptyList(),
    val loading: Boolean = true,
)

class TrendsViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val _state = MutableStateFlow(TrendsState())
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setRange(days: Int) {
        _state.value = _state.value.copy(rangeDays = days)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                outcomes = repository.outcomeSeries(),
                predictors = repository.predictorSeries(),
                loading = false,
            )
        }
    }
}

@Composable
fun TrendsScreen(viewModel: TrendsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val to = LocalDate.now()
    val from = to.minusDays((state.rangeDays - 1).toLong())

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Trends", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                FilterChip(
                    selected = state.rangeDays == days,
                    onClick = { viewModel.setRange(days) },
                    label = { Text("${days}d") },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        val mood = state.outcomes.firstOrNull { it.key == "mood" }
        val energy = state.outcomes.firstOrNull { it.key == "energy" }

        ChartCard(
            title = "Mood and energy",
            subtitle = "Daily values on the -3 to +3 scale.",
        ) {
            LineChart(
                series = listOfNotNull(
                    mood?.let { ChartSeries("Mood", MOOD_COLOUR, it.values) },
                    energy?.let { ChartSeries("Energy", ENERGY_COLOUR, it.values) },
                ),
                from = from,
                to = to,
                yRange = -3.0..3.0,
            )
        }

        Spacer(Modifier.height(16.dp))
        ChartCard(
            title = "Seven-day averages",
            subtitle = "Smoothed for reading the shape of the last few weeks. Correlations on the " +
                "Insights screen always use the raw daily values, never these.",
        ) {
            LineChart(
                series = listOfNotNull(
                    mood?.let {
                        ChartSeries("Mood", MOOD_COLOUR, SeriesOps.trailingRollingMean(it, 7))
                    },
                    energy?.let {
                        ChartSeries("Energy", ENERGY_COLOUR, SeriesOps.trailingRollingMean(it, 7))
                    },
                ),
                from = from,
                to = to,
                yRange = -3.0..3.0,
            )
        }

        state.predictors.filter { it.key != "sleep_debt_3d" }.forEach { metric ->
            Spacer(Modifier.height(16.dp))
            ChartCard(title = metric.label, subtitle = null) {
                LineChart(
                    series = listOf(ChartSeries(metric.label, METRIC_COLOUR, metric.values)),
                    from = from,
                    to = to,
                )
            }
        }

        if (state.predictors.isEmpty() && !state.loading) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Automatic metrics (sleep, screen time, activity) appear here once those sources " +
                    "are connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private val MOOD_COLOUR = Color(0xFF4C8C6B)
private val ENERGY_COLOUR = Color(0xFFCC8A3E)
private val METRIC_COLOUR = Color(0xFF4A7FA5)
