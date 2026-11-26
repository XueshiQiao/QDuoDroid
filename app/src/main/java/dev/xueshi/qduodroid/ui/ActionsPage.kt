package dev.xueshi.qduodroid.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.xueshi.qduodroid.R
import dev.xueshi.qduodroid.model.ActionKind
import dev.xueshi.qduodroid.model.QDuoAction
import dev.xueshi.qduodroid.state.MonitorState
import dev.xueshi.qduodroid.util.ActionStore
import java.util.Collections

/**
 * 「动作」页：列出悬浮条上的动作，可以添加、编辑、删除、拖动排序、单独关掉。
 *
 * 每次改动都立即写进 [ActionStore]，悬浮条下次弹出时就用新的列表。
 */
class ActionsPage(private val activity: AppCompatActivity, root: View) {

    private val rvActions: RecyclerView = root.findViewById(R.id.rvActions)
    private val btnAddAction: MaterialButton = root.findViewById(R.id.btnAddAction)
    private val tvResetActions: TextView = root.findViewById(R.id.tvResetActions)

    private val actions: MutableList<QDuoAction> = ActionStore.load(activity).toMutableList()
    private val adapter = ActionAdapter()
    private val touchHelper: ItemTouchHelper

    init {
        rvActions.layoutManager = LinearLayoutManager(activity)
        rvActions.adapter = adapter

        touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                private var moved = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                    Collections.swap(actions, from, to)
                    adapter.notifyItemMoved(from, to)
                    moved = true
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.85f
                    }
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1f
                    // 松手后才保存一次，拖动过程中每换一个位置不保存。
                    if (moved) {
                        moved = false
                        persist("调整了动作顺序")
                    }
                }
            },
        )
        touchHelper.attachToRecyclerView(rvActions)

        btnAddAction.setOnClickListener { openEditor(existing = null) }
        tvResetActions.setOnClickListener { confirmReset() }
    }

    private fun persist(logLine: String) {
        ActionStore.save(activity, actions.toList())
        MonitorState.addLog("动作列表：$logLine")
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun confirmReset() {
        AlertDialog.Builder(activity)
            .setTitle("恢复默认动作？")
            .setMessage("会删掉你添加和修改过的动作，换回默认的翻译、润色、总结、解释、搜索、朗读六个动作。")
            .setPositiveButton("恢复默认") { _, _ ->
                val defaults = ActionStore.resetToDefaults(activity)
                actions.clear()
                actions.addAll(defaults)
                adapter.notifyDataSetChanged()
                MonitorState.addLog("动作列表：已恢复默认动作")
                Toast.makeText(activity, "已恢复默认动作", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openEditor(existing: QDuoAction?) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_action_editor, null, false)
        val etTitle = view.findViewById<EditText>(R.id.etActionTitle)
        val layoutKindSelector = view.findViewById<LinearLayout>(R.id.layoutKindSelector)
        val tvKindValue = view.findViewById<TextView>(R.id.tvKindValue)
        val tvKindHint = view.findViewById<TextView>(R.id.tvKindHint)
        val layoutPromptSection = view.findViewById<LinearLayout>(R.id.layoutPromptSection)
        val etPrompt = view.findViewById<EditText>(R.id.etActionPrompt)
        val layoutUrlSection = view.findViewById<LinearLayout>(R.id.layoutUrlSection)
        val etUrl = view.findViewById<EditText>(R.id.etActionUrl)

        listOf(etTitle, layoutKindSelector, etPrompt, etUrl).forEach { it.background = inputBoxBackground() }

        var kind = existing?.kind ?: ActionKind.AI
        etTitle.setText(existing?.title.orEmpty())
        etPrompt.setText(existing?.prompt.orEmpty())
        etUrl.setText(existing?.url.orEmpty())

        fun renderKind() {
            tvKindValue.text = kind.label
            tvKindHint.text = kind.hint
            layoutPromptSection.visibility = if (kind == ActionKind.AI) View.VISIBLE else View.GONE
            layoutUrlSection.visibility = if (kind == ActionKind.OPEN_URL) View.VISIBLE else View.GONE
        }
        renderKind()

        layoutKindSelector.setOnClickListener {
            val kinds = ActionKind.entries
            AlertDialog.Builder(activity)
                .setTitle("动作类型")
                .setSingleChoiceItems(kinds.map { it.label }.toTypedArray(), kinds.indexOf(kind)) { d, which ->
                    d.dismiss()
                    kind = kinds[which]
                    if (kind == ActionKind.OPEN_URL && etUrl.text.isNullOrBlank()) {
                        etUrl.setText(DEFAULT_URL_TEMPLATE)
                    }
                    renderKind()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "添加动作" else "编辑动作")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
        if (existing != null) {
            builder.setNeutralButton("删除", null)
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim()
                val prompt = etPrompt.text.toString().trim()
                val url = etUrl.text.toString().trim()
                when {
                    title.isEmpty() -> {
                        etTitle.error = "请填写名称"
                        etTitle.requestFocus()
                        return@setOnClickListener
                    }
                    kind == ActionKind.AI && prompt.isEmpty() -> {
                        etPrompt.error = "请写提示词"
                        etPrompt.requestFocus()
                        return@setOnClickListener
                    }
                    kind == ActionKind.OPEN_URL && !url.contains(QDuoAction.TEXT_PLACEHOLDER) -> {
                        etUrl.error = "网址里要有 {text}，打开时会换成选中的文字"
                        etUrl.requestFocus()
                        return@setOnClickListener
                    }
                }
                // 只保存这个类型用得上的内容：换了类型之后，旧类型的提示词或网址不留在动作里。
                val saved = QDuoAction(
                    id = existing?.id ?: ActionStore.newId(),
                    title = title,
                    kind = kind,
                    prompt = if (kind == ActionKind.AI) prompt else "",
                    url = if (kind == ActionKind.OPEN_URL) url else "",
                    enabled = existing?.enabled ?: true,
                )
                val index = actions.indexOfFirst { it.id == saved.id }
                if (index >= 0) {
                    actions[index] = saved
                    adapter.notifyItemChanged(index)
                    persist("修改了「$title」")
                } else {
                    actions.add(saved)
                    adapter.notifyItemInserted(actions.lastIndex)
                    rvActions.scrollToPosition(actions.lastIndex)
                    persist("添加了「$title」（${kind.label}）")
                }
                dialog.dismiss()
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                    setTextColor(Color.parseColor("#B91C1C"))
                    setOnClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle("删除「${existing.title}」？")
                            .setMessage("删除后这个动作就不在悬浮条上了。想暂时不用的话，也可以在列表里把它右边的开关关掉。")
                            .setPositiveButton("删除") { _, _ ->
                                val index = actions.indexOfFirst { it.id == existing.id }
                                if (index >= 0) {
                                    actions.removeAt(index)
                                    adapter.notifyItemRemoved(index)
                                    persist("删除了「${existing.title}」")
                                }
                                dialog.dismiss()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun inputBoxBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(10f)
        setColor(Color.parseColor("#F3F4F6"))
        setStroke(dpF(1f).toInt(), Color.parseColor("#E5E7EB"))
    }

    private fun kindBadgeColors(kind: ActionKind): Pair<Int, Int> = when (kind) {
        ActionKind.AI -> Color.parseColor("#EDE9FE") to Color.parseColor("#6D28D9")
        ActionKind.TRANSLATE -> Color.parseColor("#E0F2FE") to Color.parseColor("#0369A1")
        ActionKind.OPEN_URL -> Color.parseColor("#FEF3C7") to Color.parseColor("#B45309")
        ActionKind.SPEAK -> Color.parseColor("#DCFCE7") to Color.parseColor("#047857")
    }

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics)

    private inner class ActionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvActionTitle)
        val tvKind: TextView = itemView.findViewById(R.id.tvActionKind)
        val tvDetail: TextView = itemView.findViewById(R.id.tvActionDetail)
        val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switchActionEnabled)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
    }

    private inner class ActionAdapter : RecyclerView.Adapter<ActionViewHolder>() {

        override fun getItemCount(): Int = actions.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActionViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_action, parent, false)
            val holder = ActionViewHolder(view)
            view.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) openEditor(actions[pos])
            }
            holder.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }
            holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                val current = actions[pos]
                if (current.enabled == isChecked) return@setOnCheckedChangeListener
                actions[pos] = current.copy(enabled = isChecked)
                applyEnabledLook(holder, isChecked)
                persist(if (isChecked) "打开了「${current.title}」" else "关掉了「${current.title}」")
            }
            return holder
        }

        override fun onBindViewHolder(holder: ActionViewHolder, position: Int) {
            val action = actions[position]
            holder.tvTitle.text = action.title
            holder.tvKind.text = action.kind.label
            val (bg, fg) = kindBadgeColors(action.kind)
            holder.tvKind.setTextColor(fg)
            holder.tvKind.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(8f)
                setColor(bg)
            }
            holder.tvDetail.text = action.detailLine()
            // 先设好开关状态再算样式；监听器里比较了 enabled，所以这里设置不会被当成一次修改。
            holder.switchEnabled.isChecked = action.enabled
            applyEnabledLook(holder, action.enabled)
        }

        private fun applyEnabledLook(holder: ActionViewHolder, enabled: Boolean) {
            val alpha = if (enabled) 1f else 0.45f
            holder.tvTitle.alpha = alpha
            holder.tvKind.alpha = alpha
            holder.tvDetail.alpha = alpha
        }
    }

    companion object {
        private const val DEFAULT_URL_TEMPLATE = "https://www.google.com/search?q={text}"
    }
}
