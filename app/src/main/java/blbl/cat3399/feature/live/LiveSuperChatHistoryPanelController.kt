package blbl.cat3399.feature.live

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.databinding.ViewLiveSuperChatHistoryPanelBinding

internal class LiveSuperChatHistoryPanelController(
    private val binding: ViewLiveSuperChatHistoryPanelBinding,
) {
    private val adapter = LiveSuperChatHistoryAdapter()
    private var selectedPosition = RecyclerViewPosition.NONE

    val isVisible: Boolean
        get() = binding.root.visibility == View.VISIBLE

    init {
        binding.recyclerLiveSuperChatHistory.layoutManager = LinearLayoutManager(binding.root.context)
        binding.recyclerLiveSuperChatHistory.adapter = adapter
        binding.recyclerLiveSuperChatHistory.setHasFixedSize(true)
        binding.recyclerLiveSuperChatHistory.itemAnimator = null
        binding.recyclerLiveSuperChatHistory.setItemViewCacheSize(2)
    }

    fun show(entries: List<LiveSuperChatHistoryEntry>) {
        adapter.replaceAll(entries)
        updateCount()
        binding.root.visibility = View.VISIBLE
        showEmptyState(entries.isEmpty())

        if (entries.isEmpty()) {
            selectedPosition = RecyclerViewPosition.NONE
            binding.tvLiveSuperChatHistoryEmpty.requestFocus()
            return
        }

        selectedPosition = entries.lastIndex
        focusPosition(selectedPosition)
    }

    fun hide() {
        binding.root.visibility = View.GONE
        selectedPosition = RecyclerViewPosition.NONE
    }

    fun append(entry: LiveSuperChatHistoryEntry) {
        if (!isVisible) return

        val oldCount = adapter.itemCount
        val focusedPosition = focusedItemPosition()
        val wasAtLatest =
            if (oldCount == 0) {
                binding.root.findFocus() != null
            } else {
                (selectedPosition.takeIf { it != RecyclerViewPosition.NONE } ?: focusedPosition) == oldCount - 1
            }
        val removedFirst = adapter.append(entry)
        if (removedFirst) selectedPosition = (selectedPosition - 1).coerceAtLeast(0)

        updateCount()
        showEmptyState(false)
        if (wasAtLatest) {
            selectedPosition = adapter.itemCount - 1
            focusPosition(selectedPosition)
        } else if (removedFirst && selectedPosition != RecyclerViewPosition.NONE) {
            focusPosition(selectedPosition)
        }
    }

    fun moveSelection(direction: Int) {
        if (!isVisible || adapter.itemCount == 0) return

        val currentPosition =
            selectedPosition
                .takeIf { it in 0 until adapter.itemCount }
                ?: focusedItemPosition().takeIf { it != RecyclerViewPosition.NONE }
                ?: adapter.itemCount - 1
        val nextPosition = (currentPosition + direction).coerceIn(0, adapter.itemCount - 1)
        selectedPosition = nextPosition
        focusPosition(nextPosition)
    }

    private fun focusedItemPosition(): Int {
        val focused = binding.root.findFocus() ?: return RecyclerViewPosition.NONE
        val holder = binding.recyclerLiveSuperChatHistory.findContainingViewHolder(focused) ?: return RecyclerViewPosition.NONE
        return holder.bindingAdapterPosition.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }
            ?: RecyclerViewPosition.NONE
    }

    private fun focusPosition(position: Int) {
        if (position !in 0 until adapter.itemCount) return
        val recycler = binding.recyclerLiveSuperChatHistory
        recycler.scrollToPosition(position)
        recycler.post {
            if (!isVisible || position != selectedPosition || position !in 0 until adapter.itemCount) return@post
            recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    private fun updateCount() {
        binding.tvLiveSuperChatHistoryCount.text = "${adapter.itemCount} 条"
    }

    private fun showEmptyState(isEmpty: Boolean) {
        binding.tvLiveSuperChatHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerLiveSuperChatHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private object RecyclerViewPosition {
        const val NONE = -1
    }
}
