package com.abhiram.audiobooks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val SKIP_MS = 30_000L

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent { MaterialTheme { App(viewModel()) } }
    }

    /** Notifications only affect whether the media notification is visible; reads gate the library. */
    private fun requestPermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 0)
    }
}

@UnstableApi
@Composable
private fun App(vm: PlayerViewModel) {
    var stack by remember { mutableStateOf(listOf(vm.library.root)) }
    var showPlayer by remember { mutableStateOf(vm.hasResumable) }
    val scan = rescanOnResume()

    BackHandler(enabled = showPlayer || stack.size > 1) {
        if (showPlayer) showPlayer = false else stack = stack.dropLast(1)
    }

    if (showPlayer) {
        PlayerScreen(vm)
    } else {
        val dir = stack.last()
        BrowseScreen(
            dir = dir,
            vm = vm,
            scan = scan,
            atRoot = stack.size == 1,
            onEnter = { stack = stack + it },
            onPlay = { track ->
                vm.open(track, vm.library.tracksIn(dir), play = true)
                showPlayer = true
            },
            onOpenPlayer = { showPlayer = true },
        )
    }
}

@UnstableApi
@Composable
private fun BrowseScreen(
    dir: File,
    vm: PlayerViewModel,
    scan: Int,
    atRoot: Boolean,
    onEnter: (File) -> Unit,
    onPlay: (Track) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val entries by produceState(initialValue = emptyList<Entry>(), dir, scan) {
        value = withContext(Dispatchers.IO) { vm.library.list(dir) }
    }
    val listState = rememberScalingLazyListState()

    Scaffold(positionIndicator = { PositionIndicator(listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ListHeader {
                    Text(
                        text = if (atRoot) "Books" else dir.name,
                        style = MaterialTheme.typography.title3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (atRoot && vm.nowPlaying != null) {
                item {
                    BigChip(
                        label = vm.title,
                        secondary = if (vm.isPlaying) "playing" else "paused",
                        primary = true,
                        onClick = onOpenPlayer,
                    )
                }
            }
            items(entries) { entry ->
                when (entry) {
                    is Folder -> BigChip(entry.name, secondary = null, primary = false) { onEnter(entry.dir) }
                    is Track -> BigChip(
                        label = entry.name,
                        secondary = progressLabel(vm.progress, entry.id),
                        primary = false,
                    ) { onPlay(entry) }
                }
            }
            // Shown alongside whatever MediaStore did surface, so a partial library is never silent.
            val hint = when {
                entries.isEmpty() -> vm.library.emptyReason()
                atRoot && !vm.library.hasFullAccess() -> ACCESS_HINT
                else -> null
            }
            if (hint != null) {
                item {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.caption3,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Rescans on every resume: the first scan can predate the storage permission grant, and new files
 *  get pushed while the app sits in the background. */
@Composable
private fun rescanOnResume(): Int {
    val owner = LocalLifecycleOwner.current
    var scan by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scan++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return scan
}

@Composable
private fun BigChip(
    label: String,
    secondary: String?,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.button,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = secondary?.let {
            { Text(text = it, style = MaterialTheme.typography.caption2, maxLines = 1) }
        },
    )
}

@UnstableApi
@Composable
private fun PlayerScreen(vm: PlayerViewModel) {
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(500)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = vm.title.ifEmpty { "Nothing open" },
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatTime(vm.position)} / ${formatTime(vm.duration)}",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ProgressBar(vm.position, vm.duration)
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SkipButton("−30") { vm.skip(-SKIP_MS) }
            Button(
                onClick = vm::togglePlay,
                modifier = Modifier.size(62.dp),
                colors = ButtonDefaults.primaryButtonColors(),
            ) {
                PlayPauseGlyph(vm.isPlaying)
            }
            SkipButton("+30") { vm.skip(SKIP_MS) }
        }
    }
}

// Drawn rather than imported: material-icons-core has no Pause, and the extended set is 30x the size.
@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    val color = MaterialTheme.colors.onPrimary
    Canvas(modifier = Modifier.size(28.dp)) {
        if (playing) {
            val bar = size.width * 0.3f
            drawRect(color, Offset(0f, 0f), Size(bar, size.height))
            drawRect(color, Offset(size.width - bar, 0f), Size(bar, size.height))
        } else {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                },
                color,
            )
        }
    }
}

@Composable
private fun SkipButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.button,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.01f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colors.primary),
        )
    }
}

private fun progressLabel(store: ProgressStore, id: String): String? {
    if (store.isFinished(id)) return "finished"
    val position = store.position(id)
    if (position <= 0) return null
    val duration = store.duration(id)
    return if (duration > 0) "${formatTime(position)} / ${formatTime(duration)}" else formatTime(position)
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds % 60)
    } else {
        "%d:%02d".format(minutes, seconds % 60)
    }
}
