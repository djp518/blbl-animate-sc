package blbl.cat3399.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import com.github.penfeizhou.animation.executor.FrameDecoderExecutor
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.WeakHashMap

object ImageLoader {
    private const val TAG = "ImageLoader"

    /** 动图解码的字节上限。评论区 GIF 实测多在几百 KB，8MB 是很宽松的兜底。 */
    private const val MAX_ANIMATED_BYTES = 8 * 1024 * 1024
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val inFlight = WeakHashMap<ImageView, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class ImageRequestKey(
        val url: String,
        val allowAnimated: Boolean,
    )

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * @param allowAnimated 允许播放动图（GIF / 动画 WebP）。目前仅评论图片全屏查看器开启；
     *   头像、封面和评论缩略图都走原有静态图路径。
     */
    fun loadInto(
        view: ImageView,
        url: String?,
        allowAnimated: Boolean = false,
    ) {
        val normalized = normalizeImageUrl(url)

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            detachAnimated(view)
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val requestKey = ImageRequestKey(normalized, allowAnimated)
        val lastRequestKey = view.getTag(R.id.tag_image_loader_url) as? ImageRequestKey
        if (lastRequestKey == requestKey) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                inFlight.remove(view)?.cancel()
                return
            }
            // If the same URL is already loading, keep the current placeholder.
            val inFlightJob = inFlight[view]
            if (inFlightJob != null && inFlightJob.isActive) return
        } else {
            view.setTag(R.id.tag_image_loader_url, requestKey)
            inFlight.remove(view)?.cancel()
        }

        val cached = cache.get(normalized)
        // The comment thumbnail path caches the first frame through the regular static loader.
        // When the full-screen viewer asks to animate a GIF/WebP URL, fetch the original bytes
        // instead of returning that cached first frame. Other image paths keep the existing cache.
        val animationUrl = allowAnimated && mayContainAnimation(normalized)
        if (cached != null && !animationUrl) {
            detachAnimated(view)
            view.setImageBitmap(cached)
            return
        }

        detachAnimated(view)
        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
        val job = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                if (allowAnimated) {
                    // Dynamic drawables carry playback state, so keep them out of the bitmap cache.
                    // The comment viewer shows one image at a time.
                    val animated = withContext(Dispatchers.Default) { decodeAnimated(bytes) }
                    if (animated != null) {
                        AppLog.d(
                            TAG,
                            "animated decoded url=$normalized type=${animated.javaClass.simpleName} " +
                                "${animated.intrinsicWidth}x${animated.intrinsicHeight}",
                        )
                        if ((view.getTag(R.id.tag_image_loader_url) as? ImageRequestKey) == requestKey) {
                            bindAnimated(view, animated, requestKey)
                        }
                        return@launch
                    }
                }
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bmp != null) {
                    cache.put(normalized, bmp)
                    if ((view.getTag(R.id.tag_image_loader_url) as? ImageRequestKey) == requestKey) {
                        view.setImageBitmap(bmp)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "load failed url=$normalized", t)
            }
        }
        inFlight[view] = job
    }

    /**
     * 把字节解成可播放的动图；不是动图或解码失败时返回 null，交给静态路径。
     *
     * 不用 URL 后缀判定、直接看 magic bytes：这样即便服务端给的是无后缀 / 后缀不准的 URL
     * 也能播，而且静态图走的是与改之前完全一致的 [BitmapFactory] 路径。
     *
     * GIF 优先走 [MovieGifDrawable]（API 1 的 [android.graphics.Movie]，自己驱动）；动画 WebP
     * 用独立解码库，兼容 API 21+。静态图片始终走原有 [BitmapFactory] 路径。
     */
    private fun decodeAnimated(bytes: ByteArray): Drawable? {
        // 超过上限就不按动图播：[Movie] 会把整份数据留在内存里，超大 GIF 有 OOM 风险，
        // 宁可退化成静态首帧也不要把播放页搞崩。
        if (bytes.size > MAX_ANIMATED_BYTES) {
            AppLog.w(TAG, "animated skipped: too large ${bytes.size}B > ${MAX_ANIMATED_BYTES}B")
            return null
        }

        if (AnimatedImageFormat.isGif(bytes)) {
            val movie = runCatching { Movie.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (movie == null) {
                AppLog.w(TAG, "gif movie decode failed size=${bytes.size}")
                return null
            }
            // duration()==0 说明是单帧 GIF，没必要按动图播，交给静态路径省下每 100ms 一次的调度。
            // 尺寸为 0 是异常 GIF（Movie 解不出画面），同样交回静态路径。
            if (movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0) {
                AppLog.w(
                    TAG,
                    "gif not playable size=${bytes.size} duration=${movie.duration()} " +
                        "${movie.width()}x${movie.height()}",
                )
                return null
            }
            return MovieGifDrawable(movie)
        }

        if (!AnimatedImageFormat.isAnimatedWebp(bytes)) return null
        return try {
            // Only the full-screen comment viewer uses this decoder, so a single worker is enough.
            FrameDecoderExecutor.getInstance().setPoolSize(1)
            val loader = object : ByteBufferLoader() {
                override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
            }
            WebPDrawable(loader)
        } catch (t: Throwable) {
            AppLog.w(TAG, "animated WebP decode failed", t)
            null
        }
    }

    /**
     * 先把动图挂到 View，再在下一轮检查绑定仍有效后起播/触发第一帧，避免 recycled View
     * 上的旧请求覆盖新内容。
     */
    private fun bindAnimated(
        view: ImageView,
        drawable: Drawable,
        requestKey: ImageRequestKey,
    ) {
        view.setImageDrawable(drawable)
        if (drawable is MovieGifDrawable) view.addOnAttachStateChangeListener(drawable)
        AppLog.d(
            TAG,
            "animated bound url=${requestKey.url} ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}",
        )
        view.post {
            if ((view.getTag(R.id.tag_image_loader_url) as? ImageRequestKey) != requestKey) return@post
            if (view.drawable !== drawable) return@post
            when {
                drawable is MovieGifDrawable -> drawable.invalidateSelf()
                drawable is WebPDrawable -> {
                    // ImageView can mark a Drawable visible before its final bounds are applied.
                    // Restart once the view has completed the bind so the WebP decoder starts
                    // with the measured display size on older Android releases too.
                    runCatching { drawable.start() }
                        .onFailure { AppLog.w(TAG, "animated WebP start failed url=${requestKey.url}", it) }
                }
                drawable is Animatable && !drawable.isRunning -> runCatching { drawable.start() }
                else -> drawable.invalidateSelf()
            }
        }
    }

    /** 换图 / 清空前把上一张动图收干净：摘监听、停动画，避免旧 GIF 在后台继续占调度。 */
    private fun detachAnimated(view: ImageView) {
        val previous = view.drawable
        if (previous is MovieGifDrawable) {
            view.removeOnAttachStateChangeListener(previous)
        }
        if (previous is Animatable) runCatching { previous.stop() }
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun mayContainAnimation(url: String): Boolean {
        if (ImageUrl.isAnimatedImage(url)) return true
        val path = url.substringBefore('?').lowercase()
        return !path.endsWith(".jpg") &&
            !path.endsWith(".jpeg") &&
            !path.endsWith(".png") &&
            !path.endsWith(".bmp")
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}
