package app.upscaler

import android.content.Context
import org.json.JSONObject

class BackendRepo(private val ctx: Context) {
    val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    val drive = DriveClient { Auth.silentToken(ctx) }
    private var rootId: String? = null

    companion object {
        const val NB_NAME = "AI_Upscaler_Backend.ipynb"
        const val NB_VERSION = 2          // bump when the bundled notebook changes
        const val NB_MIME = "application/vnd.google.colaboratory"
    }

    suspend fun root(): String = rootId ?: drive.ensureFolder("root", "AI Upscaler").also { rootId = it }
    suspend fun jobsFolder(): String = drive.ensureFolder(root(), "jobs")

    /** Copies the bundled Colab notebook into the user's own Drive so the app can open it by file id. */
    suspend fun installNotebook() {
        val bytes = ctx.assets.open(NB_NAME).use { it.readBytes() }
        val existing = drive.find(root(), NB_NAME)
        val id = if (existing != null) { drive.updateContent(existing.id, NB_MIME, bytes); existing.id }
                 else drive.createSmall(root(), NB_NAME, NB_MIME, bytes)
        prefs.edit().putString("nb_id", id).putInt("nb_ver", NB_VERSION).apply()
    }
    fun notebookUrl(): String? = prefs.getString("nb_id", null)?.let { "https://colab.research.google.com/drive/$it" }
    fun notebookOutdated() = prefs.getInt("nb_ver", 0) < NB_VERSION

    /** Reads the worker heartbeat written to Drive every 5 s. Stale heartbeat = Colab is not running. */
    suspend fun status(): BackendStatus {
        val f = drive.find(root(), "backend.json") ?: return BackendStatus(Backend.Offline)
        val j = JSONObject(drive.readText(f.id))
        val age = System.currentTimeMillis() / 1000.0 - j.getDouble("ts")
        if (age > 45) return BackendStatus(Backend.Offline)
        val gpu = j.optString("gpu").takeIf { it.isNotBlank() && it != "null" }
        return BackendStatus(if (j.optString("state") == "busy") Backend.Busy else Backend.Ready, gpu)
    }
}
