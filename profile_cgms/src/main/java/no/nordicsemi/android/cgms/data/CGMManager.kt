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
package no.nordicsemi.android.cgms.data

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import android.util.SparseArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.common.callback.RecordAccessControlPointResponse
import no.nordicsemi.android.ble.common.callback.battery.BatteryLevelResponse
import no.nordicsemi.android.ble.common.callback.cgm.CGMFeatureResponse
import no.nordicsemi.android.ble.common.callback.cgm.CGMSpecificOpsControlPointResponse
import no.nordicsemi.android.ble.common.callback.cgm.CGMStatusResponse
import no.nordicsemi.android.ble.common.callback.cgm.ContinuousGlucoseMeasurementResponse
import no.nordicsemi.android.ble.common.data.RecordAccessControlPointData
import no.nordicsemi.android.ble.common.data.cgm.CGMSpecificOpsControlPointData
import no.nordicsemi.android.ble.common.profile.RecordAccessControlPointCallback
import no.nordicsemi.android.ble.common.profile.cgm.CGMSpecificOpsControlPointCallback
import no.nordicsemi.android.ble.ktx.asValidResponseFlow
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.ktx.suspendForValidResponse
import no.nordicsemi.android.common.logger.NordicLogger
import no.nordicsemi.android.service.ConnectionObserverAdapter
import no.nordicsemi.android.utils.launchWithCatch
import java.util.*

val CGMS_SERVICE_UUID: UUID = UUID.fromString("0000181F-0000-1000-8000-00805f9b34fb")
private val CGM_STATUS_UUID = UUID.fromString("00002AA9-0000-1000-8000-00805f9b34fb")
private val CGM_FEATURE_UUID = UUID.fromString("00002AA8-0000-1000-8000-00805f9b34fb")
private val CGM_MEASUREMENT_UUID = UUID.fromString("00002AA7-0000-1000-8000-00805f9b34fb")
private val CGM_OPS_CONTROL_POINT_UUID = UUID.fromString("00002AAC-0000-1000-8000-00805f9b34fb")

private val RACP_UUID = UUID.fromString("00002A52-0000-1000-8000-00805f9b34fb")

private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

