package app.upscaler

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

object JobStore {
    val ui = MutableStateFlow(JobUi())
    var pendingVideo: VideoInfo? = null
    var pendingSettings: UpscaleSettings? = null
}

class JobRunner(private val ctx: Context, private val repo: BackendRepo) {
    private val drive get() = repo.drive
    private fun set(p: Phase, reconnecting: Boolean = false) = JobStore.ui.update { it.copy(phase = p, reconnecting = reconnecting) }
    private fun fail(msg: String, details: String? = null) = set(Phase.Failed(msg, details))

    suspend fun run(v: VideoInfo, s: UpscaleSettings) {
        JobStore.ui.value = JobUi(Phase.Uploading(0f), v.name, s.scale, Options.presetLabel(s.preset))
        var folder: String? = null
        var jobCreated = false
        var finished = false
        try {
            if (repo.status().state != Backend.Ready) {
                fail("The processing server isn't running. Start the backend from the home screen, then try again."); finished = true; return
            }
            val name = "job_${System.currentTimeMillis()}"
            folder = drive.createFolder(repo.jobsFolder(), name)
            val ext = v.name.substringAfterLast('.', "mp4").lowercase().take(5)
            val inName = "input.$ext"
            drive.uploadResumable(folder, inName, "application/octet-stream", v.size,
                { ctx.contentResolver.openInputStream(v.uri) ?: throw IOException("cannot open video") }) { set(Phase.Uploading(it)) }
            val job = JSONObject().put("id", name).put("input_file", inName).put("input_size", v.size).put("settings", s.toJson())
            drive.createSmall(folder, "job.json", "application/json", job.toString().toByteArray())
            jobCreated = true
            set(Phase.Waiting("Waiting for the GPU…"))
            finished = monitor(folder, v, s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsSignIn) {
            fail("Google sign-in expired. Open the app and sign in again."); finished = true
        } catch (e: IOException) {
            fail(if (JobStore.ui.value.phase is Phase.Uploading) "Video upload failed. Please try again." else "Connection problem. Please try again.", e.message)
            finished = true
        } catch (e: Exception) {
            fail("Something went wrong.", e.stackTraceToString().take(1500)); finished = true
        } finally {
            if (!finished && folder != null) withContext(NonCancellable) { abort(folder, jobCreated) }
        }
    }

    /** User cancelled (coroutine cancelled): tell the worker to stop, wait for confirmation, then clean up. */
    private suspend fun abort(folder: String, jobCreated: Boolean) {
        runCatching {
            if (jobCreated) {
                drive.createSmall(folder, "cancel", "text/plain", ByteArray(0))
                val confirmed = withTimeoutOrNull(40_000) {
                    while (true) { delay(3000); val st = readStatus(folder)?.optString("state"); if (st == "cancelled" || st == "failed" || st == "done") return@withTimeoutOrNull true }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (confirmed == true) drive.delete(folder)   // keep folder otherwise so the worker still sees the marker
            } else drive.delete(folder)
        }
        set(Phase.Cancelled)
    }

    private suspend fun readStatus(folder: String): JSONObject? =
        drive.find(folder, "status.json")?.let { JSONObject(drive.readText(it.id)) }

    /** @return true when the job reached a terminal state. */
    private suspend fun monitor(folder: String, v: VideoInfo, s: UpscaleSettings): Boolean {
        val started = System.currentTimeMillis()
        var lastGood = started
        while (true) {
            delay(4000)
            val st: JSONObject?
            try { st = readStatus(folder); lastGood = System.currentTimeMillis() }
            catch (e: NeedsSignIn) { throw e }
            catch (e: IOException) {
                set(JobStore.ui.value.phase, reconnecting = true)
                if (System.currentTimeMillis() - lastGood > 10 * 60_000) { fail("Lost connection to the server. Please try again.", e.message); return true }
                continue
            }
            val now = System.currentTimeMillis()
            if (st == null) {
                if (now - started > 180_000 && repo.status().state == Backend.Offline) {
                    fail("The Google Colab runtime disconnected. Start the backend again and retry."); return true
                }
                set(Phase.Waiting("Waiting for the GPU…")); continue
            }
            val stale = now / 1000.0 - st.optDouble("ts", 0.0) > 90
            when (st.getString("state")) {
                "preparing" -> set(Phase.Waiting(st.optString("message", "Preparing…")))
                "processing", "finalizing" -> set(Phase.Processing(st.optDouble("progress", 0.0).toFloat(), st.optString("message", "Upscaling")))
                "done" -> { download(folder, st, v, s); return true }
                "failed" -> { fail("The AI upscaler could not process this video.", st.optString("log_tail")); return true }
                "cancelled" -> { set(Phase.Cancelled); return true }
            }
            if (stale && repo.status().state == Backend.Offline) {
                fail("The Google Colab runtime disconnected. Start the backend again and retry."); return true
            }
        }
    }

    private suspend fun download(folder: String, st: JSONObject, v: VideoInfo, s: UpscaleSettings) {
        val outName = st.getString("output_file")
        val f = drive.find(folder, outName) ?: throw IOException("result not found")
        val ext = outName.substringAfterLast('.', "mp4").lowercase()
        val display = v.name.substringBeforeLast('.') + "_${s.scale}x_upscaled.$ext"
        var attempt = 0
        while (true) {
            set(Phase.Downloading(0f))
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, display)
                put(MediaStore.Video.Media.MIME_TYPE, when (ext) { "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; else -> "video/mp4" })
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AI Upscaler")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri: Uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("cannot create file")
            try {
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> drive.download(f.id, out, f.size) { set(Phase.Downloading(it)) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                runCatching { drive.delete(folder) }
                set(Phase.Done(uri, "Movies/AI Upscaler/$display"))
                return
            } catch (e: CancellationException) { ctx.contentResolver.delete(uri, null, null); throw e }
            catch (e: IOException) {
                ctx.contentResolver.delete(uri, null, null)
                if (++attempt >= 3) throw e
                delay(3000L * attempt)
            }
        }
    }
}
