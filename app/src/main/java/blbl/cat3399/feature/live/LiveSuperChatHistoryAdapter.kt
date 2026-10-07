package blbl.cat3399.feature.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemLiveSuperChatHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class LiveSuperChatHistoryAdapter : RecyclerView.Adapter<LiveSuperChatHistoryAdapter.ViewHolder>() {
    private val items = ArrayList<LiveSuperChatHistoryEntry>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemLiveSuperChatHistoryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(entries: List<LiveSuperChatHistoryEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    fun append(entry: LiveSuperChatHistoryEntry): Boolean {
        var removedFirst = false
        if (items.size >= LiveSuperChatHistory.MAX_ENTRIES) {
            items.removeAt(0)
            notifyItemRemoved(0)
            removedFirst = true
        }
        items.add(entry)
        notifyItemInserted(items.lastIndex)
        return removedFirst
    }

    class ViewHolder(
        private val binding: ItemLiveSuperChatHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LiveSuperChatHistoryEntry) {
            binding.tvLiveSuperChatHistoryMeta.text =
                "UID ${item.senderId} · ￥${item.price} · ${formatTime(item.sentAtEpochSeconds)}"
            binding.tvLiveSuperChatHistoryContent.text = item.content
        }

        private fun formatTime(epochSeconds: Long): String =
            TIME_FORMAT.format(Date(epochSeconds.coerceAtLeast(0L) * 1_000L))

        private companion object {
            val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }
    }
}
