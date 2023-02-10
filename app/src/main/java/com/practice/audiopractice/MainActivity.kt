package com.practice.audiopractice

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.collection.CircularArray
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.BrushPainter

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.egeniq.exovisualizer.FFTAudioProcessor
import com.egeniq.exovisualizer.FFTBandView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.*
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.practice.audiopractice.ui.theme.AudioPracticeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.apache.commons.collections4.queue.CircularFifoQueue
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioPracticeTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    App()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AudioPracticeTheme {
        App()
    }
}


@Composable
fun App() {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var playerState by remember(context) { mutableStateOf(PLAYER_STATE_IDLE) }
    var exoPlayer by remember(context) {
        mutableStateOf<ExoPlayer?>(null)
    }
    var duration by remember(context) { mutableStateOf(0L) }
    var position by remember(context) { mutableStateOf(0f) }
    val mySliderState = remember(context) { MySliderState() }
    val dataflow = remember(context) {
        MutableSharedFlow<FFTDataPack>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }


    LaunchedEffect(dataflow) {
        val size = FFTAudioProcessor.SAMPLE_SIZE / 2
        val bands = FFTBandView.FREQUENCY_BAND_LIMITS.size
        val smoothingFactor = 30
        val previousValues = FloatArray(bands * smoothingFactor)
        dataflow
            .collect { fftDataPack ->
                withContext(Dispatchers.IO) {
                    var currentFftPosition = 0
                    var currentFrequencyBandLimitIndex = 0
                    var currentAverage = 0f

                    // Iterate over the entire FFT result array
                    while (currentFftPosition < size) {
                        var accum = 0f

                        // We divide the bands by frequency.
                        // Check until which index we need to stop for the current band
                        val nextLimitAtPosition =
                            floor(FFTBandView.FREQUENCY_BAND_LIMITS[currentFrequencyBandLimitIndex] / 20_000.toFloat() * size).toInt()


                        // Here we iterate within this single band
                        for (j in 0 until (nextLimitAtPosition - currentFftPosition) step 2) {
                            // Convert real and imaginary part to get energy
                            val raw = (fftDataPack.fft[currentFftPosition + j].toDouble().pow(2.0) +
                                    fftDataPack.fft[currentFftPosition + j + 1].toDouble()
                                        .pow(2.0)).toFloat()

                            // Hamming window (by frequency band instead of frequency, otherwise it would prefer 10kHz, which is too high)
                            // The window mutes down the very high and the very low frequencies, usually not hearable by the human ear
                            val m = bands / 2
                            val windowed =
                                raw * (0.54f - 0.46f * cos(2 * Math.PI * currentFrequencyBandLimitIndex / (m + 1))).toFloat()
                            accum += windowed
                        }

                        // A window might be empty which would result in a 0 division
                        if (nextLimitAtPosition - currentFftPosition != 0) {
                            accum /= (nextLimitAtPosition - currentFftPosition)
                        } else {
                            accum = 0.0f
                        }
                        currentFftPosition = nextLimitAtPosition

                        // Here we do the smoothing
                        // If you increase the smoothing factor, the high shoots will be toned down, but the
                        // 'movement' in general will decrease too
                        var smoothedAccum = accum
                        for (i in 0 until smoothingFactor) {
                            smoothedAccum += previousValues[i * bands + currentFrequencyBandLimitIndex]
                            if (i != smoothingFactor - 1) {
                                previousValues[i * bands + currentFrequencyBandLimitIndex] =
                                    previousValues[(i + 1) * bands + currentFrequencyBandLimitIndex]
                            } else {
                                previousValues[i * bands + currentFrequencyBandLimitIndex] = accum
                            }
                        }
                        smoothedAccum /= (smoothingFactor + 1) // +1 because it also includes the current value

                        // We display the average amplitude with a vertical line
                        currentAverage += smoothedAccum / bands
                        currentFrequencyBandLimitIndex++
                    }
                    Log.e("TAG", "App: currentAverage$currentAverage")
                    mySliderState.avgVolume = if (playerState == PLAYER_STATE_PLAYING) {
                        (currentAverage / 1000f).let { if (it >= 1f) 1f else if (it <= 0f) 0f else it }
                    } else {
                        0f
                    }
                    delay(16)

                }
            }
    }



    DisposableEffect(true) {
        onDispose {
            kotlin.runCatching {
                exoPlayer!!.playWhenReady = false
            }
            kotlin.runCatching {
                exoPlayer!!.release()
            }
            exoPlayer = null
        }
    }
    LaunchedEffect(context) {
        playerState = PLAYER_STATE_IDLE
        withContext(Dispatchers.Main) {
            kotlin.runCatching {
                exoPlayer?.release()
            }
        }
        withContext(Dispatchers.IO) {
            exoPlayer = null
            var mediaSource: MediaSource? = null
            val ce = try {
                val playUrl = "asset:///sound.mp3"
                mediaSource = createExoPlayerMediaSource(
                    context,
                    playUrl
                ) ?: throw Exception("未知错误")
                null
            } catch (e: Exception) {
                e
            }


            if (ce != null) {
                playerState = PLAYER_STATE_ERROR
            } else {
                kotlin.runCatching {
                    exoPlayer =
                        withContext(Dispatchers.Main) {
                            kotlin.runCatching {
                                createExoPlayer(context) { sampleRateHz: Int, channelCount: Int, fft: FloatArray ->

                                    dataflow.tryEmit(FFTDataPack().apply {
                                        this.sampleRateHz = sampleRateHz
                                        this.channelCount = channelCount
                                        this.fft = fft.copyOf()
                                    }).let { Log.e("TAG", "App: emit $it") }
                                }
                                    .apply {
                                        setMediaSource(mediaSource!!, false)
                                        prepare()
                                        playWhenReady = true
                                        addListener(object : Player.Listener {


                                            override fun onEvents(
                                                player: Player,
                                                events: Player.Events
                                            ) {

                                            }

                                            override fun onPlaybackStateChanged(
                                                playbackState: Int
                                            ) {
                                                when (playbackState) {
                                                    Player.STATE_ENDED -> {
                                                        this@apply.seekTo(0)
                                                    }


                                                }


                                            }

                                            override fun onPlayerError(error: PlaybackException) {
                                                super.onPlayerError(error)
                                                Log.e(
                                                    "PLAYERTAG",
                                                    "onPlayerError: " + Log.getStackTraceString(
                                                        error
                                                    )
                                                )
                                            }
                                        })
                                    }
                            }.getOrNull()
                        }
                    if (exoPlayer == null) throw Exception("播放器创建失败")
                    playerState = PLAYER_STATE_PLAYING
                }.getOrElse {
                    playerState = PLAYER_STATE_ERROR
                    withContext(Dispatchers.Main) {
                        kotlin.runCatching {
                            exoPlayer?.release()
                        }
                    }
                    exoPlayer = null
                }
            }
        }
    }
    val elapsed by remember(context) {
        derivedStateOf {
            (position * duration).toLong().let { ms -> ms - ms % 1000 }
        }
    }
    when (playerState) {
        PLAYER_STATE_IDLE -> {
            Box(
                modifier = Modifier.fillMaxSize().background(color = Color(0xff000000))
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp).align(Alignment.Center)
                )
            }
        }
        PLAYER_STATE_ERROR -> {
            Box(
                modifier = Modifier.fillMaxSize().background(color = Color(0xff000000))
            ) {
                Text(text = "播放器错误", color = Color(0xffffffff), fontSize = 20.sp)
            }
        }
        else -> {
            LaunchedEffect(context) {
                while (true) {
                    mySliderState.frameCount++
                    delay(16)
                }
            }


            LaunchedEffect(context) {
                while (true) {
                    try {
                        withContext(Dispatchers.Main) {
                            if (!mySliderState.userOperating) {
                                kotlin.runCatching {
                                    position =
                                        exoPlayer!!.contentPosition.toFloat() / exoPlayer!!.duration
                                    duration = exoPlayer!!.duration
                                }
                            }
                        }
                        delay(16)
                    } catch (e: Exception) {
                        break
                    }
                }
            }




            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).background(color=Color(0xff003265))
                        .align(Alignment.Center)
                ) {
                    Box(modifier = Modifier.size(40.dp).clickable {

                        when (playerState) {
                            PLAYER_STATE_PLAYING -> {
                                kotlin.runCatching {
                                    exoPlayer!!.playWhenReady = false
                                }
                                playerState =
                                    PLAYER_STATE_STOP
                                mySliderState.avgVolume = 0f
                            }
                            PLAYER_STATE_STOP -> {
                                kotlin.runCatching {
                                    exoPlayer!!.playWhenReady = true
                                }
                                playerState =
                                    PLAYER_STATE_PLAYING
                            }
                        }


                    }) {
                        if (playerState == PLAYER_STATE_PLAYING) {
                            Image(
                                painter = painterResource(R.drawable.ic_pause),
                                modifier = Modifier.size(21.dp)
                                    .align(Alignment.Center),
                                contentDescription = ""
                            )
                        } else {
                            Image(
                                painter = painterResource(R.drawable.ic_play),
                                modifier = Modifier.size(21.dp)
                                    .align(Alignment.Center),
                                contentDescription = ""
                            )
                        }
                    }
                    Box(modifier = Modifier.width(39.dp).fillMaxHeight()) {
                        Text(
                            text = elapsed.durationTimeText(),
                            color = Color(0xffffffff),
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MySlider(
                            modifier = Modifier.fillMaxSize(),
                            state = mySliderState
                        ) { p, end ->
                            mySliderState.position = p
                            if (end) {
                                kotlin.runCatching {
                                    exoPlayer!!.seekTo((exoPlayer!!.duration * mySliderState.position).toLong())
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.width(39.dp).fillMaxHeight()) {
                        Text(
                            text = duration.durationTimeText(),
                            color = Color(0xffffffff),
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }


            }


        }
    }

}


const val PLAYER_STATE_IDLE = 0
const val PLAYER_STATE_PLAYING = 1
const val PLAYER_STATE_STOP = 2
const val PLAYER_STATE_ERROR = 3

fun createExoPlayer(
    context: Context, audioFFTCallback: (
        sampleRateHz: Int,
        channelCount: Int,
        fft: FloatArray
    ) -> Unit
): ExoPlayer {
    val trackSelectionFactory = AdaptiveTrackSelection.Factory()
    val renderersFactory = object : DefaultRenderersFactory(context) {

        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>
        ) {
            out.add(
                MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    DefaultAudioSink(
                        AudioCapabilities.getCapabilities(context),
                        arrayOf(FFTAudioProcessor().apply {
                            listener = object : FFTAudioProcessor.FFTListener {
                                override fun onFFTReady(
                                    sampleRateHz: Int,
                                    channelCount: Int,
                                    fft: FloatArray
                                ) {
                                    audioFFTCallback(sampleRateHz, channelCount, fft)
                                }
                            }
                        })
                    )
                )
            )

            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
            )
        }
    }
    val trackSelector = DefaultTrackSelector(/* context= */ context, trackSelectionFactory)
    trackSelector.parameters =
        DefaultTrackSelector.ParametersBuilder(/* context= */context).build()
    return ExoPlayer.Builder(context, renderersFactory).setTrackSelector(trackSelector)
        .build()
        .apply {
            addListener(object : Player.Listener {

            })
            setAudioAttributes(
                com.google.android.exoplayer2.audio.AudioAttributes.DEFAULT,
                true
            )
            playWhenReady = false
        }

}

