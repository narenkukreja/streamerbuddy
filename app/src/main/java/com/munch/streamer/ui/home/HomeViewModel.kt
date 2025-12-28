package com.munch.streamer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.munch.streamer.data.StreamedRepository
import com.munch.streamer.data.model.Sport
import com.munch.streamer.ui.model.SportSectionUi
import com.munch.streamer.ui.model.toUi
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class HomeUiState(
    val isLoading: Boolean = true,
    val sections: List<SportSectionUi> = emptyList(),
    val errorMessage: String? = null
)

class HomeViewModel(
    private val repository: StreamedRepository
) : ViewModel() {

    private val prioritizedSportKeys = listOf("football", "american-football", "basketball")

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val sports = repository.getSports()
                val livePopular = repository.getLivePopularMatches()
                val livePopularOrder = livePopular.mapIndexed { index, match -> match.id to index }.toMap()
                val liveIds = repository.getLiveMatchIds()
                val orderedSports = orderSports(sports)
                val sections = buildSections(orderedSports, liveIds, livePopularOrder)
                HomeUiState(isLoading = false, sections = sections)
            }.onSuccess { uiState ->
                _state.value = uiState
            }.onFailure { throwable ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = throwable.message ?: "Unable to load games"
                )
            }
        }
    }

    private fun orderSports(sports: List<Sport>): List<Sport> {
        val prioritized = prioritizedSportKeys.mapNotNull { key ->
            sports.find { sport -> sport.matchesKey(key) }
        }
        val prioritizedIds = prioritized.map { it.id.normalizeKey() }.toSet()
        val remaining = sports.filterNot { prioritizedIds.contains(it.id.normalizeKey()) }
        return prioritized + remaining
    }

    private suspend fun buildSections(
        orderedSports: List<Sport>,
        liveIds: Set<String>,
        livePopularOrder: Map<String, Int>
    ): List<SportSectionUi> = coroutineScope {
        orderedSports.map { sport ->
            async {
                runCatching {
                    val matches = repository.getMatchesForSport(sport.id, liveIds).map { it.toUi() }
                    sortMatches(matches, livePopularOrder)
                }.getOrElse { emptyList() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { SportSectionUi(name = sport.name, matches = it) }
            }
        }.awaitAll().filterNotNull()
    }

    private fun Sport.matchesKey(key: String): Boolean {
        val normalizedKey = key.normalizeKey()
        return id.normalizeKey() == normalizedKey || name.normalizeKey() == normalizedKey
    }

    private fun String.normalizeKey(): String = this.lowercase(Locale.US)
        .replace(" ", "")
        .replace("-", "")

    private fun sortMatches(
        matches: List<com.munch.streamer.ui.model.MatchUi>,
        livePopularOrder: Map<String, Int>
    ): List<com.munch.streamer.ui.model.MatchUi> {
        return matches.sortedWith(
            compareBy<com.munch.streamer.ui.model.MatchUi> { popularRank(it.id, livePopularOrder) }
                .thenBy { popularWeight(it.popular) }
                .thenBy { matchPriority(it.status) }
                .thenBy { timeSortKey(it) }
                .thenBy { it.title }
        )
    }

    private fun popularRank(matchId: String, livePopularOrder: Map<String, Int>): Int =
        livePopularOrder[matchId] ?: Int.MAX_VALUE

    private fun matchPriority(status: com.munch.streamer.ui.model.MatchStatus): Int = when (status) {
        com.munch.streamer.ui.model.MatchStatus.LIVE -> 0
        com.munch.streamer.ui.model.MatchStatus.UPCOMING -> 1
        com.munch.streamer.ui.model.MatchStatus.DONE -> 2
    }

    private fun timeSortKey(match: com.munch.streamer.ui.model.MatchUi): Long {
        val time = match.startTimeEpoch ?: Long.MAX_VALUE
        return when (match.status) {
            com.munch.streamer.ui.model.MatchStatus.LIVE -> 0L
            com.munch.streamer.ui.model.MatchStatus.UPCOMING -> if (time > 0) time else Long.MAX_VALUE
            com.munch.streamer.ui.model.MatchStatus.DONE -> if (time > 0) -time else Long.MAX_VALUE
        }
    }

    private fun popularWeight(popular: Boolean): Int = if (popular) 0 else 1
}

class HomeViewModelFactory(
    private val repository: StreamedRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
