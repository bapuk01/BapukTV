package com.bapuk.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Обработчик клавиш строки списка: (позиция, keyCode) -> поглощено ли событие. */
typealias RowKeys = (Int, Int) -> Boolean

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onKey: RowKeys? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    var favorites: Set<String> = emptySet()
    /** Канал, который сейчас играет (подсвечивается) */
    var activeUrl: String? = null

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].number.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.number.text = c.number.toString()
        holder.name.text = c.name
        holder.fav.visibility = if (c.url in favorites) View.VISIBLE else View.GONE
        holder.itemView.isActivated = c.url == activeUrl
        LogoLoader.load(holder.logo, c.logo)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val fav: ImageView = v.findViewById(R.id.fav)

        init {
            v.setOnClickListener { pos()?.let(onClick) }
            v.setOnLongClickListener { pos()?.let(onLongClick); true }
            v.setOnKeyListener { _, code, e ->
                e.action == KeyEvent.ACTION_DOWN && pos()?.let { onKey?.invoke(it, code) } == true
            }
        }

        private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }
}

data class GroupItem(val key: String, val title: String, val count: Int)

/** Категории каналов: «Избранное», «Все каналы» и группы из плейлиста. Общие для главного экрана и плеера. */
object Groups {
    const val FAV = "@@fav"
    const val ALL = "@@all"

    fun build(context: android.content.Context, all: List<Channel>, favs: Set<String>): List<GroupItem> {
        val counts = LinkedHashMap<String, Int>()
        all.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val list = ArrayList<GroupItem>(counts.size + 2)
        list += GroupItem(FAV, context.getString(R.string.favorites), all.count { it.url in favs })
        list += GroupItem(ALL, context.getString(R.string.all_channels), all.size)
        counts.forEach { (g, n) -> list += GroupItem(g, g, n) }
        return list
    }

    fun channels(key: String, all: List<Channel>, favs: Set<String>): List<Channel> = when (key) {
        FAV -> all.filter { it.url in favs }
        ALL -> all
        else -> all.filter { it.group == key }
    }
}

class GroupAdapter(
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
    private val onKey: RowKeys,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<GroupItem> = emptyList()
        private set
    var selected = 0
        set(value) {
            val old = field
            field = value
            notifyItemChanged(old)
            notifyItemChanged(value)
        }

    fun submit(list: List<GroupItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.title
        holder.count.text = g.count.toString()
        holder.itemView.isActivated = position == selected
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)

        init {
            v.setOnFocusChangeListener { _, has -> if (has) pos()?.let(onFocus) }
            v.setOnClickListener { pos()?.let(onClick) }
            v.setOnKeyListener { _, code, e ->
                e.action == KeyEvent.ACTION_DOWN && pos()?.let { onKey(it, code) } == true
            }
        }

        private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }
}

/** Прокручивает список к позиции и ставит на неё фокус (для пульта). */
fun RecyclerView.focusPosition(position: Int) {
    val count = adapter?.itemCount ?: 0
    if (count == 0) {
        requestFocus()
        return
    }
    val pos = position.coerceIn(0, count - 1)
    val holder = findViewHolderForAdapterPosition(pos)
    if (holder != null && holder.itemView.isShown && isFullyVisible(holder.itemView)) {
        holder.itemView.requestFocus()
        return
    }
    // Список мог быть скрыт и ещё не измерен — тогда ориентируемся на высоту экрана
    val h = if (height > 0) height else rootView.height
    (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, h / 3)
    post { findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
}

private fun RecyclerView.isFullyVisible(v: View) = v.top >= 0 && v.bottom <= height

fun RecyclerView.verticalList() {
    // Фокус получают только строки: пустой список не должен «съедать» фокус пульта
    isFocusable = false
    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    layoutManager = LinearLayoutManager(context)
    setHasFixedSize(true)
    itemAnimator = null
    setItemViewCacheSize(12)
}
