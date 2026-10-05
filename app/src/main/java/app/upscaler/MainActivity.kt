package app.upscaler

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.init(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Root(vm) } } }
    }
}

@Composable
fun Root(vm: AppVM) {
    val ctx = LocalContext.current
    val ui by JobStore.ui.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.signedIn) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollBackend() } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            !vm.signedIn -> SetupScreen(vm)
            ui.phase is Phase.Idle -> HomeScreen(vm)
            ui.phase is Phase.Done -> DoneScreen(vm, ui.phase as Phase.Done)
            ui.phase is Phase.Failed -> FailedScreen(vm, ui.phase as Phase.Failed)
            ui.phase is Phase.Cancelled -> { LaunchedEffect(Unit) { vm.reset() } }
            else -> ProcessingScreen(ui)
        }
    }
}

@Composable
fun SetupScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try { vm.finishSignIn(Auth.fromIntent(ctx, r.data)) } catch (e: Exception) { vm.setupFailed("Sign-in cancelled or failed.") }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("AI Video Upscaler", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Your videos are upscaled by an AI model running on a Google Colab GPU. Sign in with Google once so the app can " +
            "exchange files with your own Google Drive (a folder named “AI Upscaler”). The app never sees your password.")
        Text("Access to your Drive is requested through Google's official sign-in. You can revoke it any time in your Google account settings.",
            style = MaterialTheme.typography.bodySmall)
        vm.setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !vm.setupBusy, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
            vm.setupError = null
            scope.launch {
                try {
                    val res = Auth.authorize(ctx)
                    if (res.hasResolution()) launcher.launch(IntentSenderRequest.Builder(res.pendingIntent!!.intentSender).build())
                    else vm.finishSignIn(res)
                } catch (e: Exception) { vm.setupFailed("Google sign-in unavailable: ${e.message}") }
            }
        }) { if (vm.setupBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Sign in with Google") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppVM) {
    val ctx = LocalContext.current
    val backend by vm.backend.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let { vm.pick(ctx, it) } }
    val s = vm.settings
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AI VIDEO UPSCALER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        BackendCard(vm, backend)
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Select Video") }
        vm.video?.let { v ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(v.name, fontWeight = FontWeight.SemiBold)
                Text("${v.width} × ${v.height}  ·  ${fmtDur(v.durationMs)}  ·  ${v.size / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
            } }
        }
        Card(Modifier.fillMaxWidth(), onClick = { showSettings = true }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Settings", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Icon(Icons.Default.Tune, null) }
            Text("Upscale ${s.scale}×  ·  ${Options.presetLabel(s.preset)}")
            Text("Output: ${s.outputRes}  ·  ${s.encoder}  ·  Quality ${s.quality}", style = MaterialTheme.typography.bodySmall)
        } }
        val canStart = vm.video != null && backend.state == Backend.Ready
        Button(enabled = canStart, onClick = { vm.start(ctx) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("START UPSCALING", fontWeight = FontWeight.Bold) }
        if (vm.video != null && backend.state == Backend.Busy) Text("The GPU is busy with another job.", style = MaterialTheme.typography.bodySmall)
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) { SettingsSheet(vm) }
}

fun fmtDur(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }

