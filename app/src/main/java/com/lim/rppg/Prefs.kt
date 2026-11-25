package com.lim.rppg

import android.content.Context

/** 앱 설정 저장소 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("rppg_prefs", Context.MODE_PRIVATE)

    var age: Int
        get() = sp.getInt("age", 0)
        set(v) = sp.edit().putInt("age", v).apply()
    /** "M" / "F" / "" */
    var sex: String
        get() = sp.getString("sex", "") ?: ""
        set(v) = sp.edit().putString("sex", v).apply()
    var heightCm: Int
        get() = sp.getInt("height", 0)
        set(v) = sp.edit().putInt("height", v).apply()
    var weightKg: Int
        get() = sp.getInt("weight", 0)
        set(v) = sp.edit().putInt("weight", v).apply()
    var durationSec: Int
        get() = sp.getInt("duration", 60)
        set(v) = sp.edit().putInt("duration", v).apply()

    /** "none" / "ble" / "hc" */
    var watchMode: String
        get() = sp.getString("watch_mode", "none") ?: "none"
        set(v) = sp.edit().putString("watch_mode", v).apply()
    var bleAddress: String
        get() = sp.getString("ble_addr", "") ?: ""
        set(v) = sp.edit().putString("ble_addr", v).apply()
    var bleName: String
        get() = sp.getString("ble_name", "") ?: ""
        set(v) = sp.edit().putString("ble_name", v).apply()

    /** Sasang enum 이름, 미진단이면 "" */
    var constitution: String
        get() = sp.getString("constitution", "") ?: ""
        set(v) = sp.edit().putString("constitution", v).apply()
    /** "태양,소양,태음,소음" 백분율 */
    var constitutionPct: String
        get() = sp.getString("constitution_pct", "") ?: ""
        set(v) = sp.edit().putString("constitution_pct", v).apply()

    /** 측정 완료 시 자동 저장 */
    var autoSave: Boolean
        get() = sp.getBoolean("auto_save", false)
        set(v) = sp.edit().putBoolean("auto_save", v).apply()

    val profileReady: Boolean get() = age > 0 && sex.isNotEmpty()

    val bmi: Double?
        get() = if (heightCm > 0 && weightKg > 0) weightKg / ((heightCm / 100.0) * (heightCm / 100.0)) else null

    val sasang: Sasang? get() = Sasang.values().firstOrNull { it.name == constitution }

    val watchLabel: String
        get() = when (watchMode) {
            "ble" -> if (bleAddress.isNotEmpty()) "BLE: ${bleName.ifEmpty { bleAddress }}" else "BLE 기기 미선택"
            "hc" -> "Health Connect"
            else -> "미연동"
        }
}
