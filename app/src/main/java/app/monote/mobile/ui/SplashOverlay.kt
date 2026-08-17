package app.monote.mobile.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monote.mobile.ui.theme.Ink900
import app.monote.mobile.ui.theme.Paper100
import app.monote.mobile.ui.theme.Paper200
import app.monote.mobile.ui.theme.Vermilion
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@Composable
internal fun SplashGate(
    initiallyVisible: Boolean? = null,
    splash: @Composable (onFinished: () -> Unit) -> Unit = { onFinished ->
        SplashOverlay(onFinished = onFinished)
    },
    content: @Composable () -> Unit,
) {
    val shouldShow = remember(initiallyVisible) {
        initiallyVisible ?: SplashProcessState.hasShownSplash.compareAndSet(false, true)
    }
    var visible by remember(shouldShow) { mutableStateOf(shouldShow) }
    if (visible) {
        splash { visible = false }
    } else {
        content()
    }
}

@Composable
fun SplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsDisabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    var progress by remember { mutableFloatStateOf(if (animationsDisabled) 1f else 0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(animationsDisabled) {
        if (animationsDisabled) {
            withFrameNanos { }
            currentOnFinished()
            return@LaunchedEffect
        }
        val startedAt = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            progress = ((now - startedAt) / TOTAL_NANOS.toFloat()).coerceIn(0f, 1f)
        } while (progress < 1f)
        currentOnFinished()
    }

    SplashFrame(progress = progress, modifier = modifier)
}

@Composable
private fun SplashFrame(
    progress: Float,
    modifier: Modifier,
) {
    val inkProgress = stage(progress, 0, 180)
    val writingProgress = stage(progress, 180, 420)
    val paperProgress = stage(progress, 420, 680)
    val titleProgress = stage(progress, 680, 850)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Paper100),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val unit = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f - unit * 0.04f)
            drawPaper(center, unit, paperProgress)
            drawInkDrop(center, unit, inkProgress)
            drawHandwrittenM(center, unit, writingProgress)
            drawHash(center, unit, paperProgress)
        }
        Text(
            text = "墨笺 · MoNote",
            color = Ink900,
            fontFamily = FontFamily.Serif,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .offset(y = 118.dp)
                .graphicsLayer { alpha = titleProgress },
        )
    }
}

private fun DrawScope.drawInkDrop(center: Offset, unit: Float, progress: Float) {
    if (progress <= 0f) return
    val radius = unit * 0.022f * (0.45f + progress * 0.55f)
    drawCircle(
        color = Ink900.copy(alpha = progress),
        radius = radius,
        center = center.copy(y = center.y - unit * 0.16f),
    )
}

private fun DrawScope.drawPaper(center: Offset, unit: Float, progress: Float) {
    if (progress <= 0f) return
    val width = unit * 0.30f
    val height = unit * 0.24f
    val scale = 0.2f + 0.8f * progress
    withTransform({
        rotate(degrees = 12f * (1f - progress), pivot = center)
        scale(scaleX = scale, scaleY = scale, pivot = center)
    }) {
        drawRoundRect(
            color = Paper200,
            topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
            size = Size(width, height),
            cornerRadius = CornerRadius(unit * 0.025f),
        )
    }
}

private fun DrawScope.drawHandwrittenM(center: Offset, unit: Float, progress: Float) {
    if (progress <= 0f) return
    val points = listOf(
        Offset(center.x - unit * 0.105f, center.y + unit * 0.055f),
        Offset(center.x - unit * 0.075f, center.y - unit * 0.060f),
        Offset(center.x, center.y + unit * 0.015f),
        Offset(center.x + unit * 0.072f, center.y - unit * 0.060f),
        Offset(center.x + unit * 0.108f, center.y + unit * 0.055f),
    )
    val segmentProgress = progress * points.lastIndex
    for (index in 0 until points.lastIndex) {
        val amount = (segmentProgress - index).coerceIn(0f, 1f)
        if (amount <= 0f) break
        val start = points[index]
        val end = points[index + 1]
        drawLine(
            color = Ink900,
            start = start,
            end = Offset(
                x = start.x + (end.x - start.x) * amount,
                y = start.y + (end.y - start.y) * amount,
            ),
            strokeWidth = unit * 0.012f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawHash(center: Offset, unit: Float, progress: Float) {
    if (progress <= 0f) return
    val color = Vermilion.copy(alpha = progress)
    val x = center.x + unit * 0.105f
    val y = center.y - unit * 0.078f
    val short = unit * 0.030f
    val long = unit * 0.043f
    val width = unit * 0.006f
    drawLine(color, Offset(x - short, y - long), Offset(x - short, y + long), width, StrokeCap.Round)
    drawLine(color, Offset(x + short, y - long), Offset(x + short, y + long), width, StrokeCap.Round)
    drawLine(color, Offset(x - long, y - short), Offset(x + long, y - short), width, StrokeCap.Round)
    drawLine(color, Offset(x - long, y + short), Offset(x + long, y + short), width, StrokeCap.Round)
}

private fun stage(progress: Float, startMillis: Int, endMillis: Int): Float {
    val millis = progress * TOTAL_MILLIS
    return ((millis - startMillis) / (endMillis - startMillis)).coerceIn(0f, 1f)
}

private object SplashProcessState {
    val hasShownSplash = AtomicBoolean(false)
}

private const val TOTAL_MILLIS = 850f
private const val TOTAL_NANOS = 850_000_000L
