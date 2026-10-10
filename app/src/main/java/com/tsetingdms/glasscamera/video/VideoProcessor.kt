package com.tsetingdms.glasscamera.video

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.tsetingdms.glasscamera.look.Lut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs [VideoProcessor] on the viewfinder and the recording (so what you see is what's recorded), or on the viewfinder
 * only ([targets] = PREVIEW: photo modes with a colour look).
 */
class VideoEffect(processor: VideoProcessor, targets: Int = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE) : CameraEffect(
    targets,
    processor.executor,
    processor,
    Consumer { it.printStackTrace() },
)

/**
 * GCam-style video processing on the GPU, frame by frame:
 *
 * - **Stabilization**: each frame is shrunk to 1/16 size and compared with the previous one to measure
 *   how far the picture moved (hand shake). The camera's path is smoothed, and every frame is shifted
 *   to follow the smooth path inside a 15 % crop margin.
 * - **Enhance**: local tone mapping (a blurred brightness map lifts dark areas more than bright ones)
 *   and motion-adaptive temporal noise reduction (blend with the previous frame where nothing moved).
 *
 * - **Look**: an optional colour look (3D LUT) applied as each output is drawn, after the denoise history, so the
 *   temporal blend always compares ungraded frames.
 *
 * All positions are in "frame space": the camera image with the SurfaceTexture transform applied, before
 * each output's own rotation / crop / mirror (CameraX's [SurfaceOutput.updateTransformMatrix]). Measuring and
 * correcting in the same space keeps the shift directions right on any phone, front or back camera.
 */
class VideoProcessor : SurfaceProcessor {
    private val thread = HandlerThread("GlassVideoGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    @Volatile
    var stabilize = true

    @Volatile
    var enhance = true

    /** Colour look for the outputs (null = none) and how strongly it's applied (0..1). */
    @Volatile
    var lut: Lut? = null

    @Volatile
    var lutAmount = 1f

    private var lutTexture = 0
    private var uploadedLut: Lut? = null

    private var egl: EglCore? = null
    private var programs: Programs? = null

    private var inputTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputSize = Size(0, 0)

    private class Output(val surface: EGLSurface, val size: Size)

    private val outputs = LinkedHashMap<SurfaceOutput, Output>()

    // Render targets (allocated for the input size)
    private var quarter: Gl.Target? = null
    private var small: Gl.Target? = null
    private var history = arrayOfNulls<Gl.Target>(2)
    private var historyIndex = 0
    private var hasHistory = false

    // Stabilization state, in frame-space units (0..1)
    private var smallPixels: ByteBuffer? = null
    private var previousLuma: IntArray? = null
    private var currentLuma: IntArray? = null
    private var pathX = 0f
    private var pathY = 0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var wasStabilizing = false

    private val stMatrix = FloatArray(16)
    private val inverseSt = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val drawMatrix = FloatArray(16)

    // region SurfaceProcessor

    override fun onInputSurface(request: SurfaceRequest) {
        val core = ensureGl()
        core.makeOffscreenCurrent()
        val size = request.resolution
        val texture = Gl.oesTexture()
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
        st.setOnFrameAvailableListener({ onFrame() }, handler)

        releaseInput()
        inputTexture = texture
        surfaceTexture = st
        inputSurface = surface
        if (size != inputSize) {
            inputSize = size
            allocateTargets(size)
        }

        request.provideSurface(surface, executor) {
            // The camera stopped using this surface.
            if (surfaceTexture === st) {
                releaseInput()
            } else {
                st.release()
                surface.release()
                ensureGl().makeOffscreenCurrent()
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        val core = ensureGl()
        val surface = output.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                outputs.remove(output)?.let {
                    core.makeOffscreenCurrent()
                    core.destroySurface(it.surface)
                }
                output.close()
            }
        }
        outputs[output] = Output(core.createWindowSurface(surface), output.size)
    }

    // endregion

    // region Frames

    private fun onFrame() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        val p = programs ?: return
        core.makeOffscreenCurrent()
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        if (outputs.isEmpty()) return
        st.getTransformMatrix(stMatrix)
        val timestamp = st.timestamp
        syncLut()
        val look = uploadedLut?.let { if (lutAmount > 0f) it else null }
        val amount = if (look != null) lutAmount else 0f
        val lutSize = look?.size ?: 2

        val stab = stabilize
        val enh = enhance
        if (!stab && !enh) {
            // Pass-through: the camera image straight to each output.
            for ((output, target) in outputs) {
                core.makeCurrent(target.surface)
                output.updateTransformMatrix(outMatrix, stMatrix)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, target.size.width, target.size.height)
                p.drawOes(inputTexture, outMatrix, lutTexture, amount, lutSize)
                core.present(target.surface, timestamp)
            }
            hasHistory = false
            wasStabilizing = false
            return
        }

        val q = quarter ?: return
        val s = small ?: return
        // 1/4 then 1/16 size copies: the brightness map, and the input for shake measurement.
        q.bind()
        p.downsampleOes(inputTexture, stMatrix, 1f / inputSize.width, 1f / inputSize.height)
        s.bind()
        p.downsample2d(q.texture, 1f / q.width, 1f / q.height)

        var shiftX = 0f
        var shiftY = 0f
        var crop = 1f
        if (stab) {
            if (!wasStabilizing) resetPath()
            measureShake(s)
            shiftX = pathX - smoothX
            shiftY = pathY - smoothY
            crop = CROP
        } else {
            previousLuma = null
        }
        wasStabilizing = stab

        // Processed frame into the history buffer (stabilized, tone mapped, denoised).
        val read = history[historyIndex]
        val write = history[1 - historyIndex] ?: return
        write.bind()
        p.process(
            oes = inputTexture,
            st = stMatrix,
            small = s.texture,
            previous = if (hasHistory && enh) read?.texture ?: 0 else 0,
            crop = crop,
            shiftX = shiftX,
            shiftY = shiftY,
            gain = if (enh) 1.45f else 1f,
            saturation = if (enh) 1.06f else 1f,
            denoise = if (enh) 0.6f else 0f,
        )
        historyIndex = 1 - historyIndex
        hasHistory = true

        // Each output samples the processed frame with its own rotation / crop / mirror.
        Matrix.invertM(inverseSt, 0, stMatrix, 0)
        for ((output, target) in outputs) {
            core.makeCurrent(target.surface)
            output.updateTransformMatrix(outMatrix, stMatrix)
            Matrix.multiplyMM(drawMatrix, 0, inverseSt, 0, outMatrix, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, target.size.width, target.size.height)
            p.draw2d(write.texture, drawMatrix, lutTexture, amount, lutSize)
            core.present(target.surface, timestamp)
        }
    }

    /** Uploads a newly chosen look's atlas (GL thread). */
    private fun syncLut() {
        val wanted = lut
        if (wanted === uploadedLut) return
        uploadedLut = wanted
        if (wanted == null) return
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, wanted.atlas, 0)
    }

    private fun resetPath() {
        pathX = 0f
        pathY = 0f
        smoothX = 0f
        smoothY = 0f
        previousLuma = null
    }

    /**
     * Global shift between this frame and the last one (content moved by d), accumulated into the
     * camera path; the smooth path follows it slowly (hand shake is filtered out, intentional pans kept).
     */
    private fun measureShake(target: Gl.Target) {
        val w = target.width
        val h = target.height
        val buffer = smallPixels ?: return
        buffer.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val luma = currentLuma ?: IntArray(w * h).also { currentLuma = it }
        for (i in 0 until w * h) {
            val r = buffer.get(i * 4).toInt() and 255
            val g = buffer.get(i * 4 + 1).toInt() and 255
            val b = buffer.get(i * 4 + 2).toInt() and 255
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        val prev = previousLuma
        if (prev != null) {
            val shift = estimateShift(prev, luma, w, h)
            if (shift != null) {
                pathX += shift[0] / w
                pathY += shift[1] / h
            }
        }
        // Swap buffers.
        previousLuma = luma
        currentLuma = prev ?: IntArray(w * h)

        smoothX += SMOOTHING * (pathX - smoothX)
        smoothY += SMOOTHING * (pathY - smoothY)
        // Never shift past the crop margin; when a pan reaches it, the smooth path is pulled along.
        val margin = (1f - CROP) / 2f
        if (pathX - smoothX > margin) smoothX = pathX - margin
        if (smoothX - pathX > margin) smoothX = pathX + margin
        if (pathY - smoothY > margin) smoothY = pathY - margin
        if (smoothY - pathY > margin) smoothY = pathY + margin
    }

    /** Shift (dx, dy) in small-frame pixels with sub-pixel refinement, or null when the scene has no detail. */
    private fun estimateShift(prev: IntArray, cur: IntArray, w: Int, h: Int): FloatArray? {
        val r = SEARCH
        val size = 2 * r + 1
        val sad = LongArray(size * size)
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        var total = 0L
        for (dy in -r..r) {
            for (dx in -r..r) {
                var sum = 0L
                var n = 0
                for (y in r until h - r) {
                    val pr = y * w
                    val cr = (y + dy) * w + dx
                    for (x in r until w - r) {
                        sum += abs(prev[pr + x] - cur[cr + x])
                        n++
                    }
                }
                val score = if (n > 0) sum * 256 / n else Long.MAX_VALUE
                sad[(dy + r) * size + (dx + r)] = score
                total += score
                if (score < best) {
                    best = score
                    bx = dx
                    by = dy
                }
            }
        }
        val mean = total / (size * size)
        // Flat scenes (sky, wall): no reliable match, assume no shake.
        if (mean <= 0 || best * 100 > mean * 85) return null
        fun at(dx: Int, dy: Int) = sad[(dy + r) * size + (dx + r)].toFloat()
        var fx = bx.toFloat()
        var fy = by.toFloat()
        if (bx > -r && bx < r) {
            val l = at(bx - 1, by)
            val c = at(bx, by)
            val rr = at(bx + 1, by)
            val d = l - 2 * c + rr
            if (d > 0f) fx += ((l - rr) / (2 * d)).coerceIn(-0.5f, 0.5f)
        }
        if (by > -r && by < r) {
            val u = at(bx, by - 1)
            val c = at(bx, by)
            val dd = at(bx, by + 1)
            val d = u - 2 * c + dd
            if (d > 0f) fy += ((u - dd) / (2 * d)).coerceIn(-0.5f, 0.5f)
        }
        return floatArrayOf(fx, fy)
    }

    // endregion

    // region Setup / teardown

    private fun ensureGl(): EglCore {
        egl?.let { return it }
        val core = EglCore()
        egl = core
        programs = Programs()
        return core
    }

    private fun allocateTargets(size: Size) {
        quarter?.release()
        small?.release()
        history.forEach { it?.release() }
        val qw = max(1, size.width / 4)
        val qh = max(1, size.height / 4)
        quarter = Gl.Target(qw, qh)
        val sw = max(8, qw / 4)
        val sh = max(8, qh / 4)
        small = Gl.Target(sw, sh)
        smallPixels = ByteBuffer.allocateDirect(sw * sh * 4).order(ByteOrder.nativeOrder())
        previousLuma = null
        currentLuma = null
        history = arrayOf(Gl.Target(size.width, size.height), Gl.Target(size.width, size.height))
        hasHistory = false
    }

    private fun releaseInput() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        inputSurface?.release()
        if (inputTexture != 0 && egl != null) {
            egl?.makeOffscreenCurrent()
            GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        }
        surfaceTexture = null
        inputSurface = null
        inputTexture = 0
    }

    /** Frees everything; call when the camera screen is destroyed. */
    fun release() {
        handler.post {
            releaseInput()
            val core = egl
            if (core != null) {
                core.makeOffscreenCurrent()
                outputs.values.forEach { core.destroySurface(it.surface) }
                outputs.keys.forEach { it.close() }
                outputs.clear()
                quarter?.release()
                small?.release()
                history.forEach { it?.release() }
                if (lutTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
                lutTexture = 0
                programs?.release()
                core.release()
            }
            egl = null
            programs = null
            thread.quitSafely()
        }
    }

    // endregion

    private inner class Programs {
        private val copyOes = Gl.program(VERTEX, FRAGMENT_OES)
        private val copy2d = Gl.program(VERTEX, FRAGMENT_2D)
        private val downOes = Gl.program(VERTEX_PLAIN, FRAGMENT_DOWN_OES)
        private val down2d = Gl.program(VERTEX_PLAIN, FRAGMENT_DOWN_2D)
        private val process = Gl.program(VERTEX_PROCESS, FRAGMENT_PROCESS)

        fun drawOes(texture: Int, matrix: FloatArray, lut: Int, amount: Float, lutSize: Int) {
            GLES20.glUseProgram(copyOes)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyOes, "uTexMatrix"), 1, false, matrix, 0)
            bindOes(0, texture, copyOes, "sTexture")
            bindLut(copyOes, lut, amount, lutSize)
            Gl.drawQuad()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        fun draw2d(texture: Int, matrix: FloatArray, lut: Int, amount: Float, lutSize: Int) {
            GLES20.glUseProgram(copy2d)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copy2d, "uTexMatrix"), 1, false, matrix, 0)
            bind2d(0, texture, copy2d, "sTexture")
            bindLut(copy2d, lut, amount, lutSize)
            Gl.drawQuad()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        private fun bindLut(program: Int, lut: Int, amount: Float, lutSize: Int) {
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutAmount"), if (lut != 0) amount else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutSize"), lutSize.toFloat())
            bind2d(1, lut, program, "sLut")
        }

        fun downsampleOes(texture: Int, st: FloatArray, stepX: Float, stepY: Float) {
            GLES20.glUseProgram(downOes)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(downOes, "uSt"), 1, false, st, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(downOes, "uStep"), stepX, stepY)
            bindOes(0, texture, downOes, "sTexture")
            Gl.drawQuad()
        }

        fun downsample2d(texture: Int, stepX: Float, stepY: Float) {
            GLES20.glUseProgram(down2d)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(down2d, "uStep"), stepX, stepY)
            bind2d(0, texture, down2d, "sTexture")
            Gl.drawQuad()
        }

        fun process(
            oes: Int,
            st: FloatArray,
            small: Int,
            previous: Int,
            crop: Float,
            shiftX: Float,
            shiftY: Float,
            gain: Float,
            saturation: Float,
            denoise: Float,
        ) {
            val prog = process
            GLES20.glUseProgram(prog)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSt"), 1, false, st, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uCrop"), crop)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uShift"), shiftX, shiftY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGain"), gain)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSaturation"), saturation)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uDenoise"), if (previous != 0) denoise else 0f)
            bindOes(0, oes, prog, "sCamera")
            bind2d(1, small, prog, "sSmall")
            bind2d(2, if (previous != 0) previous else small, prog, "sPrevious")
            Gl.drawQuad()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        private fun bindOes(unit: Int, texture: Int, program: Int, name: String) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
        }

        private fun bind2d(unit: Int, texture: Int, program: Int, name: String) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
        }

        fun release() {
            listOf(copyOes, copy2d, downOes, down2d, process).forEach { GLES20.glDeleteProgram(it) }
        }
    }

    private companion object {
        /** Visible part of the frame while stabilizing (the rest is room to move). */
        const val CROP = 0.85f

        /** How quickly the smooth path follows the real one each frame (lower = steadier, laggier pans). */
        const val SMOOTHING = 0.07f

        /** Shake search range in 1/16-size pixels (±6 ≈ ±96 px at 1080p per frame). */
        const val SEARCH = 6

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * aTex).xy;
            }
        """

        const val VERTEX_PLAIN = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vP;
            void main() {
                gl_Position = aPos;
                vP = aTex.xy;
            }
        """

        // Look: 3D LUT atlas (blue slices side by side, red across, green down); red/green interpolated by the
        // texture filter, blue between two slices. Atlas coordinates need highp (1089 texels across).
        const val LUT_GLSL = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            #define LUTP highp
            #else
            #define LUTP mediump
            #endif
            uniform sampler2D sLut;
            uniform float uLutAmount;
            uniform LUTP float uLutSize;
            vec3 graded(vec3 c) {
                if (uLutAmount <= 0.0) return c;
                LUTP vec3 v = clamp(c, 0.0, 1.0) * (uLutSize - 1.0);
                LUTP float b0 = floor(v.b);
                LUTP float b1 = min(b0 + 1.0, uLutSize - 1.0);
                LUTP float x = v.r + 0.5;
                LUTP float y = (v.g + 0.5) / uLutSize;
                LUTP float w = uLutSize * uLutSize;
                vec3 s0 = texture2D(sLut, vec2((b0 * uLutSize + x) / w, y)).rgb;
                vec3 s1 = texture2D(sLut, vec2((b1 * uLutSize + x) / w, y)).rgb;
                return mix(c, mix(s0, s1, v.b - b0), uLutAmount);
            }
        """

        const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vUv;
        """ + LUT_GLSL + """
            void main() {
                gl_FragColor = vec4(graded(texture2D(sTexture, vUv).rgb), 1.0);
            }
        """

        const val FRAGMENT_2D = """
            precision mediump float;
            uniform sampler2D sTexture;
            varying vec2 vUv;
        """ + LUT_GLSL + """
            void main() {
                gl_FragColor = vec4(graded(texture2D(sTexture, vUv).rgb), 1.0);
            }
        """

        // 4 bilinear taps one source pixel around the centre = average of a 4x4 block.
        const val FRAGMENT_DOWN_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform mat4 uSt;
            uniform vec2 uStep;
            varying vec2 vP;
            vec4 tap(vec2 p) { return texture2D(sTexture, (uSt * vec4(p, 0.0, 1.0)).xy); }
            void main() {
                gl_FragColor = 0.25 * (tap(vP + vec2(-uStep.x, -uStep.y)) + tap(vP + vec2(uStep.x, -uStep.y))
                    + tap(vP + vec2(-uStep.x, uStep.y)) + tap(vP + vec2(uStep.x, uStep.y)));
            }
        """

        const val FRAGMENT_DOWN_2D = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform vec2 uStep;
            varying vec2 vP;
            void main() {
                gl_FragColor = 0.25 * (texture2D(sTexture, vP + vec2(-uStep.x, -uStep.y)) + texture2D(sTexture, vP + vec2(uStep.x, -uStep.y))
                    + texture2D(sTexture, vP + vec2(-uStep.x, uStep.y)) + texture2D(sTexture, vP + vec2(uStep.x, uStep.y)));
            }
        """

        const val VERTEX_PROCESS = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uSt;
            uniform float uCrop;
            uniform vec2 uShift;
            varying vec2 vP;
            varying vec2 vSrc;
            varying vec2 vCam;
            void main() {
                gl_Position = aPos;
                vP = aTex.xy;
                vSrc = vec2(0.5) + (aTex.xy - vec2(0.5)) * uCrop + uShift;
                vCam = (uSt * vec4(vSrc, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_PROCESS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sCamera;
            uniform sampler2D sSmall;
            uniform sampler2D sPrevious;
            uniform float uGain;
            uniform float uSaturation;
            uniform float uDenoise;
            varying vec2 vP;
            varying vec2 vSrc;
            varying vec2 vCam;
            const vec3 W = vec3(0.299, 0.587, 0.114);
            void main() {
                vec3 c = texture2D(sCamera, vCam).rgb;
                // Local tone mapping from the blurred 1/16 brightness map.
                float area = max(dot(texture2D(sSmall, vSrc).rgb, W), 0.02);
                float toned = area * uGain / (1.0 + area * (uGain - 1.0));
                c = c * (toned / area);
                float l = dot(c, W);
                c = vec3(l) + (c - vec3(l)) * uSaturation;
                c = clamp(c, 0.0, 1.0);
                // Temporal noise reduction: blend with the previous frame where nothing changed.
                vec3 p = texture2D(sPrevious, vP).rgb;
                float diff = abs(dot(p, W) - dot(c, W));
                float w = uDenoise * clamp(1.0 - diff * 14.0, 0.0, 1.0) * (1.0 - 0.4 * l);
                gl_FragColor = vec4(mix(c, p, w), 1.0);
            }
        """
    }
}
