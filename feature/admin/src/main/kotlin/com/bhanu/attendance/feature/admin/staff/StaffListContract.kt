package com.bhanu.attendance.feature.admin.staff

import com.bhanu.attendance.domain.model.Staff

/** One row in the staff list. */
data class StaffListItem(
    val id: String,
    val name: String,
    val employeeId: String,
    val isFaceEnrolled: Boolean,
    val isActive: Boolean,
    val punchCount: Int,
) {
    /** Single spoken sentence for the whole row. */
    val accessibilityLabel: String
        get() = buildString {
            append(name)
            append(", employee ID $employeeId")
            append(if (isFaceEnrolled) ", face enrolled" else ", face not enrolled")
            if (!isActive) append(", deactivated")
            append(", $punchCount punches recorded")
        }
}

data class StaffListUiState(
    val items: List<StaffListItem> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isAddDialogVisible: Boolean = false,
) {
    /**
     * Filtering is derived, not stored.
     *
     * Keeping it as a computed property means there is no second copy of the list to fall out
     * of sync, and no `combine` in the ViewModel that can emit a query with a stale list.
     */
    val visibleItems: List<StaffListItem>
        get() = if (query.isBlank()) items else items.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.employeeId.contains(query, ignoreCase = true)
        }

    val isFiltered: Boolean get() = query.isNotBlank()
    val showEmptyState: Boolean get() = !isLoading && visibleItems.isEmpty()
}

/** Detail-pane content, or a placeholder when nothing is selected. */
data class StaffDetailUiState(
    val staff: StaffListItem? = null,
    val punchCount: Int = 0,
    val isLoading: Boolean = false,
)

sealed interface StaffListEvent {
    data object OpenAddDialog : StaffListEvent
    data object CloseAddDialog : StaffListEvent
    data class SelectStaff(val id: String?) : StaffListEvent
    data class OpenEnrolment(val staffId: String) : StaffListEvent
    data class ShowPinReset(val staffId: String) : StaffListEvent
    data class ShowMessage(val message: String) : StaffListEvent
}
