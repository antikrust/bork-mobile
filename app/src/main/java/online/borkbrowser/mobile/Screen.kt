package online.borkbrowser.mobile

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView

/** A plain scrolling screen with a back arrow and a title — what the secondary screens share. */
abstract class Screen : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected val app get() = BorkApplication.instance

    abstract val screenTitle: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bork_bg))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bork_surface))
            minimumHeight = Ui.dp(context, 56)
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 12), 0)
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Ui.fg(context))
            background = null
            contentDescription = "Back"
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = screenTitle
            textSize = 19f
            setTextColor(Ui.fg(context))
            setPadding(Ui.dp(context, 8), 0, 0, 0)
        })
        root.addView(bar)
        body = Ui.column(this)
        root.addView(NestedScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    protected fun note(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ui.dim(context))
        setPadding(Ui.dp(context, 20), Ui.dp(context, 4), Ui.dp(context, 20), Ui.dp(context, 8))
    }
}
