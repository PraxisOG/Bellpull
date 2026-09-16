package org.praxisog.hermes

import org.praxisog.hermes.model.HermesError

/**
 * Loading, loaded, or failed — with the failure typed. There is no fourth "empty-ish" case:
 * an empty list is [Ready] with an empty value, so a screen renders "no sessions" rather than
 * an indefinite spinner.
 */
internal sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val value: T) : UiState<T>
    data class Failed(val error: HermesError) : UiState<Nothing>
}
