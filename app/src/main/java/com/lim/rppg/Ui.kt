package com.lim.rppg

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** 코드로 화면을 구성하는 작은 헬퍼들 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.heading(text: String) = TextView(this).apply {
    this.text = text; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.parseColor("#FF5C7A")); setPadding(0, dp(20), 0, dp(6))
}

fun Context.body(text: String, size: Float = 14f, color: String = "#E0E6EA") = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(4), 0, dp(4))
}

fun Context.numberField(hint: String, value: Int) = EditText(this).apply {
    this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER
    if (value > 0) setText(value.toString())
}

/** 툴바(뒤로가기) + 스크롤 영역을 가진 화면을 만들고, 내용을 담을 LinearLayout을 반환 */
fun AppCompatActivity.scaffold(title: String): LinearLayout {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#101418"))
    }
    val bar = MaterialToolbar(this).apply {
        this.title = title
        setTitleTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#161B20"))
        setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        setNavigationOnClickListener { (context as Activity).finish() }
    }
    root.addView(bar, LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(32))
    }
    val scroll = ScrollView(this).apply { addView(content) }
    root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    return content
}

fun View.visible(v: Boolean) { visibility = if (v) View.VISIBLE else View.GONE }
