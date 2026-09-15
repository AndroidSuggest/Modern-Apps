package com.vayunmathur.appstore.util

import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.SEARCH_DEBOUNCE_MS
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.domain.SearchRanking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Search ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

internal fun AppStoreViewModel.setSearchImpl(query: String) {
    _query.value = query
    searchJob?.cancel()
    if (query.isBlank()) {
        _searchResults.value = emptyList()
        _hasSearched.value = false
        _isSearching.value = false
        return
    }
    searchJob = viewModelScope.launch {
        delay(SEARCH_DEBOUNCE_MS)
        _isSearching.value = true
        val enabled = enabledSources.value

        // Local first and published immediately: the F-Droid catalogue is on disk, so
        // there is no reason to make the user wait on Play before seeing anything.
        val local = catalog.searchLocal(query)
        _searchResults.value = rank(local, query)

        // Accrescent search is client-side over the listings already cached from the home
        // carousel (its API has no search RPC), so it adds no network round-trip here.
        val accrescentResults =
            if (AppSource.ACCRESCENT in enabled) accrescent.search(query) else emptyList()
        val remote = if (AppSource.PLAYSTORE in enabled) play.search(query) else emptyList()
        _searchResults.value = rank(merge(local, remote, accrescentResults), query)
        _isSearching.value = false
        _hasSearched.value = true
    }
}

/**
 * Combine catalogue, Play and Accrescent hits, one row per package.
 *
 * Where several sources offer a package, [AppSource.PRIORITY] decides which row survives —
 * notably keeping the GrapheneOS row for the Sandboxed Google Play components rather than
 * Play's listing of the same three packages. Sorting is stable, so each source's own
 * relevance ordering is preserved within its rank, and [rank] re-sorts the result anyway.
 */
internal fun AppStoreViewModel.merge(vararg lists: List<UnifiedApp>): List<UnifiedApp> =
    lists.asSequence()
        .flatten()
        .sortedBy { it.source.priority }
        .distinctBy { it.packageName }
        .toList()

/** See [SearchRanking]: exact and prefix hits first, and nothing that answers no word. */
internal fun AppStoreViewModel.rank(apps: List<UnifiedApp>, query: String): List<UnifiedApp> =
    SearchRanking.rank(apps, query) { it.searchCandidate() }
