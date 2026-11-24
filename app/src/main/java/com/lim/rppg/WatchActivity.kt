package com.lim.rppg

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

/** 워치 연동 설정: BLE 심박 기기 검색/선택 또는 Health Connect 권한 */
class WatchActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var bleSection: LinearLayout
    private lateinit var hcSection: LinearLayout
    private val found = LinkedHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private val blePerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val blePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startScan() else status.text = "블루투스 권한이 거부되었습니다"
    }

    private val hcPermLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        status.text = if (HealthConnectSource.PERMISSION in granted) "Health Connect 심박 읽기 권한 허용됨 ✓" else "권한이 허용되지 않았습니다"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = scaffold("워치 연동")

        c.addView(heading("연동 방식"))
        val group = RadioGroup(this)
        val rNone = RadioButton(this).apply { id = 3001; text = "사용 안 함" }
        val rBle = RadioButton(this).apply { id = 3002; text = "BLE 심박 (실시간)" }
        val rHc = RadioButton(this).apply { id = 3003; text = "Health Connect (갤럭시워치·삼성헬스 등)" }
        group.addView(rNone); group.addView(rBle); group.addView(rHc)
        c.addView(group)

        status = body("", 13f, "#66FFB3"); c.addView(status)

        bleSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bleSection.addView(body(
            "표준 BLE 심박 서비스를 지원하는 기기: 가민/폴라/코로스 '심박 브로드캐스트', 가슴 스트랩, " +
                "또는 심박 브로드캐스트 앱을 설치한 갤럭시워치(Wear OS).",
            13f, "#9AA4AE",
        ))
        bleSection.addView(Button(this).apply { text = "BLE 기기 검색 (10초)"; setOnClickListener { requestScan() } })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bleSection.addView(deviceList)
        c.addView(bleSection)

        hcSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hcSection.addView(body(
            "삼성헬스 → 설정 → Health Connect에서 '심박수' 쓰기를 허용하세요. 워치 동기화 지연 때문에 " +
                "실시간이 아니며, 워치에서 운동(예: 실내 운동)을 시작하면 데이터가 촘촘해집니다.",
            13f, "#9AA4AE",
        ))
        hcSection.addView(Button(this).apply { text = "Health Connect 권한 요청"; setOnClickListener { requestHc() } })
        c.addView(hcSection)

        when (prefs.watchMode) { "ble" -> rBle.isChecked = true; "hc" -> rHc.isChecked = true; else -> rNone.isChecked = true }
        refreshSections(prefs.watchMode)
        if (prefs.bleAddress.isNotEmpty()) status.text = "선택된 기기: ${prefs.bleName.ifEmpty { prefs.bleAddress }}"

        group.setOnCheckedChangeListener { _, id ->
            val mode = when (id) { 3002 -> "ble"; 3003 -> "hc"; else -> "none" }
            prefs.watchMode = mode
            refreshSections(mode)
        }
    }

    private fun refreshSections(mode: String) {
        bleSection.visible(mode == "ble")
        hcSection.visible(mode == "hc")
    }

    // ---------- BLE ----------

    private fun requestScan() {
        val missing = blePerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else blePermLauncher.launch(missing.toTypedArray())
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            if (found.containsKey(addr)) return
            val name = try { result.device.name } catch (_: SecurityException) { null } ?: result.scanRecord?.deviceName ?: "이름 없음"
            found[addr] = name
            addDeviceRow(addr, name)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { status.text = "블루투스를 켜주세요"; return }
        if (scanning) return
        found.clear(); deviceList.removeAllViews()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.HRS)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        status.text = "검색 중… 기기에서 심박 브로드캐스트를 켜세요"
        handler.postDelayed({ stopScan(); if (found.isEmpty()) status.text = "심박 기기를 찾지 못했습니다" }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    private fun addDeviceRow(addr: String, name: String) {
        deviceList.addView(TextView(this).apply {
            text = "♥  $name\n    $addr"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                prefs.bleAddress = addr; prefs.bleName = name; prefs.watchMode = "ble"
                stopScan()
                status.text = "선택된 기기: $name"
                Toast.makeText(this@WatchActivity, "$name 선택됨", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ---------- Health Connect ----------

    private fun requestHc() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> hcPermLauncher.launch(setOf(HealthConnectSource.PERMISSION))
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                status.text = "Health Connect 설치/업데이트가 필요합니다"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
                    )))
                } catch (_: Exception) {}
            }
            else -> status.text = "이 기기는 Health Connect를 지원하지 않습니다"
        }
    }

    override fun onDestroy() {
        stopScan(); handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
