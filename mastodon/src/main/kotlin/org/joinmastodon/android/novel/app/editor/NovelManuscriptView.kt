package org.joinmastodon.android.novel.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import org.joinmastodon.noveleditor.EditorLimits
import org.joinmastodon.noveleditor.EditorSession

/** A genuine Android text input: Android owns composing spans, selection, accessibility and IME. */
internal class NovelManuscriptView(context: Context) : LinearLayout(context) {
    private var applying = false
    private var loaded = false
    private var boundChapter: String? = null
    private var title = ""
    private var listener: (EditorSession.Frame) -> Unit = {}
    private val gutter = Gutter(context)
    private val input = object : EditText(context) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); if (loaded && !applying) changed() }
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); if (loaded) { gutter.invalidate(); if (!applying) changed() } }
    }
    init {
        orientation = HORIZONTAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(gutter, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
        addView(input, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        input.background = null
        input.gravity = Gravity.TOP or Gravity.START
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setHorizontallyScrolling(false)
        input.isVerticalScrollBarEnabled = true
        input.setPadding(dp(10), 0, dp(10), dp(16))
        input.setTextSize(14f)
        input.typeface = androidx.core.content.res.ResourcesCompat.getFont(context, org.joinmastodon.android.R.font.noto_serif_sc_regular)
        input.setLineSpacing(dp(7).toFloat(), 1f)
        input.filters = arrayOf(InputFilter.LengthFilter(EditorLimits.CHAPTER_CONTENT))
        input.isSaveEnabled = false // a 500k chapter must not enter an Activity Bundle
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { gutter.invalidate() }
            override fun afterTextChanged(s: Editable?) { if (!applying) changed() }
        })
    }
    private fun changed() { listener(EditorSession.Frame(title, input.text.toString(), input.selectionStart.coerceAtLeast(0), input.selectionEnd.coerceAtLeast(0), input.scrollY)) }
    fun bind(chapter: String, frame: EditorSession.Frame, enabled: Boolean, foreground: Int, muted: Int, accent: Int, showLines: Boolean, fontSize: Float, onChange: (EditorSession.Frame) -> Unit) {
        listener = onChange; title = frame.title()
        input.isEnabled = enabled
        input.setTextColor(foreground); input.setHintTextColor(muted); input.hint = "开始写作…"
        input.highlightColor = ColorUtils.setAlphaComponent(accent, 65)
        gutter.paint.color = muted; gutter.visibility = if (showLines) View.VISIBLE else View.GONE
        if (input.textSize / resources.displayMetrics.scaledDensity != fontSize) input.textSize = fontSize
        applying = true
        // No setText during ordinary recomposition: this preserves IME composing spans.
        if (boundChapter != chapter || input.text.toString() != frame.body()) {
            input.setText(frame.body()); input.setSelection(frame.start().coerceIn(0, input.length()), frame.end().coerceIn(0, input.length()))
            input.scrollTo(0, frame.scroll())
            input.post { if (boundChapter == chapter && input.text.toString() == frame.body()) { applying = true; input.scrollTo(0, frame.scroll()); applying = false } }
        } else if (input.selectionStart != frame.start() || input.selectionEnd != frame.end()) {
            input.setSelection(frame.start().coerceIn(0, input.length()), frame.end().coerceIn(0, input.length()))
        }
        boundChapter = chapter; applying = false; loaded = true; gutter.invalidate()
    }
    private inner class Gutter(context: Context) : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE; textSize = 10f * resources.displayMetrics.scaledDensity }
        override fun onDraw(canvas: Canvas) {
            val layout = input.layout ?: return
            val first = layout.getLineForVertical(input.scrollY)
            val last = layout.getLineForVertical((input.scrollY + input.height).coerceAtMost(layout.height))
            for (line in first..last) canvas.drawText((line + 1).toString().padStart(2, '0'), width - dp(6).toFloat(), (layout.getLineBaseline(line) + input.totalPaddingTop - input.scrollY).toFloat(), paint)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
