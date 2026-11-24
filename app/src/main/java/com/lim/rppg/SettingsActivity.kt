package com.lim.rppg

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 기본 설정: 사용자 프로필 + 측정 시간 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val c = scaffold("기본 설정")

        c.addView(heading("사용자 프로필"))
        c.addView(body("측정을 시작하려면 나이와 성별이 필요합니다. 키·몸무게는 체질 진단(BMI)에 사용됩니다.", 13f, "#9AA4AE"))
        val age = numberField("나이 (세)", prefs.age).also { c.addView(it) }
        val sexGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val male = RadioButton(this).apply { id = 1001; text = "남성" }
        val female = RadioButton(this).apply { id = 1002; text = "여성" }
        sexGroup.addView(male); sexGroup.addView(female)
        when (prefs.sex) { "M" -> male.isChecked = true; "F" -> female.isChecked = true }
        c.addView(sexGroup)
        val height = numberField("키 (cm, 선택)", prefs.heightCm).also { c.addView(it) }
        val weight = numberField("몸무게 (kg, 선택)", prefs.weightKg).also { c.addView(it) }

        c.addView(heading("측정 시간"))
        c.addView(body("스트레스(HRV) 계산에는 최소 30초, 정확도를 위해 60초 이상을 권장합니다.", 13f, "#9AA4AE"))
        val durGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(30, 60, 120).forEach { sec ->
            durGroup.addView(RadioButton(this).apply {
                id = 2000 + sec; text = "${sec}초"; isChecked = prefs.durationSec == sec
            })
        }
        c.addView(durGroup)

        c.addView(Button(this).apply {
            text = "저장"
            setOnClickListener {
                val a = age.text.toString().toIntOrNull() ?: 0
                val sex = when (sexGroup.checkedRadioButtonId) { 1001 -> "M"; 1002 -> "F"; else -> "" }
                if (a !in 5..110 || sex.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "나이와 성별을 입력하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.age = a; prefs.sex = sex
                prefs.heightCm = height.text.toString().toIntOrNull()?.takeIf { it in 80..250 } ?: 0
                prefs.weightKg = weight.text.toString().toIntOrNull()?.takeIf { it in 20..300 } ?: 0
                if (durGroup.checkedRadioButtonId > 2000) prefs.durationSec = durGroup.checkedRadioButtonId - 2000
                Toast.makeText(this@SettingsActivity, "저장했습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }
}
