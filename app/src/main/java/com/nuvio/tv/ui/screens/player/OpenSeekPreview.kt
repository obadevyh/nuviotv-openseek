@file:OptIn(ExperimentalTvMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.openseek.OpenSeekRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class OpenSeekPlayerViewModel @Inject constructor(
    val repository: OpenSeekRepository
) : ViewModel()

private val thumbWidth = 240.dp
private val thumbHeight = 135.dp

/**
 * Starts the OpenSeek preview session for the video that is playing and stops it when the player closes.
 * Does nothing (no request is made) unless OpenSeek is enabled and paired.
 */
@Composable
fun OpenSeekSessionEffect(viewModel: PlayerViewModel) {
    val repo = hiltViewModel<OpenSeekPlayerViewModel>().repository
    LaunchedEffect(viewModel) {
        viewModel.playbackTimeline
            .map { if (it.isLive) 0L else it.duration }
            .distinctUntilChanged()
            .debounce(1500)
            .collect { duration ->
                if (duration >= 60_000) {
                    val req = viewModel.openSeekRequest(duration)
                    if (req != null) repo.startSession(req) else repo.stopSession()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { repo.stopSession() } }
}

/** Where the thumbnail position comes from. */
enum class OpenSeekPreviewSource {
    /** Controls visible: the position being scrubbed to (set while a key is held, cleared on release). */
    Scrub,
    /** Quick-seek overlay (controls hidden): the playhead, which jumps when the viewer seeks. */
    Playhead
}

/**
 * The thumbnail shown above the seek bar while the viewer scrubs. Place it in the same Box as the bar
 * (it draws above the bar's top edge). Draws nothing when OpenSeek has no preview for this video.
 * After the viewer lets go it stays for a moment so a single tap still shows where it landed.
 */
@Composable
fun OpenSeekThumbnailOverlay(viewModel: PlayerViewModel, formatTime: (Long) -> String, source: OpenSeekPreviewSource = OpenSeekPreviewSource.Scrub) {
    val repo = hiltViewModel<OpenSeekPlayerViewModel>().repository
    val session by repo.session.collectAsState()
    if (!session.ready) return

    val liveFlow = remember(viewModel, source) {
        if (source == OpenSeekPreviewSource.Scrub) viewModel.uiState.map { it.pendingPreviewSeekPosition }.distinctUntilChanged()
        else viewModel.playbackTimeline.map { it.currentPosition / 1000 * 1000 }.distinctUntilChanged()
    }
    val live by liveFlow.collectAsState(initial = null)
    val durationFlow = remember(viewModel) { viewModel.playbackTimeline.map { it.duration }.distinctUntilChanged() }
    val duration by durationFlow.collectAsState(initial = 0L)
    var shownPos by remember { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(false) }
    var thumb by remember { mutableStateOf<OpenSeekRepository.Thumb?>(null) }

    LaunchedEffect(live) {
        val l = live
        if (l != null) {
            shownPos = l; visible = true
            repo.scrubHint(l, viewModel.playbackTimeline.value.currentPosition)
        } else if (shownPos != null) {
            repo.scrubHint(null, viewModel.playbackTimeline.value.currentPosition)
            delay(1500)                                 // keep the last thumbnail briefly so a single tap still shows
            visible = false
        }
    }
    LaunchedEffect(shownPos, session.cues) {
        val pos = shownPos ?: return@LaunchedEffect
        val cached = repo.cachedThumb(pos)
        if (cached != null) { thumb = cached; return@LaunchedEffect }
        delay(60)                                       // skip images for positions the viewer is flying past
        repo.loadThumb(pos)?.let { thumb = it }
    }

    val pos = shownPos
    val shown = thumb
    if (!visible || pos == null || shown == null || duration <= 0) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(0.dp)) {
        val fraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val x = (maxWidth * fraction - (thumbWidth + 60.dp) / 2).coerceIn(0.dp, (maxWidth - thumbWidth - 60.dp).coerceAtLeast(0.dp))
        Column(
            modifier = Modifier
                .requiredSize(thumbWidth + 60.dp, thumbHeight + 44.dp)
                .offset(x = x, y = -(thumbHeight + 54.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = shown.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(thumbWidth, thumbHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(text = formatTime(pos), color = Color.White, fontSize = 13.sp)
            }
            if (session.label.isNotEmpty()) {
                Text(text = session.label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
