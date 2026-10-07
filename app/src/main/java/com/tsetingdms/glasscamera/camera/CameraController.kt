package com.tsetingdms.glasscamera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewViewimport androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.tsetingdms.glasscamera.process.BurstMerge
import com.tsetingdms.glasscamera.process.ImageSaver
import com.tsetingdms.glasscamera.process.MergeParams
import com.tsetingdms.glasscamera.process.Portrait
import com.tsetingdms.glasscamera.video.VideoEffect
import com.tsetingdms.glasscamera.video.VideoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class Mode(val label: String) { NIGHT("Night"), PORTRAIT("Portrait"), PHOTO("Photo"), VIDEO("Video"), PRO("Pro") }

enum class RecState { IDLE, RECORDING, PAUSED }

enum class FlashMode { OFF, AUTO, ON }

enum class ProSetting(val label: String) { EV("EV"), WB("WB"), ISO("ISO"), SHUTTER("Shutter"), FOCUS("Focus") }

/** Shown in the middle of the screen while a multi-frame photo is taken / processed. */
data class Status(val text: String, val progress: Float)

/** What the current camera can do (read from Camera2). */
class Caps(
    val level: String,
    val manualSensor: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    /** Closest focus in diopters (1 / metres); 0 = fixed focus. */
    val minFocus: Float,
    val awbModes: List<Int>,
    val evRange: Range<Int>,
    val evStep: Float,
    val hasFlash: Boolean,
) {
    val manualFocus get() = manualSensor && minFocus > 0f
}

private class Shot(val jpeg: ByteArray, val rotation: Int)

private enum class Burst(val params: MergeParams, val holdText: String) {
    NIGHT(MergeParams.NIGHT, "Hold still…"),
    HDR(MergeParams.HDR, "Hold still…"),
    CLEAN(MergeParams.CLEAN, "Hold still…"),
}

/**
 * Owns the camera: binding CameraX to the activity, the capture modes, Pro controls and the
 * settings. Its Compose state drives the UI.
 */
class CameraController(private val activity: ComponentActivity) {
    private val prefs = activity.getSharedPreferences("glass_camera", Context.MODE_PRIVATE)
    private val io: Executor = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    // region UI state

    var mode by mutableStateOf(enumOr(prefs.getString("mode", null), Mode.PHOTO))
        private set
    var front by mutableStateOf(prefs.getBoolean("front", false))
        private set
    var flash by mutableStateOf(enumOr(prefs.getString("flash", null), FlashMode.OFF))
        private set
    var screenLight by mutableStateOf(prefs.getBoolean("screenLight", false))
        private set
    var hdr by mutableStateOf(prefs.getBoolean("hdr", false))
        private set
    var timer by mutableIntStateOf(prefs.getInt("timer", 0))
        private set
    var grid by mutableStateOf(prefs.getBoolean("grid", false))
        private set
    var mirrorFront by mutableStateOf(prefs.getBoolean("mirrorFront", true))
        private set
    var cleanSelfies by mutableStateOf(prefs.getBoolean("cleanSelfies", true))
        private set
    var shutterSound by mutableStateOf(prefs.getBoolean("shutterSound", true))
        private set

    // Video
    var videoStabilize by mutableStateOf(prefs.getBoolean("videoStabilize", true))
        private set
    var videoEnhance by mutableStateOf(prefs.getBoolean("videoEnhance", true))
        private set
    var video720 by mutableStateOf(prefs.getBoolean("video720", false))
        private set
    var torch by mutableStateOf(false)
        private set
    var recState by mutableStateOf(RecState.IDLE)
        private set
    var recSeconds by mutableLongStateOf(0L)
        private set
    val recording get() = recState != RecState.IDLE

