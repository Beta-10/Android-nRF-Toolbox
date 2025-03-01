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

package no.nordicsemi.android.hrs.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.nordicsemi.android.common.ui.scanner.view.DeviceConnectingView
import no.nordicsemi.android.common.ui.scanner.view.DeviceDisconnectedView
import no.nordicsemi.android.common.ui.scanner.view.Reason
import no.nordicsemi.android.hrs.R
import no.nordicsemi.android.hrs.viewmodel.HRSViewModel
import no.nordicsemi.android.service.ConnectedResult
import no.nordicsemi.android.service.ConnectingResult
import no.nordicsemi.android.service.DeviceHolder
import no.nordicsemi.android.service.DisconnectedResult
import no.nordicsemi.android.service.IdleResult
import no.nordicsemi.android.service.LinkLossResult
import no.nordicsemi.android.service.MissingServiceResult
import no.nordicsemi.android.service.SuccessResult
import no.nordicsemi.android.service.UnknownErrorResult
import no.nordicsemi.android.ui.view.BackIconAppBar
import no.nordicsemi.android.ui.view.LoggerIconAppBar
import no.nordicsemi.android.ui.view.NavigateUpButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HRSScreen() {
    val viewModel: HRSViewModel = hiltViewModel()
    val state = viewModel.state.collectAsState().value

    val navigateUp = { viewModel.onEvent(NavigateUpEvent) }

    Scaffold(
        topBar = { AppBar(state, navigateUp, viewModel) }
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (state) {
                NoDeviceState -> DeviceConnectingView()
                is WorkingState -> when (state.result) {
                    is IdleResult,
                    is ConnectingResult -> DeviceConnectingView { NavigateUpButton(navigateUp) }
                    is ConnectedResult -> DeviceConnectingView { NavigateUpButton(navigateUp) }
                    is DisconnectedResult -> DeviceDisconnectedView(Reason.USER) { NavigateUpButton(navigateUp) }
                    is LinkLossResult -> DeviceDisconnectedView(Reason.LINK_LOSS) { NavigateUpButton(navigateUp) }
                    is MissingServiceResult -> DeviceDisconnectedView(Reason.MISSING_SERVICE) { NavigateUpButton(navigateUp) }
                    is UnknownErrorResult -> DeviceDisconnectedView(Reason.UNKNOWN) { NavigateUpButton(navigateUp) }
                    is SuccessResult -> HRSContentView(state.result.data, state.zoomIn) { viewModel.onEvent(it) }
                }
            }
        }
    }
}

@Composable
private fun AppBar(state: HRSViewState, navigateUp: () -> Unit, viewModel: HRSViewModel) {
    val toolbarName = (state as? WorkingState)?.let {
        (it.result as? DeviceHolder)?.deviceName()
    }

    if (toolbarName == null) {
        BackIconAppBar(stringResource(id = R.string.hrs_title), navigateUp)
    } else {
        LoggerIconAppBar(toolbarName, navigateUp, { viewModel.onEvent(DisconnectEvent) }) {
            viewModel.onEvent(OpenLoggerEvent)
        }
    }
}
