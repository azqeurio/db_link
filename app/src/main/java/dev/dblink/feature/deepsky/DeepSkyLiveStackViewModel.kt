package dev.dblink.feature.deepsky

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dblink.DbLinkApplication
import dev.dblink.core.deepsky.DeepSkyLiveStackUiState
import dev.dblink.core.deepsky.DeepSkyPresetId
import dev.dblink.core.model.GeoTagLocationSample
import kotlinx.coroutines.flow.StateFlow

class DeepSkyLiveStackViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val coordinator =
        (application as DbLinkApplication).appContainer.deepSkyLiveStackCoordinator

    val uiState: StateFlow<DeepSkyLiveStackUiState> = coordinator.state

    fun onScreenEntered() {
        coordinator.startSession(uiState.value.manualPresetOverride)
    }

    fun onScreenExited() {
        coordinator.stopSession()
    }

    fun onResetSession() {
        coordinator.resetSession()
    }

    fun onSelectPreset(presetId: DeepSkyPresetId?) {
        coordinator.startSession(presetId)
    }

    fun updateSkyHint(locationSample: GeoTagLocationSample?) {
        coordinator.updateSkyHintContext(locationSample, rotationHintDeg = null)
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeepSkyLiveStackViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!)
            }
        }
    }
}
