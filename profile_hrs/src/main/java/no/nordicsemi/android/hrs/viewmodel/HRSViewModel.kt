/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.hrs.viewmodel

import android.os.ParcelUuid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.analytics.AppAnalytics
import no.nordicsemi.android.analytics.Profile
import no.nordicsemi.android.analytics.ProfileConnectedEvent
import no.nordicsemi.android.common.navigation.NavigationResult
import no.nordicsemi.android.common.navigation.Navigator
import no.nordicsemi.android.common.ui.scanner.model.DiscoveredBluetoothDevice
import no.nordicsemi.android.hrs.data.HRS_SERVICE_UUID
import no.nordicsemi.android.hrs.service.HRSRepository
import no.nordicsemi.android.hrs.view.DisconnectEvent
import no.nordicsemi.android.hrs.view.HRSScreenViewEvent
import no.nordicsemi.android.hrs.view.HRSViewState
import no.nordicsemi.android.hrs.view.NavigateUpEvent
import no.nordicsemi.android.hrs.view.NoDeviceState
import no.nordicsemi.android.hrs.view.OpenLoggerEvent
import no.nordicsemi.android.hrs.view.SwitchZoomEvent
import no.nordicsemi.android.hrs.view.WorkingState
import no.nordicsemi.android.service.ConnectedResult
import no.nordicsemi.android.toolbox.scanner.ScannerDestinationId
import javax.inject.Inject

@HiltViewModel
internal class HRSViewModel @Inject constructor(
    private val repository: HRSRepository,
    private val navigationManager: Navigator,
    private val analytics: AppAnalytics
) : ViewModel() {

    private val _state = MutableStateFlow<HRSViewState>(NoDeviceState)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (repository.isRunning.firstOrNull() == false) {
                requestBluetoothDevice()
            }
        }

        repository.data.onEach {
            val zoomIn = (_state.value as? WorkingState)?.zoomIn ?: false
            _state.value = WorkingState(it, zoomIn)

            (it as? ConnectedResult)?.let {
                analytics.logEvent(ProfileConnectedEvent(Profile.HRS))
            }
        }.launchIn(viewModelScope)
    }

    private fun requestBluetoothDevice() {
        navigationManager.navigateTo(ScannerDestinationId, ParcelUuid(HRS_SERVICE_UUID))

        navigationManager.resultFrom(ScannerDestinationId)
            .onEach { handleResult(it) }
            .launchIn(viewModelScope)
    }

    private fun handleResult(result: NavigationResult<DiscoveredBluetoothDevice>) {
        when (result) {
            is NavigationResult.Cancelled -> navigationManager.navigateUp()
            is NavigationResult.Success -> repository.launch(result.value)
        }
    }

    fun onEvent(event: HRSScreenViewEvent) {
        when (event) {
            DisconnectEvent -> disconnect()
            NavigateUpEvent -> navigationManager.navigateUp()
            OpenLoggerEvent -> repository.openLogger()
            SwitchZoomEvent -> onZoomButtonClicked()
        }
    }

    private fun onZoomButtonClicked() {
        (_state.value as? WorkingState)?.let {
            _state.value = it.copy(zoomIn = !it.zoomIn)
        }
    }

    private fun disconnect() {
        repository.release()
        navigationManager.navigateUp()
    }
}
