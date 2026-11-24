package com.lim.rppg

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** 워치/웨어러블 심박 소스. 콜백은 임의 스레드에서 올 수 있음 */
interface WatchSource {
    /** onHr(epochMillis, bpm) */
    fun start(onHr: (Long, Int) -> Unit, onStatus: (String) -> Unit)
    fun stop()
}

object Ble {
    val HRS: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HRM: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Heart Rate Measurement 특성 파싱 (flags bit0: 0=uint8, 1=uint16) */
    fun parse(v: ByteArray): Int? {
        if (v.isEmpty()) return null
        return if (v[0].toInt() and 0x01 == 0) {
            if (v.size < 2) null else v[1].toInt() and 0xFF
        } else {
            if (v.size < 3) null else (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        }
    }
}

/**
 * 표준 BLE Heart Rate Profile(0x180D) — 실시간(약 1초 간격).
 * 가민·폴라·코로스 '심박 브로드캐스트', 가슴 스트랩, 심박 브로드캐스트 앱을 설치한 워치 등.
 */
@SuppressLint("MissingPermission")
class BleHrSource(private val ctx: Context, private val address: String) : WatchSource {
    private var gatt: BluetoothGatt? = null
    private var onHr: ((Long, Int) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { onStatus?.invoke("연결됨"); g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> onStatus?.invoke("연결 끊김")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(Ble.HRS)?.getCharacteristic(Ble.HRM)
            if (ch == null) { onStatus?.invoke("심박 서비스 없음"); return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(Ble.CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            onStatus?.invoke("수신 중")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(value)
        }

        @Deprecated("API 32 이하")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { handle(it) }
        }
    }

    private fun handle(v: ByteArray) {
        val bpm = Ble.parse(v) ?: return
        if (bpm in 25..250) onHr?.invoke(System.currentTimeMillis(), bpm)
    }

    override fun start(onHr: (Long, Int) -> Unit, onStatus: (String) -> Unit) {
        this.onHr = onHr; this.onStatus = onStatus
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { onStatus("블루투스 꺼짐"); return }
        try {
            val dev = adapter.getRemoteDevice(address)
            onStatus("연결 중…")
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            onStatus("블루투스 권한 필요")
        } catch (e: IllegalArgumentException) {
            onStatus("기기 주소 오류")
        }
    }

    override fun stop() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null; onHr = null; onStatus = null
    }
}

/**
 * Health Connect — 갤럭시워치(삼성헬스), 픽셀워치(Fitbit) 등이 기록한 심박을 주기적으로 읽음.
 * 워치→폰→Health Connect 동기화 지연이 있어 실시간이 아님(운동 모드 중이면 촘촘해짐).
 */
class HealthConnectSource(
    private val ctx: Context,
    private val scope: CoroutineScope,
) : WatchSource {
    private var job: Job? = null
    private val seen = HashSet<Long>()

    companion object {
        val PERMISSION: String = HealthPermission.getReadPermission(HeartRateRecord::class)

        fun available(ctx: Context): Boolean =
            HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    }

    override fun start(onHr: (Long, Int) -> Unit, onStatus: (String) -> Unit) {
        if (!available(ctx)) { onStatus("Health Connect 없음"); return }
        val client = HealthConnectClient.getOrCreate(ctx)
        val since = Instant.now().minusSeconds(180)
        job = scope.launch {
            try {
                if (PERMISSION !in client.permissionController.getGrantedPermissions()) {
                    onStatus("권한 필요(메뉴)"); return@launch
                }
            } catch (e: Exception) {
                onStatus("Health Connect 오류"); return@launch
            }
            onStatus("동기화 대기")
            while (isActive) {
                try {
                    val resp = client.readRecords(
                        ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, Instant.now()))
                    )
                    var got = 0
                    for (rec in resp.records) for (s in rec.samples) {
                        val t = s.time.toEpochMilli()
                        if (seen.add(t)) { onHr(t, s.beatsPerMinute.toInt()); got++ }
                    }
                    if (got > 0) onStatus("수신 ${seen.size}건")
                } catch (e: Exception) {
                    onStatus("읽기 실패")
                }
                delay(10_000)
            }
        }
    }

    override fun stop() { job?.cancel(); job = null }
}
