package dev.sergio.lifeinsights.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sergio.lifeinsights.data.DayBoundary
import dev.sergio.lifeinsights.data.TrackerRepository
import dev.sergio.lifeinsights.data.db.CheckInWithTags
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CheckInFormState(
    val mood: Int? = null,
    val energy: Int? = null,
    val note: String = "",
    val selectedTags: Set<String> = emptySet(),
    val editingId: Long = 0,
) {
    val canSave: Boolean get() = mood != null && energy != null
}

data class TodayState(
    val day: LocalDate = LocalDate.now(),
    val entries: List<CheckInWithTags> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val metrics: DailyMetricEntity? = null,
)

class CheckInViewModel(private val repository: TrackerRepository) : ViewModel() {

    private val today: LocalDate = DayBoundary.dayOf(Instant.now(), ZoneId.systemDefault())

    private val _form = MutableStateFlow(CheckInFormState())
    val form: StateFlow<CheckInFormState> = _form.asStateFlow()

    private val _justSaved = MutableStateFlow(false)
    val justSaved: StateFlow<Boolean> = _justSaved.asStateFlow()

    val state: StateFlow<TodayState> = combine(
        repository.observeCheckInsForDay(today),
        repository.observeTags(),
        repository.observeMetricForDay(today),
    ) { entries, tags, metrics ->
        TodayState(
            day = today,
            entries = entries,
            availableTags = tags.filter { it.enabled }.map { it.name },
            metrics = metrics,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState(day = today))

    fun setMood(value: Int) = _form.update { it.copy(mood = value) }

    fun setEnergy(value: Int) = _form.update { it.copy(energy = value) }

    fun setNote(value: String) = _form.update { it.copy(note = value) }

    fun toggleTag(tag: String) = _form.update {
        it.copy(
            selectedTags = if (tag in it.selectedTags) it.selectedTags - tag
            else it.selectedTags + tag,
        )
    }

    fun edit(entry: CheckInWithTags) = _form.update {
        CheckInFormState(
            mood = entry.checkIn.mood,
            energy = entry.checkIn.energy,
            note = entry.checkIn.note.orEmpty(),
            selectedTags = entry.tagNames.toSet(),
            editingId = entry.checkIn.id,
        )
    }

    fun cancelEdit() {
        _form.value = CheckInFormState()
    }

    fun save() {
        val current = _form.value
        val mood = current.mood ?: return
        val energy = current.energy ?: return
        viewModelScope.launch {
            repository.saveCheckIn(
                id = current.editingId,
                mood = mood,
                energy = energy,
                note = current.note,
                tags = current.selectedTags.toList(),
            )
            _form.value = CheckInFormState()
            _justSaved.value = true
        }
    }

    fun acknowledgeSave() {
        _justSaved.value = false
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteCheckIn(id) }
    }

    private fun MutableStateFlow<CheckInFormState>.update(
        block: (CheckInFormState) -> CheckInFormState,
    ) {
        value = block(value)
    }
}
