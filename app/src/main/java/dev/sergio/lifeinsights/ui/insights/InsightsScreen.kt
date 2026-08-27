package dev.sergio.lifeinsights.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.insights.Confidence
import dev.sergio.lifeinsights.insights.Insight
import dev.sergio.lifeinsights.insights.InsightsEngine
import dev.sergio.lifeinsights.insights.InsightsInput
import dev.sergio.lifeinsights.insights.InsightsReport
import dev.sergio.lifeinsights.insights.PendingAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InsightsState(
    val report: InsightsReport? = null,
    val loading: Boolean = true,
)

class InsightsViewModel(
    private val repository: TrackerRepository,
    private val engine: InsightsEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            // The bootstrap and null resampling run thousands of correlations; keep it off the
            // main thread.
            val report = withContext(Dispatchers.Default) {
                engine.analyse(
                    InsightsInput(
                        outcomes = repository.outcomeSeries(),
                        predictors = repository.predictorSeries(),
                        tagDays = repository.tagDays(),
                    ),
                )
            }
            _state.value = InsightsState(report = report, loading = false)
        }
    }
}

@Composable
fun InsightsScreen(viewModel: InsightsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Patterns in your own data. These describe what tends to go together, which is not the " +
                "same as what causes what.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        val report = state.report
        when {
            state.loading && report == null -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            report == null -> Unit

            else -> {
                report.insights.forEach { insight ->
                    InsightCard(insight)
                    Spacer(Modifier.height(12.dp))
                }

                if (report.insights.isEmpty() && report.pending.isEmpty()) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Nothing stands out yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Enough data has been collected to look, and no relationship was " +
                                    "strong enough to separate from chance. That is a real result, " +
                                    "not a failure to compute.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                report.pending.forEach { pending ->
                    PendingCard(pending)
                    Spacer(Modifier.height(12.dp))
                }

                if (report.testsRun > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${report.testsRun} relationships were examined. Findings are corrected for " +
                            "that, so a pattern has to be clearly stronger than the best of many " +
                            "coincidences before it appears here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "These are personal patterns for your own curiosity, not medical or psychological " +
                "advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    Card(
        colors = if (insight.confidence == Confidence.CLEAR) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    insight.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when (insight.confidence) {
                            Confidence.CLEAR -> "Well supported"
                            Confidence.TENTATIVE -> "Worth watching"
                        },
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                insight.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            insight.caveats.forEach { caveat ->
                Spacer(Modifier.height(6.dp))
                Text(
                    caveat,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingCard(pending: PendingAnalysis) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(pending.label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Collecting data: ${pending.daysAvailable} of ${pending.daysRequired} days. " +
                    "${pending.daysRemaining} to go.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    (pending.daysAvailable.toFloat() / pending.daysRequired).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
