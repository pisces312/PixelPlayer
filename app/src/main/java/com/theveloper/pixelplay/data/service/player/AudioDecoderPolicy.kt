package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

@UnstableApi
internal object AudioDecoderPolicy {
    private const val AUDIO_MIDI = "audio/midi"
    private val extensionOnlyMimeTypes = setOf(
        MimeTypes.AUDIO_ALAC,
        MimeTypes.AUDIO_EXOPLAYER_MIDI,
        AUDIO_MIDI
    )

    fun shouldUseExtensionRenderer(mimeType: String): Boolean {
        return extensionOnlyMimeTypes.any { it.equals(mimeType, ignoreCase = true) }
    }

    fun <T> selectPlatformDecoders(mimeType: String, decoderInfos: List<T>): List<T> {
        return if (shouldUseExtensionRenderer(mimeType)) {
            emptyList()
        } else {
            decoderInfos
        }
    }

    fun isLikelyHardwareDecoder(decoderName: String): Boolean {
        val normalized = decoderName.lowercase(Locale.US)
        val knownSoftwareTokens = listOf(
            "omx.google.",
            "c2.android.",
            "ffmpeg",
            "midi",
            "jsyn",
            "libgav1",
            "dav1d"
        )
        if (knownSoftwareTokens.any(normalized::contains)) return false

        // Vendor codecs sometimes ship explicit .sw. / .hw. suffixes (e.g.
        // c2.qti.flac.sw.decoder vs c2.qti.aac.hw.decoder). Trust the suffix
        // over the vendor prefix: a .sw. codec is software even under c2.qti.
        if (normalized.contains(".sw.")) return false
        if (normalized.contains(".hw.")) return true

        return normalized.startsWith("omx.") ||
            normalized.startsWith("c2.") ||
            normalized.contains(".qti.") ||
            normalized.contains(".qcom.") ||
            normalized.contains(".sec.") ||
            normalized.contains(".mtk.") ||
            normalized.contains(".exynos.") ||
            normalized.contains(".dolby.")
    }
}
