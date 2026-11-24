package com.lim.rppg

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 사상체질 간이 설문 */
class ConstitutionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val c = scaffold("체질 진단")

        prefs.sasang?.let {
            c.addView(body("현재 결과: ${it.label} (${pctText(prefs.constitutionPct)})", 14f, "#66FFB3"))
        }
        c.addView(body(
            "12문항 간이 사상체질 설문입니다. 평소 모습에 가장 가까운 답을 고르세요. " +
                "기본 설정의 키·몸무게(BMI)도 함께 반영됩니다.\n※ 참고용이며 한의사 진단을 대체하지 않습니다.",
            13f, "#9AA4AE",
        ))

        val groups = Constitution.questions.mapIndexed { qi, q ->
            c.addView(heading("${qi + 1}. ${q.text}"))
            RadioGroup(this).also { g ->
                q.options.forEachIndexed { oi, (text, _) ->
                    g.addView(RadioButton(this).apply { id = 10_000 + qi * 10 + oi; this.text = text })
                }
                c.addView(g)
            }
        }

        c.addView(Button(this).apply {
            text = "결과 보기"
            setOnClickListener {
                val answers = IntArray(groups.size) { qi ->
                    val id = groups[qi].checkedRadioButtonId
                    if (id == -1) -1 else id - 10_000 - qi * 10
                }
                val missing = answers.indexOfFirst { it < 0 }
                if (missing >= 0) {
                    Toast.makeText(this@ConstitutionActivity, "${missing + 1}번 문항에 답해주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pct = Constitution.score(answers, prefs.bmi)
                val top = Constitution.top(pct)
                prefs.constitution = top.name
                prefs.constitutionPct = pct.joinToString(",")
                val border = if (Constitution.isBorderline(pct)) "\n\n※ 상위 두 체질 차이가 작아 경계형일 수 있습니다." else ""
                AlertDialog.Builder(this@ConstitutionActivity)
                    .setTitle("예측 체질: ${top.label}")
                    .setMessage("${pctText(prefs.constitutionPct)}\n\n${top.summary}\n\n스트레스 관리: ${top.stressTip}$border")
                    .setPositiveButton("확인") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        })
    }

    private fun pctText(csv: String): String {
        val p = csv.split(",").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return ""
        return Sasang.values().mapIndexed { i, s -> "${s.label} ${p[i]}%" }.joinToString(" · ")
    }
}
