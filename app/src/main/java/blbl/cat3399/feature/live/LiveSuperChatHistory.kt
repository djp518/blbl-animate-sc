package blbl.cat3399.feature.live

import blbl.cat3399.core.model.LiveSuperChat
import java.util.ArrayDeque

internal data class LiveSuperChatHistoryEntry(
    val superChat: LiveSuperChat,
    val sentAtEpochSeconds: Long,
) {
    val messageId: Long get() = superChat.id
    val senderId: Long get() = superChat.uid
    val price: Long get() = superChat.price
    val content: String get() = superChat.message
}

/** Keeps only the current activity's most recent SCs; it never writes to disk. */
internal class LiveSuperChatHistory(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val entries = ArrayDeque<LiveSuperChatHistoryEntry>(maxEntries)
    private val messageIds = HashSet<Long>(maxEntries)

    init {
        require(maxEntries > 0)
    }

    val size: Int
        get() = entries.size

    fun append(
        item: LiveSuperChat,
        receivedAtEpochSeconds: Long,
    ): LiveSuperChatHistoryEntry? {
        if (item.id > 0L && !messageIds.add(item.id)) return null

        if (entries.size >= maxEntries) {
            val removed = entries.removeFirst()
            if (removed.messageId > 0L) messageIds.remove(removed.messageId)
        }

        return LiveSuperChatHistoryEntry(
            superChat = item,
            sentAtEpochSeconds = item.startTimeSeconds ?: receivedAtEpochSeconds,
        ).also(entries::addLast)
    }

    fun snapshot(): List<LiveSuperChatHistoryEntry> = ArrayList(entries)

    internal companion object {
        const val MAX_ENTRIES = 200
    }
}
