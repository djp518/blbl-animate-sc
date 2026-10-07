package blbl.cat3399.feature.live

import android.os.SystemClock
import android.view.View
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.databinding.ViewLiveSuperChatOverlayBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LiveSuperChatOverlayController(
    private val binding: ViewLiveSuperChatOverlayBinding,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val queue = LiveSuperChatQueue(nowEpochSeconds)
    private var displayJob: Job? = null
    private var enabled = true
    private var released = false
    private var transitioning = false
    private var transitionToken = 0L

    fun setEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value
        if (!value) {
            reset()
        } else {
            showNextIfIdle()
        }
    }

    fun submit(item: LiveSuperChat) {
        if (released || !enabled || !queue.offer(item)) return
        showNextIfIdle()
    }

    fun delete(ids: Set<Long>) {
        if (released || ids.isEmpty()) return
        if (queue.delete(ids)) {
            transitionToNext()
        }
    }

    fun release() {
        if (released) return
        released = true
        reset()
        ImageLoader.loadInto(binding.ivLiveSuperChatAvatar, null)
        ImageLoader.loadInto(binding.ivLiveSuperChatBackground, null)
    }

    private fun showNextIfIdle() {
        if (released || !enabled || transitioning || queue.current != null) return
        val item = queue.startNext() ?: run {
            hideImmediately()
            return
        }
        val durationMs = item.displayDurationMs(nowEpochSeconds())
        if (durationMs == null) {
            queue.finishCurrent(item)
            showNextIfIdle()
            return
        }

        bind(item)
        updateCountdown(durationMs)
        val root = binding.root
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationY = root.resources.displayMetrics.density * ENTER_OFFSET_DP
        root
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .start()

        displayJob?.cancel()
        displayJob =
            scope.launch {
                val deadlineMs = SystemClock.elapsedRealtime() + durationMs
                while (true) {
                    val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                    if (remainingMs <= 0L) break
                    updateCountdown(remainingMs)
                    delay(minOf(COUNTDOWN_TICK_MS, remainingMs))
                }
                displayJob = null
                if (queue.finishCurrent(item)) transitionToNext()
            }
    }

    private fun transitionToNext() {
        displayJob?.cancel()
        displayJob = null
        transitioning = true
        val token = ++transitionToken
        val root = binding.root
        root.animate().cancel()
        root
            .animate()
            .alpha(0f)
            .translationY(root.resources.displayMetrics.density * EXIT_OFFSET_DP)
            .setDuration(EXIT_DURATION_MS)
            .withEndAction {
                if (token != transitionToken || released) return@withEndAction
                transitioning = false
                root.visibility = View.GONE
                root.translationY = 0f
                showNextIfIdle()
            }
            .start()
    }

    private fun bind(item: LiveSuperChat) {
        LiveSuperChatCardBinder.bind(binding, item)
    }

    private fun reset() {
        displayJob?.cancel()
        displayJob = null
        transitionToken++
        transitioning = false
        queue.clear()
        hideImmediately()
    }

    private fun updateCountdown(remainingMs: Long) {
        val seconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
        binding.tvLiveSuperChatCountdown.text = "${seconds}s"
    }

    private fun hideImmediately() {
        binding.root.animate().cancel()
        binding.root.visibility = View.GONE
        binding.root.alpha = 0f
        binding.root.translationY = 0f
    }

    private companion object {
        const val ENTER_DURATION_MS = 180L
        const val EXIT_DURATION_MS = 160L
        const val COUNTDOWN_TICK_MS = 1_000L
        const val ENTER_OFFSET_DP = 10f
        const val EXIT_OFFSET_DP = 8f
    }
}
