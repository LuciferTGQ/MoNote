package app.monote.mobile.feature.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SelectionCoordinator {
    private val mutableSelectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = mutableSelectedIds

    fun update(ids: Set<String>) {
        mutableSelectedIds.value = ids
    }

    fun reconcile(visibleIds: Set<String>) {
        val retained = mutableSelectedIds.value.intersect(visibleIds)
        if (retained != mutableSelectedIds.value) mutableSelectedIds.value = retained
    }
}