@Composable
fun BackendCard(vm: AppVM, b: BackendStatus) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val (dot, label) = when (b.state) {
            Backend.Checking -> "🟡" to (vm.backendError ?: "Checking backend…")
            Backend.Offline -> "🔴" to "Backend offline"
            Backend.Ready -> "🟢" to "Ready" + (b.gpu?.let { " · GPU: $it" } ?: "")
            Backend.Busy -> "🟠" to "Processing a job"
        }
        Text("$dot  $label", fontWeight = FontWeight.SemiBold)
        if (b.state == Backend.Offline) {
            Text("Colab can't be started by the app. One-time steps per session: tap Start backend → in Colab choose Runtime ▸ Run all → " +
                "tap Allow → come back here. Keep that Colab tab open.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.repo.notebookUrl()?.let { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(it)) } }) { Text("Start backend") }
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
fun SliderRow(name: String, v: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column { Text("$name: $v", style = MaterialTheme.typography.bodySmall)
        Slider(value = v.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat()) }
}

@Composable
fun SettingsSheet(vm: AppVM) {
    val s = vm.settings; val sl = s.sliders
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Choice("Upscale", listOf(1, 2), s.scale, { "$it×" }) { vm.settings = s.copy(scale = it) }
        Choice("Preset", Options.presets, s.preset, { Options.presetLabel(it) }) { vm.settings = s.copy(preset = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed mode (FP16)", Modifier.weight(1f)); Switch(s.fp16, { vm.settings = s.copy(fp16 = it) }) }
        Choice("Resize before AI", Options.inputRes, s.inputRes, { it }) { vm.settings = s.copy(inputRes = it) }
        Choice("Final output size", Options.outputRes, s.outputRes, { it }) { vm.settings = s.copy(outputRes = it) }
        Choice("Format / codec", Options.encoders, s.encoder, { it }) { vm.settings = s.copy(encoder = it) }
        SliderRow("Quality (lower number = better, bigger file)", s.quality, 0..51) { vm.settings = s.copy(quality = it) }
        if (s.preset == "Custom") {
            Text("Advanced", fontWeight = FontWeight.SemiBold)
            SliderRow("Anti-alias / deblur", sl.antiAliasDeblur, -100..100) { vm.settings = s.copy(sliders = sl.copy(antiAliasDeblur = it)) }
            SliderRow("Reduce noise", sl.reduceNoise, 0..100) { vm.settings = s.copy(sliders = sl.copy(reduceNoise = it)) }
            SliderRow("Recover details", sl.recoverDetails, 0..100) { vm.settings = s.copy(sliders = sl.copy(recoverDetails = it)) }
            SliderRow("Dehalo", sl.dehalo, 0..100) { vm.settings = s.copy(sliders = sl.copy(dehalo = it)) }
            SliderRow("Sharpen", sl.sharpen, 0..100) { vm.settings = s.copy(sliders = sl.copy(sharpen = it)) }
            SliderRow("Revert compression", sl.revertCompression, 0..100) { vm.settings = s.copy(sliders = sl.copy(revertCompression = it)) }
            SliderRow("Recover original details", sl.recoverOriginal, 0..100) { vm.settings = s.copy(sliders = sl.copy(recoverOriginal = it)) }
        }
    }
}

@Composable
fun ProcessingScreen(ui: JobUi) {
    val ctx = LocalContext.current
    val ph = ui.phase
    val (status, p) = when (ph) {
        is Phase.Uploading -> "Uploading video…" to ph.progress
        is Phase.Waiting -> ph.message to null
        is Phase.Processing -> ph.message to ph.progress
        is Phase.Downloading -> "Downloading result…" to ph.progress
        else -> "" to null
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text("UPSCALING VIDEO", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(ui.fileName)
        Text("${ui.scale}× AI Upscale · ${ui.preset}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text(status)
        if (p != null) { LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()); Text("${(p * 100).toInt()}%") }
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui.reconnecting) Text("Connection lost — reconnecting…", color = MaterialTheme.colorScheme.error)
        Text("You can minimize the app. The transfer and monitoring continue in the background; you'll get a notification when it's done.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Button(onClick = { (ctx as? android.app.Activity)?.moveTaskToBack(true) }, Modifier.fillMaxWidth()) { Text("Minimize App") }
        OutlinedButton(onClick = { UpscaleService.cancel(ctx) }, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
fun DoneScreen(vm: AppVM, d: Phase.Done) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Upscaling Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Your video is ready and saved to\n${d.location}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(d.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) },
            Modifier.fillMaxWidth().height(52.dp)) { Text("Open Video") }
        OutlinedButton(onClick = { vm.reset() }, Modifier.fillMaxWidth()) { Text("Upscale Another") }
    }
}

@Composable
fun FailedScreen(vm: AppVM, f: Phase.Failed) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Text(f.message, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Button(onClick = { vm.start(ctx) }, Modifier.fillMaxWidth()) { Text("Retry") }
        OutlinedButton(onClick = { vm.reset() }, Modifier.fillMaxWidth()) { Text("Back") }
        if (!f.details.isNullOrBlank()) {
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide technical details" else "Technical details") }
            if (details) Text(f.details, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
