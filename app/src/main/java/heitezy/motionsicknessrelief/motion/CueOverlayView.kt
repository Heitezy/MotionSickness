// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.View
import heitezy.motionsicknessrelief.data.CueSettings
import heitezy.motionsicknessrelief.data.DotShape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How the dot sizes scale with screen position.
 * - [Uniform]: every dot at the same base size, covering the whole screen.
 * - [Focus]: dots fade to zero near the center and reach full size at the edges/corners, so
 *   the middle of the screen stays clear for content and motion cues stay in peripheral vision.
 * - [Columns]: dots only appear in a couple of columns hugging the left and right edges —
 *   matches how Android's own Motion Assist actually lays cues out (screenshots of the
 *   shipped feature show 2–3 dot columns per side, not a full radial falloff).
 */
enum class CueMode { Uniform, Focus, Columns }

/**
 * Overlay view that renders the infinite dot field with a hardware-accelerated `Canvas`.
 *
 * Drawing through the View hierarchy (rather than a SurfaceView/GLSurfaceView) matters for more
 * than aesthetics: on Android 12+, only pixels that go through the window's compositor are
 * dimmed by `WindowManager.LayoutParams.alpha`. A SurfaceView renders into a separate
 * SurfaceFlinger layer at 100% opacity regardless of window alpha, so the OS still flags
 * touches as obscured and blocks them from reaching the app below. Canvas-drawn pixels are
 * part of the window and are dimmed correctly, which is what lets the touch-through trick
 * work.
 *
 * The physics runs in a "leveled" isotropic frame whose +y axis tracks world-up projected
 * onto the screen. Positions rotate to screen coords at draw time. One iso unit = half the
 * shorter screen dimension, so rotation is rigid in pixel space regardless of viewport aspect.
 */
class CueOverlayView(context: Context) : View(context) {

    @Volatile private var motionX = 0f
    @Volatile private var motionY = 0f
    @Volatile private var motionOutOfPlane = 0f
    @Volatile private var rollRadians = 0f
    @Volatile private var yawRateRps = 0f
    @Volatile private var pitchRateRps = 0f

    private var smoothedMotionX = 0f
    private var smoothedMotionY = 0f
    private var smoothedMotionOutOfPlane = 0f
    private var smoothedRollCos = 1f
    private var smoothedRollSin = 0f
    private var smoothedYawRateRps = 0f
    private var smoothedPitchRateRps = 0f

    private val pixelDensity = context.resources.displayMetrics.density

    // Grid dimension is configurable (CueSettings.density), so the particle array is rebuilt
    // whenever it changes rather than sized once from a fixed constant.
    private var gridDim = DEFAULT_GRID_DIM
    private var particles = Array(gridDim * gridDim) { Particle() }
    private var halfShortDim = 1f
    private var halfDiag = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var screenWidth = 1f

    @Volatile var mode: CueMode = CueMode.Focus

    // Appearance, driven by CueSettings. [colorPalette] always holds the three theme ARGB
    // colors (primary/secondary/tertiary) so the randomizer can cycle between them even while
    // a single fixed slot is configured.
    @Volatile private var configuredShape: DotShape = DotShape.Circle
    @Volatile private var configuredColorArgb: Int = 0xFFFFFFFF.toInt()
    @Volatile private var colorPalette: IntArray = intArrayOf(0xFFFFFFFF.toInt())
    @Volatile private var randomize: Boolean = false
    @Volatile private var baseAlpha: Int = 217 // 0.85 * 255, matches CueSettings.DEFAULT_OPACITY
    @Volatile private var dotSizeScale: Float = 1f
    @Volatile private var intensity: Float = 1f

    // What's actually drawn this frame — equal to the configured shape/color unless the
    // randomizer has picked something else.
    @Volatile private var activeShape: DotShape = DotShape.Circle
    private var randomizeAccumSec = 0f
    private val randomRng = Random(System.nanoTime())

    private var gridVx = 0f
    private var gridVy = 0f
    private var gridOx = 0f
    private var gridOy = 0f
    private var sizeEnvelope = 0f

