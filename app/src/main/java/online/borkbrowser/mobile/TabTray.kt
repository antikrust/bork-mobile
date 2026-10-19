package online.borkbrowser.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tabs and workspaces in one sheet.
 *
 * Workspaces run across the top as chips — the desktop's workspace rail, laid
 * on its side. Below them, the picked workspace's tabs as a grid of cards.
 * Swipe a card sideways to close it; long-press it to move it to another
 * workspace. Long-press a workspace chip to rename or delete it.
 */
class TabTray(private val context: Context, private val cb: Callbacks) {

    interface Callbacks {
        val workspaces: List<Workspace>
        val workspace: Workspace
        val current: Tab?
        fun tabsIn(ws: Workspace): List<Tab>
        fun select(tab: Tab)
        fun close(tab: Tab)
        fun closeAll(ws: Workspace)
        fun newTab(private: Boolean)
        fun switchWorkspace(ws: Workspace)
        fun createWorkspace(after: (Workspace) -> Unit)
        fun renameWorkspace(ws: Workspace, after: () -> Unit)
        fun deleteWorkspace(ws: Workspace, after: () -> Unit)
        fun moveTab(tab: Tab, to: Workspace)
    }

    private val dialog = BottomSheetDialog(context)
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val grid = RecyclerView(context)
    private val title = TextView(context)
    private val adapter = CardAdapter()
    private fun dp(v: Int) = Ui.dp(context, v)

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bork_surface))
        }

        // Header: workspace name and the actions.
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(6), 0)
        }
        title.apply {
            textSize = 18f
            setTextColor(Ui.fg(context))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(title)
        head.addView(iconButton(R.drawable.ic_private, "New private tab") { dialog.dismiss(); cb.newTab(true) })
        head.addView(iconButton(R.drawable.ic_add, "New tab") { dialog.dismiss(); cb.newTab(false) })
        head.addView(iconButton(R.drawable.ic_menu, "More") { more(it) })
        root.addView(head)

        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(14), dp(4), dp(14), dp(6))
            clipToPadding = false
            addView(chips)
        })

        grid.layoutManager = GridLayoutManager(context, 2)
        grid.adapter = adapter
        grid.setPadding(dp(10), 0, dp(10), dp(16))
        grid.clipToPadding = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val tab = adapter.tabs.getOrNull(vh.bindingAdapterPosition) ?: return
                cb.close(tab)
                refresh()
            }
        }).attachToRecyclerView(grid)
        val height = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        refresh()
        dialog.show()

        // Land on the current tab, not the top of a long workspace.
        val at = adapter.tabs.indexOf(cb.current)
        if (at > 0) grid.scrollToPosition(at)
    }

    private fun iconButton(icon: Int, label: String, onClick: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        contentDescription = label
        setColorFilter(Ui.fg(context))
        background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener(onClick)
    }

    private fun more(anchor: View) {
        val ws = cb.workspace
        val items = listOf("New workspace", "Rename ${ws.name}", "Delete ${ws.name}", "Close all tabs here")
        MaterialAlertDialogBuilder(context)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> cb.createWorkspace { refresh() }
                    1 -> cb.renameWorkspace(ws) { refresh() }
                    2 -> cb.deleteWorkspace(ws) { refresh() }
                    3 -> {
                        cb.closeAll(ws)
                        refresh()
                    }
                }
            }
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val ws = cb.workspace
        val list = cb.tabsIn(ws)
        title.text = "${ws.name} · ${list.size} tab${if (list.size == 1) "" else "s"}"
        adapter.tabs = list
        adapter.notifyDataSetChanged()

        chips.removeAllViews()
        for (w in cb.workspaces) {
            chips.addView(Chip(context).apply {
                text = "${w.name}  ${cb.tabsIn(w).size}"
                isCheckable = true
                isChecked = w === ws
                setOnClickListener {
                    cb.switchWorkspace(w)
                    refresh()
                }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(w.name)
                        .setItems(arrayOf("Rename", "Delete")) { _, which ->
                            if (which == 0) cb.renameWorkspace(w) { refresh() } else cb.deleteWorkspace(w) { refresh() }
                        }
                        .show()
                    true
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            })
        }
        chips.addView(Chip(context).apply {
            text = "+ Workspace"
            setOnClickListener { cb.createWorkspace { refresh() } }
        })
    }

    private inner class CardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var tabs: List<Tab> = emptyList()

        override fun getItemCount() = tabs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tab = tabs[position]
            val card = holder.itemView as LinearLayout
            card.removeAllViews()
            card.setBackgroundResource(if (tab === cb.current) R.drawable.bg_card_selected else R.drawable.bg_card)

            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (tab.private) {
                top.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_private)
                    setColorFilter(ContextCompat.getColor(context, R.color.bork_pink))
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
                })
            }
            top.addView(TextView(context).apply {
                text = tab.displayTitle
                textSize = 13f
                setTextColor(Ui.fg(context))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Ui.dim(context))
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Close tab"
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                setOnClickListener {
                    cb.close(tab)
                    refresh()
                }
            })
            card.addView(top)

            val preview = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(4)
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.bork_bg))
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val thumb = tab.thumbnail
            if (thumb != null && !tab.home) {
                preview.addView(ImageView(context).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    // The top of a page is what identifies it.
                    scaleType = ImageView.ScaleType.MATRIX
                    imageMatrix = android.graphics.Matrix().apply {
                        val scale = dp(170).toFloat() / thumb.width.coerceAtLeast(1)
                        setScale(scale, scale)
                    }
                })
            } else {
                val host = UrlInput.host(tab.url)
                val label = if (tab.home) "bork" else tab.displayTitle
                preview.addView(Ui.letterTile(context, label, host.ifEmpty { label }, 48).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
                })
            }
            card.addView(preview)

            card.setOnClickListener {
                dialog.dismiss()
                cb.select(tab)
            }
            card.setOnLongClickListener {
                val others = cb.workspaces.filter { it.id != tab.workspaceId }
                if (others.isEmpty()) {
                    MaterialAlertDialogBuilder(context)
                        .setMessage("Make a second workspace to move tabs between them.")
                        .setPositiveButton("New workspace") { _, _ -> cb.createWorkspace { refresh() } }
                        .show()
                } else {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Move to workspace")
                        .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                            cb.moveTab(tab, others[which])
                            refresh()
                        }
                        .show()
                }
                true
            }
        }
    }
}