fun createExoPlayerMediaSource(
    context: Context,
    url: String
): MediaSource {
    if (url.isEmpty()) throw Exception()
    val defaultHttpDataSourceFactory = DefaultHttpDataSource.Factory()
    val dataSourceFactory = DefaultDataSource.Factory(context, defaultHttpDataSourceFactory);
    return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(
        MediaItem.fromUri(Uri.parse(url))
    )
}

fun Long.durationTimeText(): String {
    if (this < 0) {
        return "00:00";
    }
    val totalSec = this / 1000;
    val min = (totalSec / 60);
    val second = totalSec % 60
    return "${min.let { if (it < 10) "0$it" else "$it" }}:${second.let { if (it < 10) "0$it" else "$it" }}"
}


class FFTDataPack {
    var sampleRateHz: Int = 0
    var channelCount: Int = 0
    var fft: FloatArray = FloatArray(0)
}


@Composable
fun MySlider(
    modifier: Modifier = Modifier,
    state: MySliderState = remember { MySliderState() },
    onPositionChange: (position: Float, end: Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
    ) {
        val thumb = ImageBitmap.imageResource(R.drawable.ic_cat)
        val canvasWidth = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val centerVertical = canvasHeight / 2f
        val range = canvasWidth * 0.8f
        val padStart = (canvasWidth - range) / 2f
        val thumbSize =
            IntSize((canvasHeight * 0.4f * 160f / 99f).toInt(), (canvasHeight * 0.4f).toInt())
        val audioAvgVolumeHistory =
            remember { CircularFifoQueue<Float>((canvasWidth * 0.5f).toInt()) }
        Log.e("TAG", "ABSlider: canvas width $canvasWidth height $canvasHeight")
        val ranbow = ImageBitmap.imageResource(id = R.drawable.ic_rainbow)
        val ranbowSize =
            IntSize(thumbSize.width, (thumb.height / 6f).toInt())
        val star1 = ImageBitmap.imageResource(id = R.drawable.ic_star1)
        val star2 = ImageBitmap.imageResource(id = R.drawable.ic_star2)
        val star3 = ImageBitmap.imageResource(id = R.drawable.ic_star3)
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput("tap") {
                    detectTapGestures { offset ->
                        val offsetPercent =
                            (offset.x - padStart).let {
                                if (it > 0) {
                                    if (it < range)
                                        it
                                    else
                                        range
                                } else 0f
                            } / range

                        onPositionChange(offsetPercent, true)

                    }
                }.pointerInput("drag") {
                    Log.e("TAG", "ABSlider: scope $this")
                    detectDragGestures(
                        onDragStart = { offset ->

                            val offsetPercent =
                                (offset.x - padStart).let {
                                    if (it > 0) {
                                        if (it < range)
                                            it
                                        else
                                            1f
                                    } else 0f
                                } / range


                            state.dragTarget = "t"
                            onPositionChange(offsetPercent, false)

                            state.userOperating = true
                            Log.e("TAG", "ABSlider: drag target ${state.dragTarget}")

                        },
                        onDragCancel = {
                            when (state.dragTarget) {
                                "t" -> onPositionChange(
                                    state.position,
                                    true
                                )
                            }
                            state.dragTarget = ""
                            state.userOperating = false
                        },
                        onDragEnd = {
                            when (state.dragTarget) {
                                "t" -> onPositionChange(
                                    state.position,
                                    true
                                )
                            }
                            state.dragTarget = ""
                            state.userOperating = false
                        }) { change, dragAmount ->
                        Log.e("TAG", "ABSlider: dragAmount" + dragAmount)
                        state.userOperating = true
                        when (state.dragTarget) {
                            "t" -> {
                                val delta = dragAmount.x.absoluteValue / range
                                val newPosition = if (dragAmount.x > 0) {
                                    (state.position + delta).let {
                                        if (it >= 1f) 1f else it
                                    }
                                } else {
                                    (state.position - delta).let {
                                        if (it <= 0f) 0f else it
                                    }
                                }
                                onPositionChange(newPosition, false)
                            }
                        }

                    }
                }
        ) {
            drawRect(color = Color(0xff003265))
            when ((state.frameCount / 10) % 3) {
                0L -> {
                    drawImage(star1, dstOffset = IntOffset(10, 10), dstSize = IntSize(30, 30))
                }
                1L -> {
                    drawImage(star2, dstOffset = IntOffset(10, 10), dstSize = IntSize(30, 30))
                }
                2L -> {
                    drawImage(star3, dstOffset = IntOffset(10, 10), dstSize = IntSize(30, 30))
                }
            }



            when ((state.frameCount / 10) % 3) {
                0L -> {
                    drawImage(star3, dstOffset = IntOffset((this.size.width/2f).toInt(),
                        (this.size.height/3f).toInt()
                    ), dstSize = IntSize(30, 30))
                }
                1L -> {
                    drawImage(star2, dstOffset = IntOffset((this.size.width/2f).toInt(),
                        (this.size.height/3f).toInt()), dstSize = IntSize(30, 30))
                }
                2L -> {
                    drawImage(star1, dstOffset = IntOffset((this.size.width/2f).toInt(),
                        (this.size.height/3f).toInt()), dstSize = IntSize(30, 30))
                }
            }


            when ((state.frameCount / 10) % 3) {
                0L -> {
                    drawImage(star2, dstOffset = IntOffset((this.size.width*0.9f).toInt(),
                        (this.size.height*0.5f).toInt()
                    ), dstSize = IntSize(30, 30))
                }
                1L -> {
                    drawImage(star3, dstOffset = IntOffset((this.size.width*0.9f).toInt(),
                        (this.size.height*0.5f).toInt()
                    ), dstSize = IntSize(30, 30))
                }
                2L -> {
                    drawImage(star1, dstOffset = IntOffset((this.size.width*0.9f).toInt(),
                        (this.size.height*0.5f).toInt()
                    ), dstSize = IntSize(30, 30))
                }
            }


            var thumbx = padStart + range * state.position
            thumbx = if (thumbx < padStart) {
                padStart
            } else if (thumbx > (padStart + range)) {
                padStart + range
            } else {
                thumbx
            }
            if (state.frameCount >= 0) {
                Log.e("TAG", "App: frameCount=${state.frameCount}")
                audioAvgVolumeHistory.add(state.avgVolume)
                if (!audioAvgVolumeHistory.isEmpty()) {
                    val size = audioAvgVolumeHistory.size
                    for (i in size - 1 downTo 0) {

                        val v = audioAvgVolumeHistory[i]
                        val endOffset = Offset(
                            (thumbx - (size - i) * 10).toFloat(),
                            this.size.height * (1 - v) - ranbowSize.height / 2f
                        )
                        if (endOffset.x < 0) break


                        if (i != (size - 1)) {
                            drawImage(
                                image = ranbow,
                                dstOffset = IntOffset(endOffset.x.toInt(), endOffset.y.toInt()),
                                dstSize = IntSize(10, ranbowSize.height),
                                alpha = (1f - 1.5f * (thumbx - endOffset.x) / range).let { if (it >= 1f) 1f else if (it <= 0f) 0f else it }
                            )
                        }

                    }
                }
            }


            val v = audioAvgVolumeHistory[audioAvgVolumeHistory.size - 1]
            val thumbY = this.size.height * (1 - v)
            drawImage(
                image = thumb,
                dstSize = thumbSize,
                dstOffset = IntOffset(
                    (thumbx - thumbSize.width / 2).toInt(),
                    (thumbY - thumbSize.height / 2).toInt()
                )
            )


        }
    }
}

class MySliderState {
    var dragTarget = ""
    var position by mutableStateOf(0f)
    var userOperating = false
    var frameCount by mutableStateOf(0L)
    var avgVolume = 0f
}