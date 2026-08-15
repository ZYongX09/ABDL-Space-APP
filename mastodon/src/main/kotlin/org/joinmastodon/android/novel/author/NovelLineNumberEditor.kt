package org.joinmastodon.android.novel.author

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils

class NovelLineNumberEditor(context: Context) : LinearLayout(context) {
	private val gutter = GutterView(context)
	private val divider = View(context)
	private val editor = EditText(context)
	private var changingText = false
	private var boundText = ""
	private var onTextChanged: (String) -> Unit = {}

	init {
		orientation = HORIZONTAL
		gravity = Gravity.TOP
		addView(gutter, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
		addView(divider, LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
		addView(editor, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
		editor.background = null
		editor.gravity = Gravity.TOP or Gravity.START
		editor.setPadding(dp(10), 0, dp(8), 0)
		editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
		editor.setLineSpacing(sp(8f), 1f)
		editor.setHorizontallyScrolling(true)
		editor.isHorizontalScrollBarEnabled = false
		editor.isVerticalScrollBarEnabled = false
		editor.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
				gutter.invalidate()
				if (!changingText) {
					boundText = s?.toString().orEmpty()
					onTextChanged(boundText)
				}
			}
			override fun afterTextChanged(s: Editable?) = Unit
		})
		editor.viewTreeObserver.addOnScrollChangedListener { gutter.invalidate() }
	}

	fun bind(text: String, enabled: Boolean, foreground: Int, secondary: Int, accent: Int, dividerColor: Int, onTextChanged: (String) -> Unit) {
		this.onTextChanged = onTextChanged
		editor.isEnabled = enabled
		editor.setTextColor(foreground)
		editor.setHintTextColor(secondary)
		editor.highlightColor = ColorUtils.setAlphaComponent(accent, 72)
		gutter.setColor(secondary)
		divider.setBackgroundColor(dividerColor)
		if (boundText !== text) {
			val selection = editor.selectionStart.coerceIn(0, text.length)
			changingText = true
			editor.setText(text)
			editor.setSelection(selection)
			changingText = false
			boundText = text
		}
		editor.hint = "开始写作…"
	}

	private inner class GutterView(context: Context) : View(context) {
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			textAlign = Paint.Align.RIGHT
			typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
			textSize = sp(14f)
		}
		fun setColor(color: Int) { paint.color = color; invalidate() }

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			val layout = editor.layout ?: return
			val top = editor.scrollY
			val bottom = top + editor.height
			val firstLine = layout.getLineForVertical(top)
			val lastLine = layout.getLineForVertical(bottom.coerceAtMost(layout.height))
			for (line in firstLine..lastLine) {
				val baseline = layout.getLineBaseline(line) - editor.scrollY
				canvas.drawText((line + 1).toString(), width - dp(6).toFloat(), baseline.toFloat(), paint)
			}
		}
	}

	private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
	private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
