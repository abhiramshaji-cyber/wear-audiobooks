package com.abhiram.audiobooks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val SKIP_MS = 30_000L
private const val SCAN_INTERVAL_MS = 1_500L

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent { BooksTheme { App(viewModel()) } }
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
    atRoot: Boolean,
    onEnter: (File) -> Unit,
    onPlay: (Track) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val entries = liveEntries(vm, dir)
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(listState) },
    ) {
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
                        color = MaterialTheme.colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (atRoot && vm.nowPlaying != null) {
                item {
                    NowPlayingChip(vm.title, vm.isPlaying, accentFor(vm.nowPlaying.orEmpty()), onOpenPlayer)
                }
            }
            items(entries) { entry ->
                when (entry) {
                    is Folder -> BookChip(entry.name, null, accentFor(entry.name), null) { onEnter(entry.dir) }
                    is Track -> BookChip(
                        label = entry.name,
                        secondary = remainingLabel(vm.progress, entry.id),
                        accent = accentFor(entry.name),
                        fraction = playedFraction(vm.progress, entry.id),
                        onClick = { onPlay(entry) },
                    )
                }
            }
            // Shown alongside whatever did surface, so a partly readable library is never silent.
            val hint = when {
                entries.isEmpty() -> vm.library.emptyReason()
                atRoot && !vm.library.hasFullAccess() -> ACCESS_HINT
                else -> null
            }
            if (hint != null) {
                item {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The filesystem is the only source of truth, so it is re-read on an interval while the screen is
 * up rather than once per resume: files get pushed with the app already open, and the storage
 * permission can be granted after the first scan. Gated on RESUMED so a pocketed watch scans nothing.
 */
@UnstableApi
@Composable
private fun liveEntries(vm: PlayerViewModel, dir: File): List<Entry> {
    val owner = LocalLifecycleOwner.current
    var entries by remember(dir) { mutableStateOf(emptyList<Entry>()) }
    LaunchedEffect(dir, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val scanned = withContext(Dispatchers.IO) { vm.library.list(dir) }
                if (scanned != entries) entries = scanned
                delay(SCAN_INTERVAL_MS)
            }
        }
    }
    return entries
}

@Composable
private fun BookChip(
    label: String,
    secondary: String?,
    accent: Color,
    fraction: Float?,
    onClick: () -> Unit,
) = AccentChip(label, secondary, accent, tint = 0.22f, onClick) {
    if (fraction == null) Dot(accent) else ProgressRing(fraction, accent)
}

@Composable
private fun NowPlayingChip(label: String, playing: Boolean, accent: Color, onClick: () -> Unit) =
    AccentChip(label, if (playing) "playing" else "paused", accent, tint = 0.42f, onClick) {
        PlayPauseGlyph(playing, accent, 14.dp)
    }

/** One chip shape for everything, coloured per book: the list reads by hue before it reads by word. */
@Composable
private fun AccentChip(
    label: String,
    secondary: String?,
    accent: Color,
    tint: Float,
    onClick: () -> Unit,
    icon: @Composable BoxScope.() -> Unit,
) {
    val surface = MaterialTheme.colors.surface
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.gradientBackgroundChipColors(
            startBackgroundColor = accent.copy(alpha = tint).compositeOver(surface),
            endBackgroundColor = surface,
            contentColor = MaterialTheme.colors.onSurface,
            secondaryContentColor = accent,
        ),
        icon = icon,
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

@Composable
private fun Dot(accent: Color) {
    Canvas(Modifier.size(12.dp)) { drawCircle(accent, radius = size.minDimension / 2f) }
}

@Composable
private fun ProgressRing(fraction: Float, accent: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2f
        drawCircle(accent.copy(alpha = 0.25f), radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke),
            )
        }
    }
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
    val accent = accentFor(vm.nowPlaying.orEmpty())
    Scaffold(timeText = { if (!vm.isPlaying) TimeText() }) {
        EdgeArc(vm.position, vm.duration, accent)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatTime(vm.position)} · ${formatTime(vm.duration)}",
                style = MaterialTheme.typography.caption2,
                color = accent,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SkipButton("−30") { vm.skip(-SKIP_MS) }
                Button(
                    onClick = vm::togglePlay,
                    modifier = Modifier.size(60.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = accent),
                ) {
                    PlayPauseGlyph(vm.isPlaying, MaterialTheme.colors.background, 24.dp)
                }
                SkipButton("+30") { vm.skip(SKIP_MS) }
            }
        }
    }
}

/** Progress on the bezel instead of a bar: it reads at a glance and costs no vertical space. */
@Composable
private fun EdgeArc(position: Long, duration: Long, accent: Color) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = accent.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke),
        )
        if (fraction > 0f) {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

// Drawn rather than imported: material-icons-core has no Pause, and the extended set is 30x the size.
@Composable
private fun PlayPauseGlyph(playing: Boolean, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        if (playing) {
            val bar = this.size.width * 0.3f
            drawRect(color, Offset(0f, 0f), Size(bar, this.size.height))
            drawRect(color, Offset(this.size.width - bar, 0f), Size(bar, this.size.height))
        } else {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
                    lineTo(0f, this@Canvas.size.height)
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
        modifier = Modifier.size(46.dp),
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

private fun playedFraction(store: ProgressStore, id: String): Float {
    if (store.isFinished(id)) return 1f
    val duration = store.duration(id)
    return if (duration > 0) (store.position(id).toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** Time left, not time served: it answers "can I finish this on the walk?" in one glance. */
private fun remainingLabel(store: ProgressStore, id: String): String? {
    if (store.isFinished(id)) return "finished"
    val position = store.position(id)
    if (position <= 0) return null
    val left = store.duration(id) - position
    if (left <= 0) return formatTime(position)
    val minutes = (left / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
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
