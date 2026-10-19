package online.borkbrowser.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** Small builders for the programmatic sheets and lists. */
object Ui {
    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    fun fg(context: Context) = ContextCompat.getColor(context, R.color.bork_fg)
    fun dim(context: Context) = ContextCompat.getColor(context, R.color.bork_dim)

    private fun ripple(context: Context): Drawable? {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return ContextCompat.getDrawable(context, tv.resourceId)
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** A tappable row: icon (resource or bitmap), label, optional detail. */
    fun row(
        context: Context,
        label: String,
        icon: Int? = null,
        bitmap: Bitmap? = null,
        detail: String? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 52)
        setPadding(dp(context, 20), dp(context, 6), dp(context, 16), dp(context, 6))
        background = ripple(context)
        if (icon != null || bitmap != null) {
            addView(ImageView(context).apply {
                if (bitmap != null) setImageBitmap(bitmap) else setImageResource(icon!!)
                if (bitmap == null) setColorFilter(fg(context))
                layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply {
                    marginEnd = dp(context, 20)
                }
            })
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = label
            setTextColor(fg(context))
            textSize = 16f
            maxLines = 2
        })
        if (!detail.isNullOrBlank()) {
            texts.addView(TextView(context).apply {
                text = detail
                setTextColor(dim(context))
                textSize = 13f
                maxLines = 2
            })
        }
        addView(texts)
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun switchRow(
        context: Context,
        label: String,
        detail: String? = null,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val toggle = MaterialSwitch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return row(context, label, detail = detail) { toggle.toggle() }.apply { addView(toggle) }
    }

    fun header(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.bork_accent))
        textSize = 13f
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 6))
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.bork_line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = dp(context, 6)
            bottomMargin = dp(context, 6)
        }
    }

    /** A letter tile for a site with no icon — the colour is stable per host. */
    fun letterTile(context: Context, label: String, host: String, sizeDp: Int): TextView =
        TextView(context).apply {
            val palette = intArrayOf(
                0xFF0A84FF.toInt(), 0xFFFF4AA2.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(),
                0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt(), 0xFFFF453A.toInt(), 0xFFFFD60A.toInt(),
            )
            val color = palette[Math.floorMod(host.hashCode(), palette.size)]
            text = (label.trim().firstOrNull() ?: host.firstOrNull() ?: '?').uppercase()
            gravity = Gravity.CENTER
            textSize = (sizeDp / 2.4f)
            setTextColor(0xFF050506.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(context, sizeDp / 4).toFloat()
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }
}