    // Vertical grid velocity driven specifically by signed out-of-plane (forward/back) motion,
    // separate from gridVx/gridVy's in-plane driving. Positive out-of-plane (deceleration/
    // braking) scrolls the grid up; negative (acceleration) scrolls it down — matching Apple's
    // documented Vehicle Motion Cues behavior, where accelerating moves dots down and braking
    // moves them up. This runs alongside, not instead of, the size-pulse cue below: direction
    // carries the sign of the event, the pulse carries its magnitude regardless of sign.
    private var outOfPlaneGridVy = 0f

    private var lastFrameNs = 0L
    private var running = false

    private val paint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        alpha = 217 // 0.85 * 255
        style = Paint.Style.FILL
    }

    private val shapePath = Path()

    init {
        setBackgroundColor(0)
        isClickable = false
        isFocusable = false
    }

    fun setMotion(m: MotionVector) {
        motionX = m.x
        motionY = m.y
        motionOutOfPlane = m.outOfPlane
        rollRadians = m.rollRadians
        yawRateRps = m.yawRateRps
        pitchRateRps = m.pitchRateRps
    }

    /**
     * Applies a full [CueSettings] snapshot plus the resolved theme [palette] (primary,
     * secondary, tertiary ARGB, matching [heitezy.motionsicknessrelief.data.CueColorSlot.ordinal]).
     * Safe to call every time settings change, including from a background collector.
     */
    fun applySettings(settings: CueSettings, palette: IntArray) {
        mode = settings.mode
        configuredShape = settings.shape
        colorPalette = if (palette.isNotEmpty()) palette else intArrayOf(configuredColorArgb)
        configuredColorArgb = palette.getOrElse(settings.colorSlot.ordinal) { configuredColorArgb }
        randomize = settings.randomize
        baseAlpha = (settings.opacity.coerceIn(0f, 1f) * 255).toInt()
        dotSizeScale = settings.dotSizeScale.coerceIn(
            CueSettings.MIN_DOT_SIZE_SCALE,
            CueSettings.MAX_DOT_SIZE_SCALE,
        )
        intensity = settings.intensity.coerceIn(CueSettings.MIN_INTENSITY, CueSettings.MAX_INTENSITY)
        if (!randomize) {
            activeShape = configuredShape
            setPaintColor(configuredColorArgb)
        } else {
            paint.alpha = baseAlpha
        }

        val newDim = settings.density.gridDim
        if (newDim != gridDim) {
            gridDim = newDim
            resetParticles()
        }
    }

    /**
     * `Paint.setColor(argb)` also overwrites the paint's alpha with that color's own alpha
     * channel — and theme colors are fully opaque — so every call site that changes the dot
     * color must re-apply [baseAlpha] afterward, or the opacity setting silently gets clobbered
     * back to 100%. Centralizing that here so it can't be forgotten at a new call site.
     */
    private fun setPaintColor(argb: Int) {
        paint.color = argb
        paint.alpha = baseAlpha
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        halfShortDim = min(w, h) / 2f
        halfDiag = sqrt((w.toFloat() * w + h.toFloat() * h)) / 2f
        centerX = w / 2f
        centerY = h / 2f
        screenWidth = w.toFloat()
        resetParticles()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 1f / 60f
                     else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameNs = frameTimeNanos
            applyInputSmoothing(dt)
            step(dt)
            updateSizeEnvelope(dt)
            updateRandomize(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val sizeBoost = (1f + sizeEnvelope).coerceIn(1f, SIZE_BOOST_MAX)
        val cosR = smoothedRollCos
        val sinR = smoothedRollSin
        val ext = GRID_EXTENT
        val span = 2f * ext

        val focusMode = mode == CueMode.Focus
        val columnsMode = mode == CueMode.Columns
        val shape = activeShape

        // Column band width, in pixels: a fixed fraction of screen width per edge. (Earlier
        // version derived this from the wrap-grid's own dot pitch, but that pitch is ~29% of
        // screen width here, so a "2.5 columns" band worked out wider than half the screen —
        // the fade never actually reached zero before the center, making Columns mode
        // indistinguishable from Uniform. Anchoring to screen width directly avoids that.)
        val columnBandOuterPx = COLUMN_BAND_OUTER_FRAC * screenWidth
        val columnBandInnerPx = COLUMN_BAND_INNER_FRAC * screenWidth

        // Meteoroid streaks are elongated along the on-screen travel direction, computed once
        // per frame from the grid's velocity (same rotation the positions themselves get).
        var streakAngleDeg = 0f
        var streakStretch = 0f
        if (shape == DotShape.Meteoroid) {
            val velSx = gridVx * cosR + gridVy * sinR
            val velSy = -(-gridVx * sinR + gridVy * cosR) // matches the py sign flip below
            val speedPx = sqrt(velSx * velSx + velSy * velSy) * halfShortDim
            if (speedPx > 1e-3f) streakAngleDeg = Math.toDegrees(atan2(velSy.toDouble(), velSx.toDouble())).toFloat()
            streakStretch = (speedPx * METEOR_STRETCH_GAIN).coerceIn(0f, METEOR_STRETCH_MAX)
        }

        for (p in particles) {
            // Apply global grid offset and wrap into the toroidal iso square.
            val lx = wrap(p.homeX + gridOx, ext, span)
            val ly = wrap(p.homeY + gridOy, ext, span)
            // Rigid rotation in iso space.
            val sx = lx * cosR + ly * sinR
            val sy = -lx * sinR + ly * cosR
            // Iso → pixels via uniform scale by halfShortDim. Rigid rotation in iso becomes
            // rigid rotation in pixels. Y flipped (pixel +y is down; iso +y is up).
            val px = centerX + sx * halfShortDim
            val py = centerY - sy * halfShortDim

            var radius = p.size * pixelDensity * sizeBoost * dotSizeScale * 0.5f
            if (focusMode) {
                val dx = px - centerX
                val dy = py - centerY
                // Normalize by half-diagonal → 0 at center, 1 at corners. Smoothstep gives a
                // clear empty zone in the middle and full-size dots only near the edges.
                val distNorm = sqrt(dx * dx + dy * dy) / halfDiag
                val focus = smoothstep(FOCUS_INNER, FOCUS_OUTER, distNorm)
                if (focus <= 0.01f) continue
                radius *= focus
            } else if (columnsMode) {
                // Distance to the *nearer* vertical edge — small near either edge, large in
                // the middle — so both left and right columns light up symmetrically.
                val distFromEdgeX = min(px, screenWidth - px)
                val columnFactor = 1f - smoothstep(columnBandInnerPx, columnBandOuterPx, distFromEdgeX)
                if (columnFactor <= 0.01f) continue
                radius *= columnFactor
            }

            when (shape) {
                DotShape.Circle -> canvas.drawCircle(px, py, radius, paint)
                DotShape.Diamond -> drawDiamond(canvas, px, py, radius)
                DotShape.Meteoroid -> drawMeteoroid(canvas, px, py, radius, streakAngleDeg, streakStretch)
            }
        }
    }

    private fun drawDiamond(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        shapePath.reset()
        shapePath.moveTo(cx, cy - r)
        shapePath.lineTo(cx + r, cy)
        shapePath.lineTo(cx, cy + r)
        shapePath.lineTo(cx - r, cy)
        shapePath.close()
        canvas.drawPath(shapePath, paint)
    }

    /** A capsule stretched along [angleDeg], approximating a comet-like streaking dot. */
    private fun drawMeteoroid(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float, stretch: Float) {
        val halfLen = r * (1f + stretch)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        canvas.drawRoundRect(-halfLen, -r, halfLen, r, r, r, paint)
        canvas.restore()
    }

    private fun updateRandomize(dt: Float) {
        if (!randomize) {
            randomizeAccumSec = 0f
            return
        }
        randomizeAccumSec += dt
        if (randomizeAccumSec < RANDOMIZE_INTERVAL_SEC) return
        randomizeAccumSec = 0f
        val shapes = DotShape.entries
        activeShape = shapes[randomRng.nextInt(shapes.size)]
        setPaintColor(colorPalette[randomRng.nextInt(colorPalette.size)])
    }

    private fun step(dt: Float) {
        val cosR = smoothedRollCos
        val sinR = smoothedRollSin
        val driveLx = smoothedMotionX * cosR - smoothedMotionY * sinR
        val driveLy = smoothedMotionX * sinR + smoothedMotionY * cosR

        gridVx += (-driveLx * DRIVE_GAIN * intensity) * dt
        gridVy += (-driveLy * DRIVE_GAIN * intensity) * dt

        // Signed, directional response to forward/back (out-of-plane) motion: braking
        // (positive out-of-plane) scrolls the grid up, accelerating (negative) scrolls it
        // down. Previously only
        // the positive half of this signal did anything (see updateSizeEnvelope below), so
        // braking produced no visual cue at all; this restores the missing half as an actual
        // direction rather than folding it into the symmetric size pulse.
        outOfPlaneGridVy += (-smoothedMotionOutOfPlane * OUT_OF_PLANE_DRIVE_GAIN * intensity) * dt

        val damping = exp(-DAMP * dt)
        gridVx *= damping
        gridVy *= damping
        outOfPlaneGridVy *= damping
        gridOx += gridVx * dt
        gridOy += (gridVy + outOfPlaneGridVy) * dt

        // Rotation scrolls the grid directly — sustained rotation → sustained flow,
        // stop rotating → flow stops. smoothedYawRateRps already carries MotionEstimator's
        // own GPS-speed scaling (see MotionEstimator.speedFactor); intensity multiplies on
        // top of that as the user's separate, independent sensitivity preference.
        gridOx += smoothedYawRateRps * YAW_GAIN * intensity * dt
        gridOy += smoothedPitchRateRps * PITCH_GAIN * intensity * dt

        val wrapSpan = 2f * GRID_EXTENT
        if (gridOx > GRID_EXTENT) gridOx -= wrapSpan
        else if (gridOx < -GRID_EXTENT) gridOx += wrapSpan
        if (gridOy > GRID_EXTENT) gridOy -= wrapSpan
        else if (gridOy < -GRID_EXTENT) gridOy += wrapSpan
    }

    private fun updateSizeEnvelope(dt: Float) {
        // Magnitude-only: both accelerating and braking are abrupt longitudinal events and
        // both deserve the size pulse. (Previously this used max(0f, ...), which silently
        // dropped one sign of out-of-plane motion — meaning braking produced no cue of any
        // kind, visual or otherwise. Direction is now carried separately by
        // outOfPlaneGridVy in step(); this pulse only communicates magnitude.)
        val target = abs(smoothedMotionOutOfPlane) * SIZE_OUT_OF_PLANE_GAIN * intensity
        if (target > sizeEnvelope) sizeEnvelope = target
        sizeEnvelope *= exp(-dt / SIZE_RELEASE_SEC)
    }

    private fun applyInputSmoothing(dt: Float) {
        val alpha = dt / (SMOOTH_TIME_SEC + dt)
        smoothedMotionX += alpha * (motionX - smoothedMotionX)
        smoothedMotionY += alpha * (motionY - smoothedMotionY)
        smoothedMotionOutOfPlane += alpha * (motionOutOfPlane - smoothedMotionOutOfPlane)

        val targetCos = cos(rollRadians)
        val targetSin = sin(rollRadians)
        smoothedRollCos += alpha * (targetCos - smoothedRollCos)
        smoothedRollSin += alpha * (targetSin - smoothedRollSin)
        val invLen = 1f / sqrt(smoothedRollCos * smoothedRollCos + smoothedRollSin * smoothedRollSin)
        smoothedRollCos *= invLen
        smoothedRollSin *= invLen

        smoothedYawRateRps += alpha * (yawRateRps - smoothedYawRateRps)
        smoothedPitchRateRps += alpha * (pitchRateRps - smoothedPitchRateRps)
    }

    private fun resetParticles() {
        // gridDim can change at runtime (CueSettings.density), so the array is rebuilt here
        // rather than assumed to already be the right size.
        if (particles.size != gridDim * gridDim) {
            particles = Array(gridDim * gridDim) { Particle() }
        }
        val rng = Random(42)
        val spacing = 2f * GRID_EXTENT / gridDim
        var i = 0
        // Staggered layout: odd rows shifted by half a column → hex-like pattern.
        for (row in 0 until gridDim) {
            val rowOffsetX = if (row % 2 == 1) spacing * 0.5f else 0f
            for (col in 0 until gridDim) {
                val nx = (col + 0.5f) * spacing - GRID_EXTENT + rowOffsetX
                val ny = (row + 0.5f) * spacing - GRID_EXTENT
                val jitterX = (rng.nextFloat() - 0.5f) * 0.04f
                val jitterY = (rng.nextFloat() - 0.5f) * 0.04f
                val p = particles[i++]
                p.homeX = nx + jitterX
                p.homeY = ny + jitterY
                p.size = DOT_SIZE_PX * (0.85f + rng.nextFloat() * 0.3f)
            }
        }
    }

    private class Particle {
        var homeX = 0f
        var homeY = 0f
        var size = 0f
    }

    companion object {
        // Default grid dimension when no density preference has been set yet
        // (CueSettings.DotDensity.Normal). The live value is [gridDim], which can be resized
        // at runtime via applySettings().
        private const val DEFAULT_GRID_DIM = 12
        private const val GRID_EXTENT = 3.5f

        private const val DRIVE_GAIN = 0.6f

        // Grid-velocity damping. Lower = the flow from a sustained acceleration (a long
        // highway curve, extended braking) persists longer before decaying to rest; higher =
        // it snaps back to rest faster after a brief jolt but also loses sustained cues sooner.
        //
        // Previously 2.7 (≈370ms time constant), which decays a sustained turn's visual cue
        // to near-zero in about a second — despite sustained low-frequency acceleration being
        // exactly the case most associated with real motion sickness (a car holding a curve
        // for several seconds, not a single bump). 0.9 (≈1.1s time constant) keeps that signal
        // alive for roughly the duration of a real turn or braking event, at the cost of a
        // larger steady-state grid drift if the sensor's residual acceleration bias isn't
        // fully zeroed by MotionEstimator's bias tracking — a trade worth revisiting with
        // real-vehicle testing rather than treating either number as final.
        private const val DAMP = 0.9f

        private const val YAW_GAIN = 1.2f
        private const val PITCH_GAIN = 1.2f
        private const val DOT_SIZE_PX = 8f

        // Directional response to forward/back (out-of-plane) motion — see step(). Tuned
        // independently from DRIVE_GAIN since out-of-plane acceleration is a smaller, noisier
        // signal (a component of a component) than the in-plane horizontal drive.
        private const val OUT_OF_PLANE_DRIVE_GAIN = 0.6f

        private const val SIZE_OUT_OF_PLANE_GAIN = 1.2f
        private const val SIZE_RELEASE_SEC = 0.9f
        private const val SIZE_BOOST_MAX = 8f

        // Meteoroid shape: how much on-screen speed (px/s) stretches the streak, and the cap
        // on that stretch (as a multiple of the dot's own radius) so it can't run away.
        private const val METEOR_STRETCH_GAIN = 0.02f
        private const val METEOR_STRETCH_MAX = 6f

        // How often (seconds) the randomizer picks a new shape/color while enabled — matches
        // Android Motion Assist's "every few seconds" randomization cadence.
        private const val RANDOMIZE_INTERVAL_SEC = 4f

        private const val SMOOTH_TIME_SEC = 0.05f

        // Focus-mode radial falloff: below FOCUS_INNER (normalized distance from center,
        // 0=center 1=corner) dots are invisible; above FOCUS_OUTER they're full size.
        private const val FOCUS_INNER = 0.25f
        private const val FOCUS_OUTER = 0.9f

        // Columns mode: band width per edge, as a fraction of screen width. The dot grid's own
        // on-screen pitch is roughly 29% of screen width at the default density (a coarse grid
        // spread over a wrap range several times the visible area), so a band this size
        // reliably shows about one full dot column plus a partially-faded second one at each
        // edge. Denser/sparser settings shift the pitch somewhat but the band remains a
        // reasonable approximation across the supported range.
        private const val COLUMN_BAND_OUTER_FRAC = 0.20f
        private const val COLUMN_BAND_INNER_FRAC = 0.10f

        private fun wrap(v: Float, ext: Float, span: Float): Float {
            var r = (v + ext) % span
            if (r < 0f) r += span
            return r - ext
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
