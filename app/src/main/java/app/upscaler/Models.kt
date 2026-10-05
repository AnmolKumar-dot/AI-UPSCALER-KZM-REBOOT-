package app.upscaler

import android.net.Uri
import org.json.JSONObject

/** Only options that really exist in Proteus V3 Cell 2. Values are the exact strings Cell 2 expects. */
object Options {
    val presets = listOf("Custom", "V1 Anime 1 Pass", "V2 Anime 2 Pass", "V3 Anime High Quality 2 Pass")
    val inputRes = listOf("No Resize", "1080p", "1440p", "2160p")
    val outputRes = listOf("Original AI Output", "1080p HD", "1440p 2K", "2160p 4K")
    val encoders = listOf("x264 8bit", "x265 8bit", "x265 10bit", "ProRes 4444 12bit", "FFV1 16bit Lossless")
    fun presetLabel(p: String) = when (p) {
        "Custom" -> "Custom"; "V1 Anime 1 Pass" -> "Anime V1 (fast)"
        "V2 Anime 2 Pass" -> "Anime V2 (2-pass)"; else -> "Anime V3 HQ (2-pass)"
    }
}

data class Sliders(
    val antiAliasDeblur: Int = 50, val reduceNoise: Int = 17, val recoverDetails: Int = 100,
    val dehalo: Int = 10, val sharpen: Int = 20, val revertCompression: Int = 100, val recoverOriginal: Int = 0,
)

data class UpscaleSettings(
    val scale: Int = 2, val preset: String = "Custom", val fp16: Boolean = true,
    val inputRes: String = "1080p", val outputRes: String = "1440p 2K",
    val encoder: String = "x265 8bit", val quality: Int = 16, val sliders: Sliders = Sliders(),
) {
    fun toJson() = JSONObject().apply {
        put("scale", scale); put("preset", preset); put("fp16", fp16)
        put("input_res", inputRes); put("output_res", outputRes); put("encoder", encoder); put("quality", quality)
        put("sliders", JSONObject().apply {
            put("anti_alias_deblur", sliders.antiAliasDeblur); put("reduce_noise", sliders.reduceNoise)
            put("recover_details", sliders.recoverDetails); put("dehalo", sliders.dehalo)
            put("sharpen", sliders.sharpen); put("revert_compression", sliders.revertCompression)
            put("recover_original", sliders.recoverOriginal)
        })
    }
}

data class VideoInfo(val uri: Uri, val name: String, val size: Long, val width: Int, val height: Int, val durationMs: Long)

enum class Backend { Checking, Offline, Ready, Busy }
data class BackendStatus(val state: Backend = Backend.Checking, val gpu: String? = null)

sealed interface Phase {
    data object Idle : Phase
    data class Uploading(val progress: Float) : Phase
    data class Waiting(val message: String) : Phase
    data class Processing(val progress: Float?, val message: String) : Phase
    data class Downloading(val progress: Float) : Phase
    data class Done(val uri: Uri, val location: String) : Phase
    data class Failed(val message: String, val details: String?) : Phase
    data object Cancelled : Phase
}

data class JobUi(
    val phase: Phase = Phase.Idle, val fileName: String = "", val scale: Int = 2,
    val preset: String = "", val reconnecting: Boolean = false,
)
