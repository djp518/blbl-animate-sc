package blbl.cat3399.feature.live

import blbl.cat3399.core.model.LiveSuperChat
import java.util.ArrayDeque

internal data class LiveSuperChatHistoryEntry(
    val messageId: Long,
    val senderId: Long,
    val price: Long,
    val content: String,
    val sentAtEpochSeconds: Long,
)

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
            messageId = item.id,
            senderId = item.uid,
            price = item.price,
            content = item.message,
            sentAtEpochSeconds = item.startTimeSeconds ?: receivedAtEpochSeconds,
        ).also(entries::addLast)
    }

    fun snapshot(): List<LiveSuperChatHistoryEntry> = ArrayList(entries)

    internal companion object {
        const val MAX_ENTRIES = 200
    }
}
