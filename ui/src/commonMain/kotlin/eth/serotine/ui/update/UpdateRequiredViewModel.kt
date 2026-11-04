package eth.serotine.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.UpgradeWarnings
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The update screen's ViewModel, which also decides whether the app shows anything else:
 * [UpdateRequiredUiState.Required] exactly while every network the wallet follows (on, with
 * accounts) has an upgrade that left this version unable to follow it
 * ([UpgradeWarnings.updateRequired]: each activated, and its light client agrees it is not
 * verifying; docs/networks.md, *Upgrades and the update screen*). A network blocked alone is its
 * own section's to say, on Accounts. The app's host scopes it, not a destination, since the
 * destinations are what it replaces.
 */
class UpdateRequiredViewModel(
    observeUpgradeWarnings: ObserveUpgradeWarnings,
    /** How the screen dates the upgrade (unix seconds). */
    formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    val uiState: StateFlow<UpdateRequiredUiState> = observeUpgradeWarnings()
        .map { warnings ->
            val blocked = warnings.updateRequired
            if (blocked.isEmpty()) {
                UpdateRequiredUiState.NotRequired
            } else {
                UpdateRequiredUiState.Required(
                    text = blocked.entries.joinToString("\n\n") { (network, warning) ->
                        updateRequiredText(warning.upgrade, network, formatTime)
                    },
                    details = blocked.values.joinToString("\n") { detailsOf(it.upgrade) },
                    version = "This is Serotine v$APP_VERSION.",
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = UpdateRequiredUiState.NotRequired,
        )
}
