package app.upscaler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, msg: String) : IOException("Drive $code: $msg")
data class DriveFile(val id: String, val name: String, val size: Long)

/** Minimal Drive v3 REST client. Streams everything; a video is never loaded into memory. */
class DriveClient(private val token: suspend () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    private val api = "https://www.googleapis.com/drive/v3"
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun <T> exec(build: (String) -> Request, parse: (Response) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(build(token())).execute().use {
            if (!it.isSuccessful) throw DriveException(it.code, it.body?.string()?.take(200) ?: "")
            parse(it)
        }
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    suspend fun list(parent: String, name: String? = null, foldersOnly: Boolean = false): List<DriveFile> {
        var q = "'$parent' in parents and trashed=false"
        if (name != null) q += " and name='${esc(name)}'"
        if (foldersOnly) q += " and mimeType='application/vnd.google-apps.folder'"
        val url = "$api/files".toHttpUrl().newBuilder().addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name,size)").addQueryParameter("pageSize", "100").build()
        return exec({ Request.Builder().url(url).header("Authorization", "Bearer $it").build() }) { r ->
            val a = JSONObject(r.body!!.string()).getJSONArray("files")
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { DriveFile(it.getString("id"), it.getString("name"), it.optString("size", "0").toLong()) }
            }
        }
    }
    suspend fun find(parent: String, name: String) = list(parent, name).firstOrNull()

    suspend fun createFolder(parent: String, name: String): String {
        val body = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", JSONArray().put(parent)).toString().toRequestBody(json)
        return exec({ Request.Builder().url("$api/files?fields=id").header("Authorization", "Bearer $it").post(body).build() }) {
            JSONObject(it.body!!.string()).getString("id")
        }
    }
    suspend fun ensureFolder(parent: String, name: String): String =
        list(parent, name, true).firstOrNull()?.id ?: createFolder(parent, name)

    suspend fun createSmall(parent: String, name: String, mime: String, content: ByteArray): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent)).toString()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(json)).addPart(content.toRequestBody(mime.toMediaType())).build()
        return exec({ Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $it").post(body).build() }) { JSONObject(it.body!!.string()).getString("id") }
    }
    suspend fun updateContent(id: String, mime: String, content: ByteArray) {
        exec({ Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
            .header("Authorization", "Bearer $it").patch(content.toRequestBody(mime.toMediaType())).build() }) { }
    }
    suspend fun readText(id: String): String =
        exec({ Request.Builder().url("$api/files/$id?alt=media").header("Authorization", "Bearer $it").build() }) { it.body!!.string() }
    suspend fun delete(id: String) {
        exec({ Request.Builder().url("$api/files/$id").header("Authorization", "Bearer $it").delete().build() }) { }
    }

    /** Resumable chunked upload; after a network error it asks Drive how much arrived and continues from there. */
    suspend fun uploadResumable(
        parent: String, name: String, mime: String, size: Long,
        open: () -> InputStream, onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent)).toString().toRequestBody(json)
        val session = http.newCall(Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")
            .header("Authorization", "Bearer ${token()}").header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", size.toString()).post(meta).build()).execute().use {
            if (!it.isSuccessful) throw DriveException(it.code, "upload init")
            it.header("Location")!!
        }
        val buf = ByteArray(8 * 1024 * 1024) // multiple of 256 KiB as Drive requires
        var offset = 0L
        var stream = open()
        var failures = 0
        try {
            while (true) {
                val n = readFully(stream, buf)
                if (n <= 0) throw IOException("unexpected end of file")
                try {
                    val range = "bytes $offset-${offset + n - 1}/$size"
                    http.newCall(Request.Builder().url(session).header("Content-Range", range)
                        .put(buf.toRequestBody("application/octet-stream".toMediaType(), 0, n)).build()).execute().use { r ->
                        when (r.code) {
                            200, 201 -> { onProgress(1f); return@withContext JSONObject(r.body!!.string()).getString("id") }
                            308 -> { offset += n; failures = 0; onProgress(offset.toFloat() / size) }
                            else -> throw DriveException(r.code, "chunk")
                        }
                    }
                } catch (e: IOException) {
                    if (e is DriveException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    if (++failures > 8) throw e
                    Thread.sleep(minOf(30_000L, 1500L * failures * failures))
                    offset = queryOffset(session, size)
                    stream.close(); stream = open(); skipFully(stream, offset)
                }
            }
            @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
        } finally { runCatching { stream.close() } }
    }
    private fun queryOffset(session: String, size: Long): Long =
        http.newCall(Request.Builder().url(session).header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null)).build()).execute().use { r ->
            if (r.code != 308) throw DriveException(r.code, "resume query")
            r.header("Range")?.substringAfter("-")?.toLong()?.plus(1) ?: 0L
        }
    private fun readFully(s: InputStream, b: ByteArray): Int {
        var t = 0
        while (t < b.size) { val r = s.read(b, t, b.size - t); if (r < 0) break; t += r }
        return t
    }
    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) { val k = s.skip(left); if (k > 0) left -= k else if (s.read() < 0) break else left-- }
    }

    suspend fun download(id: String, out: OutputStream, size: Long, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$api/files/$id?alt=media").header("Authorization", "Bearer ${token()}").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw DriveException(r.code, "download")
                val src = r.body!!.byteStream(); val b = ByteArray(256 * 1024); var done = 0L
                while (true) {
                    val n = src.read(b); if (n < 0) break
                    out.write(b, 0, n); done += n
                    if (size > 0) onProgress((done.toFloat() / size).coerceAtMost(1f))
                }
                if (size > 0 && done != size) throw IOException("download incomplete")
            }
    }
}
