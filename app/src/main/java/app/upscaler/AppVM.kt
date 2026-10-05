package app.upscaler

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVM(app: Application) : AndroidViewModel(app) {
    val repo = BackendRepo(app)
    var signedIn by mutableStateOf(repo.prefs.getBoolean("signed_in", false))
    var setupBusy by mutableStateOf(false)
    var setupError by mutableStateOf<String?>(null)
    val backend = MutableStateFlow(BackendStatus())
    var backendError by mutableStateOf<String?>(null)
    var video by mutableStateOf<VideoInfo?>(null)
    var settings by mutableStateOf(UpscaleSettings())

    fun finishSignIn(r: AuthorizationResult) {
        if (r.accessToken == null) { setupError = "Google access was not granted."; return }
        setupBusy = true; setupError = null
        viewModelScope.launch {
            try {
                repo.installNotebook()
                repo.prefs.edit().putBoolean("signed_in", true).apply(); signedIn = true
            } catch (e: Exception) { setupError = "Setup failed: ${e.message}" }
            setupBusy = false
        }
    }
    fun setupFailed(msg: String) { setupError = msg }

    /** Called while the app is visible. Cheap: one small Drive read every 6 s. */
    suspend fun pollBackend() {
        if (!signedIn) return
        if (repo.notebookOutdated()) runCatching { repo.installNotebook() }
        while (true) {
            try { backend.value = repo.status(); backendError = null }
            catch (e: NeedsSignIn) { signedIn = false; repo.prefs.edit().putBoolean("signed_in", false).apply() }
            catch (e: Exception) { backendError = "No connection"; backend.value = BackendStatus(Backend.Checking) }
            delay(6000)
        }
    }

    fun pick(ctx: Context, uri: Uri) {
        viewModelScope.launch {
            video = withContext(Dispatchers.IO) {
                var name = "video"; var size = 0L
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    }
                }
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    fun i(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
                    VideoInfo(uri, name, size, i(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
                        i(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(), i(MediaMetadataRetriever.METADATA_KEY_DURATION))
                } finally { r.release() }
            }
        }
    }

    fun start(ctx: Context) {
        val v = video ?: return
        JobStore.pendingVideo = v; JobStore.pendingSettings = settings
        JobStore.ui.value = JobUi(Phase.Uploading(0f), v.name, settings.scale, Options.presetLabel(settings.preset))
        UpscaleService.start(ctx)
    }
    fun reset() { JobStore.ui.value = JobUi() }
}
