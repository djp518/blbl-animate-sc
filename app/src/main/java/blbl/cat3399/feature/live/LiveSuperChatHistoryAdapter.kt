package blbl.cat3399.feature.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding

internal class LiveSuperChatHistoryAdapter : RecyclerView.Adapter<LiveSuperChatHistoryAdapter.ViewHolder>() {
    private val items = ArrayList<LiveSuperChatHistoryEntry>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ViewLiveSuperChatOverlayBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        val layoutParams = binding.root.layoutParams
        if (layoutParams is ViewGroup.MarginLayoutParams) {
            layoutParams.bottomMargin =
                parent.resources.getDimensionPixelSize(R.dimen.live_super_chat_history_row_gap)
            binding.root.layoutParams = layoutParams
        }
        return ViewHolder(binding)
    }

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
        private val binding: ViewLiveSuperChatOverlayBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LiveSuperChatHistoryEntry) {
            LiveSuperChatCardBinder.bind(
                binding = binding,
                item = item.superChat,
                sentAtEpochSeconds = item.sentAtEpochSeconds,
            )
            binding.root.visibility = View.VISIBLE
            binding.root.alpha = 1f
            binding.root.translationY = 0f
            binding.root.isFocusable = true
            binding.root.isClickable = true
            binding.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }
}