    /** Asks the activity for the microphone permission (video sound). */
    var requestMic: (() -> Unit)? = null
    var caps by mutableStateOf<Caps?>(null)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var minZoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<Status?>(null)
        private set
    var countdown by mutableIntStateOf(0)
        private set
    var screenFlash by mutableStateOf(false)
        private set
    var thumbnail by mutableStateOf<Bitmap?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false)

    /** Icon rotation (degrees) so buttons stay upright when the phone is turned; accumulates to animate the short way. */
    var uiRotation by mutableFloatStateOf(0f)
        private set

    // Pro controls (null = automatic)
    var proSetting by mutableStateOf(ProSetting.EV)
    var proEv by mutableIntStateOf(0)
        private set
    var proWb by mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        private set
    var proIso by mutableStateOf<Int?>(null)
        private set
    var proShutter by mutableStateOf<Long?>(null)
        private set
    var proFocus by mutableStateOf<Float?>(null)
        private set

    // endregion

    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var lastIsVideo = false

    // GPU stabilization + enhancement for Video mode (created when first needed).
    private var processor: VideoProcessor? = null
    private var effect: VideoEffect? = null    private var boundBurst = false
    private var deviceRotation = Surface.ROTATION_0
    private var lastUri: Uri? = null

    private val previewSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    private val videoPreviewSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()

    // Largest 4:3 picture up to ~13 MP: the sensor's binned 12 MP mode is its cleanest, and it keeps
    // multi-frame processing within memory and time on the E40.
    private val captureSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .setResolutionFilter(ResolutionFilter { sizes, _ ->
            sizes.filter { it.width.toLong() * it.height <= 13_000_000L }.ifEmpty { sizes }
        })
        .build()

    init {
        activity.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { ImageSaver.latest(activity) } ?: return@launch
            lastUri = uri
            thumbnail = withContext(Dispatchers.IO) { ImageSaver.thumbnail(activity, uri) }
        }
    }

    // region Binding

    fun attach(view: PreviewView) {
        previewView = view
        val existing = provider
        if (existing != null) {
            bind()
            return
        }
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Exception) {
                message = "Camera not available"
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun usesBurst() = when (mode) {
        Mode.NIGHT -> true
        Mode.PHOTO -> hdr || (front && cleanSelfies)
        else -> false
    }

    private fun bind() {
        val p = provider ?: return
        val view = previewView ?: return
        val burst = usesBurst()
        try {
            p.unbindAll()
            torch = false
            if (mode == Mode.VIDEO) {
                bindVideo(p, view)
                return
            }
            videoCapture = null
            val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
            preview.setSurfaceProvider(view.surfaceProvider)
            val capture = ImageCapture.Builder()
                // Bursts need frames quickly; single shots get the slower, cleaner processing.
                .setCaptureMode(if (burst) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureSelector)
                .setJpegQuality(95)
                .setTargetRotation(deviceRotation)
                .build()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val cam = p.bindToLifecycle(activity, selector, preview, capture)
            camera = cam
            imageCapture = capture
            boundBurst = burst
            caps = readCaps(cam)
            cam.cameraInfo.zoomState.observe(activity) { state ->
                zoom = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
            }
            applyPro()
        } catch (e: Exception) {
            message = if (front) "Front camera not available" else "Camera not available"
        }
    }

    private fun bindVideo(p: ProcessCameraProvider, view: PreviewView) {
        val proc = processor ?: VideoProcessor().also { processor = it }
        val fx = effect ?: VideoEffect(proc).also { effect = it }
        proc.stabilize = videoStabilize
        proc.enhance = videoEnhance
        val quality = if (video720) Quality.HD else Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            // More detail than typical phone defaults (about 12–17 Mbit/s at 1080p).
            .setTargetVideoEncodingBitRate(if (video720) 10_000_000 else 20_000_000)
            .build()
        val video = VideoCapture.Builder(recorder).setTargetRotation(deviceRotation).build()
        // Same 16:9 shape as the video, so the viewfinder shows exactly what is recorded.
        val preview = Preview.Builder().setResolutionSelector(videoPreviewSelector).build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(video)
            .addEffect(fx)
            .build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val cam = p.bindToLifecycle(activity, selector, group)
        camera = cam
        imageCapture = null
        videoCapture = video
        boundBurst = false
        caps = readCaps(cam)
        cam.cameraInfo.zoomState.observe(activity) { state ->
            zoom = state.zoomRatio
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
        }
        applyPro()
    }

    private fun rebindIfNeeded() {
        if (usesBurst() != boundBurst) bind() else applyPro()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun readCaps(cam: Camera): Caps {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val level = when (info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
            else -> "Unknown"
        }
        val capabilities = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val exposure = cam.cameraInfo.exposureState
        return Caps(
            level = level,
            manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            awbModes = (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)).toList(),
            evRange = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange else Range(0, 0),
            evStep = exposure.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: (1f / 3f),
            hasFlash = cam.cameraInfo.hasFlashUnit(),
        )
    }

    // endregion

    // region Controls

    fun selectMode(value: Mode) {
        if (busy || recording || value == mode) return
        val videoChanged = (value == Mode.VIDEO) != (mode == Mode.VIDEO)
        mode = value
        prefs.edit { putString("mode", value.name) }
        if (videoChanged) bind() else rebindIfNeeded()
        if (value == Mode.VIDEO && !hasMic() && !prefs.getBoolean("micAsked", false)) {
            prefs.edit { putBoolean("micAsked", true) }
            requestMic?.invoke()
        }
    }

    fun onMicResult(granted: Boolean) {
        if (!granted) message = "Videos will be recorded without sound"
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggleStabilize() {
        videoStabilize = !videoStabilize
        prefs.edit { putBoolean("videoStabilize", videoStabilize) }
        processor?.stabilize = videoStabilize
        message = if (videoStabilize) "Stabilization on (slight crop)" else "Stabilization off"
    }

    fun toggleEnhance() {
        videoEnhance = !videoEnhance
        prefs.edit { putBoolean("videoEnhance", videoEnhance) }
        processor?.enhance = videoEnhance
        message = if (videoEnhance) "Enhance on: brighter shadows, less noise" else "Enhance off"
    }

    fun toggleVideoQuality() {
        if (recording) return
        video720 = !video720
        prefs.edit { putBoolean("video720", video720) }
        if (mode == Mode.VIDEO) bind()
    }

    fun toggleFront() {
        if (busy || recording) return
        front = !front
        prefs.edit { putBoolean("front", front) }
        proFocus = null
        bind()
    }

    fun cycleFlash() {
        if (mode == Mode.VIDEO) {
            if (front) return
            val cam = camera ?: return
            torch = !torch
            cam.cameraControl.enableTorch(torch)
            return
        }
        if (front) {
            screenLight = !screenLight
            prefs.edit { putBoolean("screenLight", screenLight) }
        } else {
            flash = FlashMode.entries[(flash.ordinal + 1) % FlashMode.entries.size]
            prefs.edit { putString("flash", flash.name) }
        }
    }

    fun toggleHdr() {
        hdr = !hdr
        prefs.edit { putBoolean("hdr", hdr) }
        message = if (hdr) "HDR on: brighter shadows, safer skies" else "HDR off"
        rebindIfNeeded()
    }

    fun cycleTimer() {
        timer = when (timer) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        prefs.edit { putInt("timer", timer) }
    }

    fun toggleGrid() {
        grid = !grid
        prefs.edit { putBoolean("grid", grid) }
    }

    fun changeMirrorFront(on: Boolean) {
        mirrorFront = on
        prefs.edit { putBoolean("mirrorFront", on) }
    }

    fun changeCleanSelfies(on: Boolean) {
        cleanSelfies = on
        prefs.edit { putBoolean("cleanSelfies", on) }
        rebindIfNeeded()
    }

    fun changeShutterSound(on: Boolean) {
        shutterSound = on
        prefs.edit { putBoolean("shutterSound", on) }
    }

    fun zoomTo(value: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio(value.coerceIn(minZoom, max(minZoom, maxZoom)))
    }

    fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val cam = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun onDeviceOrientation(orientation: Int) {
        val rotation = when (orientation) {
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rotation == deviceRotation) return
        deviceRotation = rotation
        imageCapture?.targetRotation = rotation
        if (!recording) videoCapture?.targetRotation = rotation
        val target = when (rotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 0f
        }
        var delta = (target - uiRotation) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        uiRotation += delta
    }

    fun openGallery() {
        val uri = lastUri ?: return
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, if (lastIsVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            message = "No gallery app found"
        }
    }

    // endregion

    // region Pro

    fun chooseEv(value: Int) {
        proEv = value
        applyPro()
    }

    fun chooseWb(value: Int) {
        proWb = value
        applyPro()
    }

    fun chooseIso(value: Int?) {
        proIso = value
        applyPro()
    }

    fun chooseShutter(value: Long?) {
        proShutter = value
        applyPro()
    }

    fun chooseFocus(value: Float?) {
        proFocus = value
        applyPro()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyPro() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (mode != Mode.PRO) {
            control.clearCaptureRequestOptions()
            cam.cameraControl.setExposureCompensationIndex(0)
            return
        }
        val c = caps
        val options = CaptureRequestOptions.Builder()
        if (c == null || CaptureRequest.CONTROL_AWB_MODE_AUTO == proWb || proWb in c.awbModes) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proWb)
        }
        val manualExposure = c != null && c.manualSensor && (proIso != null || proShutter != null)
        if (manualExposure && c != null) {
            val iso = (proIso ?: 200).let { v -> c.isoRange?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            val shutter = (proShutter ?: 16_666_667L).let { v -> c.exposureRange?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
        }
        val focus = proFocus
        if (focus != null && c != null && c.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, c.minFocus))
        }
        control.setCaptureRequestOptions(options.build())
        cam.cameraControl.setExposureCompensationIndex(if (manualExposure) 0 else proEv)
    }

    // endregion

    // region Capture

    fun shutter() {
        if (mode == Mode.VIDEO) {
            if (recording) stopRecording() else if (!busy) startRecordingAfterTimer()
            return
        }
        if (busy || camera == null || imageCapture == null) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                if (timer > 0) {
                    for (i in timer downTo 1) {
                        countdown = i
                        delay(1000)
                    }
                }
                countdown = 0
                capture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory for that photo — try closing other apps"
            } catch (e: Exception) {
                message = "Couldn't take the photo"
            } finally {
                countdown = 0
                screenFlash = false
                status = null
                busy = false
            }
        }
    }

    private suspend fun capture() {
        if (usesBurst() != boundBurst) bind()
        val ic = imageCapture ?: return
        val mirror = front && mirrorFront

        if (front && screenLight) {
            // Front "flash": a bright white screen lights the face; give auto-exposure a moment.
            screenFlash = true
            delay(450)
        }
        val singleShot = mode == Mode.PORTRAIT || mode == Mode.PRO || (mode == Mode.PHOTO && !usesBurst())
        ic.flashMode = if (!front && singleShot) {
            when (flash) {
                FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            }
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)

        when {
            mode == Mode.PORTRAIT -> portrait(ic, mirror)
            usesBurst() -> burst(ic, mirror)
            else -> {
                val uri = single(ic, mirror)
                screenFlash = false
                saved(uri)
            }
        }
    }

    /** One photo straight from the camera's own processing (fastest, full detail). */
    private suspend fun single(ic: ImageCapture, mirror: Boolean): Uri = suspendCancellableCoroutine { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(
            activity.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ImageSaver.newValues(),
        ).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror }).build()
        ic.takePicture(options, io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri
                if (uri != null) cont.resume(uri) else cont.resumeWithException(IllegalStateException("No photo saved"))
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    /** One frame kept in memory as JPEG bytes (for the multi-frame and portrait pipelines). */
    private suspend fun grab(ic: ImageCapture): Shot = suspendCancellableCoroutine { cont ->
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bytes = if (image.format == ImageFormat.JPEG) {
                        val buffer = image.planes[0].buffer
                        buffer.rewind()
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } else {
                        val bitmap = image.toBitmap()
                        ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            bitmap.recycle()
                            out.toByteArray()
                        }
                    }
                    cont.resume(Shot(bytes, image.imageInfo.rotationDegrees))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    private suspend fun burst(ic: ImageCapture, mirror: Boolean) {
        val kind = when {
            mode == Mode.NIGHT -> Burst.NIGHT
            hdr -> Burst.HDR
            else -> Burst.CLEAN
        }
        val count = when (kind) {
            Burst.NIGHT -> if (front) 6 else 8
            Burst.HDR -> 4
            Burst.CLEAN -> 4
        }
        val cam = camera ?: return
        val c = caps
        // HDR: shoot about 1 EV darker so bright skies keep their detail; shadows are lifted afterwards.
        val ev = if (kind == Burst.HDR && c != null && c.evRange.upper > c.evRange.lower) {
            (-1f / c.evStep).roundToInt().coerceIn(c.evRange.lower, 0)
        } else {
            0
        }
        if (ev != 0) {
            runCatching { cam.cameraControl.setExposureCompensationIndex(ev).awaitResult() }
            delay(500)
        }

        val shots = ArrayList<Shot>(count)
        try {
            for (i in 0 until count) {
                status = Status(kind.holdText, 0.5f * i / count)
                shots += grab(ic)
            }
        } finally {
            if (ev != 0) runCatching { cam.cameraControl.setExposureCompensationIndex(0) }
        }
        screenFlash = false

        status = Status("Processing…", 0.5f)
        val rotation = shots.first().rotation
        val jpegs = shots.map { it.jpeg }
        shots.clear()
        val merged = withContext(Dispatchers.Default) {
            BurstMerge.merge(jpegs, kind.params) { f -> post { status = Status("Processing…", 0.5f + 0.5f * f) } }
        }
        try {
            saved(ImageSaver.saveBitmap(activity, merged, rotation, mirror))
        } finally {
            merged.recycle()
        }
    }

    private suspend fun portrait(ic: ImageCapture, mirror: Boolean) {
        val shot = grab(ic)
        screenFlash = false
        status = Status("Finding the person…", 0.15f)
        val result = Portrait.process(shot.jpeg, shot.rotation) { f ->
            post { status = Status("Blurring the background…", 0.15f + 0.85f * f) }
        }
        if (result != null) {
            try {
                saved(ImageSaver.saveBitmap(activity, result, shot.rotation, mirror))
            } finally {
                result.recycle()
            }
        } else {
            message = "No person found — saved a normal photo"
            saved(ImageSaver.saveJpeg(activity, shot.jpeg, shot.rotation, mirror))
        }
    }

    private suspend fun saved(uri: Uri, video: Boolean = false) {
        lastUri = uri
        lastIsVideo = video
        thumbnail = withContext(Dispatchers.IO) { ImageSaver.thumbnail(activity, uri) }
    }

    // region Video recording

    private fun startRecordingAfterTimer() {
        if (videoCapture == null) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                if (timer > 0) {
                    for (i in timer downTo 1) {
                        countdown = i
                        delay(1000)
                    }
                }
                countdown = 0
                startRecording()
            } finally {
                countdown = 0
                busy = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Glass Camera")
        }
        val options = MediaStoreOutputOptions.Builder(activity.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(activity, options)
        if (hasMic()) pending = pending.withAudioEnabled()
        if (shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recSeconds = 0
        recState = RecState.RECORDING
        activeRecording = pending.start(ContextCompat.getMainExecutor(activity)) { event -> onRecordEvent(event) }
    }

    fun togglePause() {
        val rec = activeRecording ?: return
        if (recState == RecState.RECORDING) rec.pause() else if (recState == RecState.PAUSED) rec.resume()
    }

    private fun stopRecording() {
        if (shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        activeRecording?.stop()
        activeRecording = null
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> recSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
            is VideoRecordEvent.Pause -> recState = RecState.PAUSED
            is VideoRecordEvent.Resume -> recState = RecState.RECORDING
            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                recState = RecState.IDLE
                recSeconds = 0
                val uri = event.outputResults.outputUri
                // Leaving the app mid-recording ends it with "source inactive"; the file is still good.
                val ok = uri != Uri.EMPTY && (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE)
                if (ok) {
                    activity.lifecycleScope.launch { saved(uri, video = true) }
                } else {
                    message = "The video couldn't be saved"
                }
            }
            else -> Unit
        }
    }

    /** Stops recording and frees the GPU pipeline; call when the activity is destroyed. */
    fun release() {
        activeRecording?.stop()
        activeRecording = null
        processor?.release()
        processor = null
        effect = null
    }

    // endregion

    private fun post(block: () -> Unit) {
        activity.lifecycleScope.launch(Dispatchers.Main) { block() }
    }

    // endregion

    companion object {
        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, Runnable::run)
}

/** EV label like "+0.7" / "-1.3" / "0". */
fun evLabel(index: Int, step: Float): String {
    val ev = index * step
    return if (abs(ev) < 0.05f) "0" else String.format(java.util.Locale.US, "%+.1f", ev)
}
