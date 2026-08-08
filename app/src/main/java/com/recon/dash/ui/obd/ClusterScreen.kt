package com.recon.dash.ui.obd

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.obd.EngineState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import kotlin.math.cos
import kotlin.math.sin

// Blade-Runner / cyberpunk palette: deep black, neon cyan primary, magenta + amber accents.
private val BG = Color(0xFF05070A)
private val CYAN = Color(0xFF00E5FF)
private val MAGENTA = Color(0xFFFF2D95)
private val AMBER = Color(0xFFFFB000)
private val DIM = Color(0xFF14323C)
private const val RPM_MAX = 8000f

@Composable
fun ClusterScreen(
    onBack: () -> Unit,
    viewModel: ClusterViewModel = hiltViewModel(),
) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()

    // Tween the needle values between 1 Hz-ish emissions so the arc/readouts glide, not step.
    val rpm by animateFloatAsState(
        targetValue = (engine.rpm ?: 0).toFloat().coerceIn(0f, RPM_MAX),
        animationSpec = tween(400), label = "rpm",
    )
    val speed by animateFloatAsState(
        targetValue = (engine.speedKmh ?: 0).toFloat(),
        animationSpec = tween(400), label = "speed",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(BG),
        contentAlignment = Alignment.Center,
    ) {
        ClusterCanvas(rpm = rpm, speedKmh = speed, engine = engine)

        // Back button (top-left), unobtrusive.
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Rounded.ArrowBack, "Back", tint = CYAN.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ClusterCanvas(rpm: Float, speedKmh: Float, engine: EngineState) {
    Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) * 0.38f

        // ── RPM arc (270° sweep, -225° start), glowing cyan, redline magenta ──
        val startAngle = 135f      // bottom-left
        val sweepMax = 270f
        val stroke = Stroke(width = 22f, cap = StrokeCap.Round)
        val arcTopLeft = Offset(cx - radius, cy - radius)
        val arcSize = Size(radius * 2, radius * 2)

        // Track (dim).
        drawArc(color = DIM, startAngle = startAngle, sweepAngle = sweepMax,
            useCenter = false, topLeft = arcTopLeft, size = arcSize, style = stroke)

        // Redline zone (last ~18%, from ~6500 rpm) in magenta.
        val redlineFrac = 6500f / RPM_MAX
        drawArc(color = MAGENTA.copy(alpha = 0.35f),
            startAngle = startAngle + sweepMax * redlineFrac,
            sweepAngle = sweepMax * (1 - redlineFrac),
            useCenter = false, topLeft = arcTopLeft, size = arcSize, style = stroke)

        // Active fill up to current rpm — glow via a couple of stacked strokes.
        val frac = (rpm / RPM_MAX).coerceIn(0f, 1f)
        val activeColor = if (rpm >= 6500f) MAGENTA else CYAN
        for ((w, a) in listOf(38f to 0.15f, 28f to 0.35f, 22f to 1f)) {
            drawArc(color = activeColor.copy(alpha = a), startAngle = startAngle,
                sweepAngle = sweepMax * frac, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(width = w, cap = StrokeCap.Round))
        }

        // Tick marks every 1000 rpm.
        for (i in 0..8) {
            val a = Math.toRadians((startAngle + sweepMax * (i / 8f)).toDouble())
            val r0 = radius - 30f; val r1 = radius - 14f
            drawLine(
                color = CYAN.copy(alpha = 0.5f),
                start = Offset(cx + (r0 * cos(a)).toFloat(), cy + (r0 * sin(a)).toFloat()),
                end = Offset(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat()),
                strokeWidth = 3f,
            )
        }

        // ── Center readouts (native canvas text for glow + size control) ──
        val nc = drawContext.canvas.nativeCanvas
        fun text(s: String, x: Float, y: Float, size: Float, color: Color, glow: Color, bold: Boolean = true) {
            val p = android.graphics.Paint().apply {
                isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                textSize = size; this.color = color.toArgb()
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setShadowLayer(size * 0.35f, 0f, 0f, glow.toArgb())
            }
            nc.drawText(s, x, y, p)
        }
        // Big speed (the hero number).
        text("${speedKmh.toInt()}", cx, cy + 20f, radius * 0.9f, Color.White, CYAN)
        text("KM/H", cx, cy + radius * 0.55f, radius * 0.16f, CYAN, CYAN)
        // RPM small, above.
        text("${rpm.toInt()} RPM", cx, cy - radius * 0.45f, radius * 0.18f,
            if (rpm >= 6500f) MAGENTA else CYAN, if (rpm >= 6500f) MAGENTA else CYAN)

        // ── Bottom bars: coolant (amber) + fuel-rate (magenta) ──
        val barY = size.height - 8f
        val barW = size.width * 0.38f
        engine.coolantC?.let { c ->
            drawStatBar(cx - barW - 16f, barY, barW, "COOLANT", "$c°C",
                (c / 110f).coerceIn(0f, 1f), AMBER, nc)
        }
        engine.fuelInstL100?.let { f ->
            drawStatBar(cx + 16f, barY, barW, "FUEL L/100", "%.1f".format(f),
                (f.toFloat() / 15f).coerceIn(0f, 1f), MAGENTA, nc)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStatBar(
    x: Float, y: Float, w: Float, label: String, value: String, frac: Float,
    color: Color, nc: android.graphics.Canvas,
) {
    val h = 8f
    drawRect(color = DIM, topLeft = Offset(x, y - h), size = Size(w, h))
    drawRect(color = color, topLeft = Offset(x, y - h), size = Size(w * frac, h))
    val p = android.graphics.Paint().apply {
        isAntiAlias = true; textSize = 26f; this.color = color.toArgb()
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(8f, 0f, 0f, color.toArgb())
    }
    nc.drawText("$label  $value", x, y - h - 12f, p)
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