internal class CGMManager(
    context: Context,
    private val scope: CoroutineScope,
    private val logger: NordicLogger
) : BleManager(context) {

    private var cgmStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var cgmFeatureCharacteristic: BluetoothGattCharacteristic? = null
    private var cgmMeasurementCharacteristic: BluetoothGattCharacteristic? = null
    private var cgmSpecificOpsControlPointCharacteristic: BluetoothGattCharacteristic? = null
    private var recordAccessControlPointCharacteristic: BluetoothGattCharacteristic? = null
    private val records: SparseArray<CGMRecord> = SparseArray<CGMRecord>()
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null

    private var secured = false

    private var recordAccessRequestInProgress = false

    private var sessionStartTime: Long = 0

    private val data = MutableStateFlow(CGMData())
    val dataHolder = ConnectionObserverAdapter<CGMData>()

    init {
        connectionObserver = dataHolder

        data.onEach {
            dataHolder.setValue(it)
        }.launchIn(scope)
    }

    override fun getGattCallback(): BleManagerGattCallback {
        return CGMManagerGattCallback()
    }

    override fun log(priority: Int, message: String) {
        logger.log(priority, message)
    }

    override fun getMinLogPriority(): Int {
        return Log.VERBOSE
    }

    private inner class CGMManagerGattCallback : BleManagerGattCallback() {
        override fun initialize() {
            super.initialize()

            setNotificationCallback(cgmMeasurementCharacteristic).asValidResponseFlow<ContinuousGlucoseMeasurementResponse>()
                .onEach {
                    if (sessionStartTime == 0L && !recordAccessRequestInProgress) {
                        val timeOffset = it.items.minOf { it.timeOffset }
                        sessionStartTime = System.currentTimeMillis() - timeOffset * 60000L
                    }

                    it.items.map {
                        val timestamp = sessionStartTime + it.timeOffset * 60000L
                        val item = CGMRecord(it.timeOffset, it.glucoseConcentration, timestamp)
                        records.put(item.sequenceNumber, item)
                    }

                    data.value = data.value.copy(records = records.toList())
                }.launchIn(scope)

            setIndicationCallback(cgmSpecificOpsControlPointCharacteristic).asValidResponseFlow<CGMSpecificOpsControlPointResponse>()
                .onEach {
                    if (it.isOperationCompleted) {
                        when (it.requestCode) {
                            CGMSpecificOpsControlPointCallback.CGM_OP_CODE_START_SESSION -> sessionStartTime =
                                System.currentTimeMillis()
                            CGMSpecificOpsControlPointCallback.CGM_OP_CODE_STOP_SESSION -> sessionStartTime =
                                0
                        }
                    } else {
                        when (it.requestCode) {
                            CGMSpecificOpsControlPointCallback.CGM_OP_CODE_START_SESSION ->
                                if (it.errorCode == CGMSpecificOpsControlPointCallback.CGM_ERROR_PROCEDURE_NOT_COMPLETED) {
                                    sessionStartTime = 0
                                }
                            CGMSpecificOpsControlPointCallback.CGM_OP_CODE_STOP_SESSION -> sessionStartTime =
                                0
                        }
                    }
                }.launchIn(scope)

            setIndicationCallback(recordAccessControlPointCharacteristic).asValidResponseFlow<RecordAccessControlPointResponse>()
                .onEach {
                    if (it.isOperationCompleted && it.wereRecordsFound() && it.numberOfRecords > 0) {
                        onRecordsReceived(it)
                    } else if (it.isOperationCompleted && !it.wereRecordsFound()) {
                        onNoRecordsFound()
                    } else if (it.isOperationCompleted && it.wereRecordsFound()) {
                        onOperationCompleted(it)
                    } else if (it.errorCode > 0) {
                        onError(it)
                    }
                }.launchIn(scope)

            setNotificationCallback(batteryLevelCharacteristic).asValidResponseFlow<BatteryLevelResponse>()
                .onEach {
                    data.value = data.value.copy(batteryLevel = it.batteryLevel)
                }.launchIn(scope)

            enableNotifications(cgmMeasurementCharacteristic).enqueue()
            enableIndications(cgmSpecificOpsControlPointCharacteristic).enqueue()
            enableIndications(recordAccessControlPointCharacteristic).enqueue()
            enableNotifications(batteryLevelCharacteristic).enqueue()

            scope.launchWithCatch {
                val cgmResponse = readCharacteristic(cgmFeatureCharacteristic).suspendForValidResponse<CGMFeatureResponse>()
                this@CGMManager.secured = cgmResponse.features.e2eCrcSupported
            }

            scope.launchWithCatch {
                val response = readCharacteristic(cgmStatusCharacteristic).suspendForValidResponse<CGMStatusResponse>()
                if (response.status?.sessionStopped == false) {
                    sessionStartTime = System.currentTimeMillis() - response.timeOffset * 60000L
                }
            }

            scope.launchWithCatch {
                if (sessionStartTime == 0L) {
                    writeCharacteristic(
                        cgmSpecificOpsControlPointCharacteristic,
                        CGMSpecificOpsControlPointData.startSession(secured),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).suspend()
                }
            }
        }

        private suspend fun onRecordsReceived(response: RecordAccessControlPointResponse) {
            if (response.numberOfRecords > 0) {
                if (records.size() > 0) {
                    val sequenceNumber = records.keyAt(records.size() - 1) + 1
                    writeCharacteristic(
                        recordAccessControlPointCharacteristic,
                        RecordAccessControlPointData.reportStoredRecordsGreaterThenOrEqualTo(
                            sequenceNumber
                        ),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).suspend()
                } else {
                    writeCharacteristic(
                        recordAccessControlPointCharacteristic,
                        RecordAccessControlPointData.reportAllStoredRecords(),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ).suspend()
                }
            } else {
                recordAccessRequestInProgress = false
                data.value = data.value.copy(requestStatus = RequestStatus.SUCCESS)
            }
        }

        private fun onNoRecordsFound() {
            recordAccessRequestInProgress = false
            data.value = data.value.copy(requestStatus = RequestStatus.SUCCESS)
        }

        private fun onOperationCompleted(response: RecordAccessControlPointResponse) {
            when (response.requestCode) {
                RecordAccessControlPointCallback.RACP_OP_CODE_ABORT_OPERATION ->
                    data.value = data.value.copy(requestStatus = RequestStatus.ABORTED)
                else -> {
                    recordAccessRequestInProgress = false
                    data.value = data.value.copy(requestStatus = RequestStatus.SUCCESS)
                }
            }
        }

        private fun onError(response: RecordAccessControlPointResponse) {
            if (response.errorCode == RecordAccessControlPointCallback.RACP_ERROR_OP_CODE_NOT_SUPPORTED) {
                data.value = data.value.copy(requestStatus = RequestStatus.NOT_SUPPORTED)
            } else {
                data.value = data.value.copy(requestStatus = RequestStatus.FAILED)
            }
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            gatt.getService(CGMS_SERVICE_UUID)?.run {
                cgmStatusCharacteristic = getCharacteristic(CGM_STATUS_UUID)
                cgmFeatureCharacteristic = getCharacteristic(CGM_FEATURE_UUID)
                cgmMeasurementCharacteristic = getCharacteristic(CGM_MEASUREMENT_UUID)
                cgmSpecificOpsControlPointCharacteristic = getCharacteristic(CGM_OPS_CONTROL_POINT_UUID)
                recordAccessControlPointCharacteristic = getCharacteristic(RACP_UUID)
            }
            gatt.getService(BATTERY_SERVICE_UUID)?.run {
                batteryLevelCharacteristic = getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
            }
            return cgmMeasurementCharacteristic != null
                    && cgmSpecificOpsControlPointCharacteristic != null
                    && recordAccessControlPointCharacteristic != null
                    && cgmStatusCharacteristic != null
                    && cgmFeatureCharacteristic != null
        }

        override fun onServicesInvalidated() {
            cgmStatusCharacteristic = null
            cgmFeatureCharacteristic = null
            cgmMeasurementCharacteristic = null
            cgmSpecificOpsControlPointCharacteristic = null
            recordAccessControlPointCharacteristic = null
            batteryLevelCharacteristic = null
        }
    }

    private fun clear() {
        records.clear()
    }

    fun requestLastRecord() {
        if (recordAccessControlPointCharacteristic == null) return
        clear()
        data.value = data.value.copy(requestStatus = RequestStatus.PENDING)
        recordAccessRequestInProgress = true
        scope.launchWithCatch {
            writeCharacteristic(
                recordAccessControlPointCharacteristic,
                RecordAccessControlPointData.reportLastStoredRecord(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).suspend()
        }
    }

    fun requestFirstRecord() {
        if (recordAccessControlPointCharacteristic == null) return
        clear()
        data.value = data.value.copy(requestStatus = RequestStatus.PENDING)
        recordAccessRequestInProgress = true
        scope.launchWithCatch {
            writeCharacteristic(
                recordAccessControlPointCharacteristic,
                RecordAccessControlPointData.reportFirstStoredRecord(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).suspend()
        }
    }

    fun requestAllRecords() {
        if (recordAccessControlPointCharacteristic == null) return
        clear()
        data.value = data.value.copy(requestStatus = RequestStatus.PENDING)
        recordAccessRequestInProgress = true
        scope.launchWithCatch {
            writeCharacteristic(
                recordAccessControlPointCharacteristic,
                RecordAccessControlPointData.reportNumberOfAllStoredRecords(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ).suspend()
        }
    }
}
