package blbl.cat3399.feature.live

import android.graphics.Color
import android.view.View
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared presentation for the on-air SC overlay and each SC history row. */
internal object LiveSuperChatCardBinder {
    fun bind(
        binding: ViewLiveSuperChatOverlayBinding,
        item: LiveSuperChat,
        sentAtEpochSeconds: Long? = null,
    ) {
        val headerColor = parseColor(item.backgroundColor, DEFAULT_HEADER_COLOR)
        val bodyColor = parseColor(item.backgroundBottomColor, DEFAULT_BODY_COLOR)
        binding.liveSuperChatHeader.setBackgroundColor(headerColor)
        binding.tvLiveSuperChatMessage.setBackgroundColor(bodyColor)
        binding.root.strokeColor = bodyColor

        binding.tvLiveSuperChatUser.text = item.userName.ifBlank { "匿名" }
        binding.tvLiveSuperChatUser.setTextColor(parseColor(item.userNameColor, DEFAULT_USER_COLOR))
        binding.tvLiveSuperChatPrice.text = "￥${item.price}"
        binding.tvLiveSuperChatPrice.setTextColor(parseColor(item.backgroundPriceColor, DEFAULT_PRICE_COLOR))
        binding.tvLiveSuperChatMessage.text = item.message
        binding.tvLiveSuperChatMessage.setTextColor(parseColor(item.messageFontColor, Color.WHITE))

        binding.tvLiveSuperChatCountdown.visibility =
            if (sentAtEpochSeconds == null) View.VISIBLE else View.GONE
        binding.tvLiveSuperChatCountdown.text = ""
        binding.tvLiveSuperChatHistoryMeta.visibility =
            if (sentAtEpochSeconds == null) View.GONE else View.VISIBLE
        val historyMeta = sentAtEpochSeconds?.let { sentAt ->
            "UID ${item.uid} · ${TIME_FORMAT.format(Date(sentAt.coerceAtLeast(0L) * 1_000L))}"
        }
        binding.tvLiveSuperChatHistoryMeta.text = historyMeta.orEmpty()

        binding.root.contentDescription =
            buildString {
                append("醒目留言，${binding.tvLiveSuperChatUser.text}，${binding.tvLiveSuperChatPrice.text}，${item.message}")
                if (historyMeta != null) append("，$historyMeta")
            }

        ImageLoader.loadInto(binding.ivLiveSuperChatAvatar, ImageUrl.avatar(item.userFaceUrl))
        val backgroundImage = item.backgroundImageUrl
        binding.ivLiveSuperChatBackground.visibility =
            if (backgroundImage.isNullOrBlank()) View.GONE else View.VISIBLE
        ImageLoader.loadInto(binding.ivLiveSuperChatBackground, backgroundImage)
    }

    private fun parseColor(raw: String, fallback: Int): Int =
        runCatching { Color.parseColor(raw.trim()) }.getOrDefault(fallback)

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private const val DEFAULT_HEADER_COLOR = 0xFFEDF5FF.toInt()
    private const val DEFAULT_BODY_COLOR = 0xFF2A60B2.toInt()
    private const val DEFAULT_USER_COLOR = 0xFF666666.toInt()
    private const val DEFAULT_PRICE_COLOR = 0xFF7497CD.toInt()
}
