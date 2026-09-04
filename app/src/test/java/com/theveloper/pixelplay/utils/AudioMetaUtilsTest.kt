package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AudioMetaUtilsTest {

    @Test
    fun `mimeTypeToFormat maps m4a variants`() {
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/mp4")).isEqualTo("m4a")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/m4a")).isEqualTo("m4a")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/x-m4a")).isEqualTo("m4a")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/mp4a-latm")).isEqualTo("m4a")
    }

    @Test
    fun `mimeTypeToFormat maps universal formats`() {
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/x-aiff")).isEqualTo("aiff")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/ac3")).isEqualTo("ac3")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/vnd.dts")).isEqualTo("dts")
    }

    @Test
    fun `mimeTypeToFormat maps samsung formats`() {
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/amr")).isEqualTo("amr")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/amr-wb")).isEqualTo("amr")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/3gpp")).isEqualTo("amr")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/evrc")).isEqualTo("evrc")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/x-evrc")).isEqualTo("evrc")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/qcelp")).isEqualTo("qcelp")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/x-qcelp")).isEqualTo("qcelp")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/x-ima-adpcm")).isEqualTo("ima")
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/ima-adpcm")).isEqualTo("ima")
    }

    @Test
    fun `mimeTypeToFormat returns dash for null blank and non audio`() {
        assertThat(AudioMetaUtils.mimeTypeToFormat(null)).isEqualTo("-")
        assertThat(AudioMetaUtils.mimeTypeToFormat("")).isEqualTo("-")
        assertThat(AudioMetaUtils.mimeTypeToFormat("  ")).isEqualTo("-")
        assertThat(AudioMetaUtils.mimeTypeToFormat("video/mp4")).isEqualTo("-")
    }

    @Test
    fun `mimeTypeToFormat returns the subtype for unrecognized audio types`() {
        assertThat(AudioMetaUtils.mimeTypeToFormat("audio/unknown-format")).isEqualTo("unknown-format")
    }
}
