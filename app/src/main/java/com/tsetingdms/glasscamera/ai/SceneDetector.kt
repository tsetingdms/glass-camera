package com.tsetingdms.glasscamera.ai

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/** What AI scene detection recognised; [label] is shown in the viewfinder. */
enum class Scene(val label: String) {
    NONE(""),
    FOOD("Food"),
    GREENERY("Plants"),
    LANDSCAPE("Landscape"),
    SUNSET("Sunset"),
    PEOPLE("People"),
    ANIMALS("Animals"),
    TEXT("Text"),
}

/**
 * AI scene detection: ML Kit's on-device image labeler (model bundled in the APK, no internet) looks at a small
 * viewfinder frame about every 0.7 s, and labels such as "Fast food", "Flora" or "Receipt" add up to a [Scene]. A new
 * scene is reported (on the main thread) only after it's seen twice in a row, so the viewfinder label doesn't flicker.
 */
class SceneDetector(private val onScene: (Scene) -> Unit) : ImageAnalysis.Analyzer {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())

    /** Skip frames, e.g. while a photo is being taken (the labeler would compete for the CPU). */
    @Volatile
    var paused = false

    @Volatile
    private var running = false

    @Volatile
    private var closed = false
    private var lastRun = 0L

    // Main thread only (ML Kit's listeners).
    private var current = Scene.NONE
    private var candidate = Scene.NONE
    private var streak = 0

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val media = image.image
        if (paused || closed || running || media == null || now - lastRun < INTERVAL_MS) {
            image.close()
            return
        }
        running = true
        lastRun = now
        labeler.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { labels -> if (!closed) update(classify(labels)) }
            .addOnCompleteListener {
                image.close()
                running = false
            }
    }

    /** Forget the current scene (the camera or mode changed); reports [Scene.NONE] if something was shown. */
    fun reset() {
        candidate = Scene.NONE
        streak = 0
        if (current != Scene.NONE) {
            current = Scene.NONE
            onScene(Scene.NONE)
        }
    }

    fun close() {
        closed = true
        labeler.close()
    }

    private fun update(scene: Scene) {
        if (scene == candidate) {
            streak++
        } else {
            candidate = scene
            streak = 1
        }
        if (streak >= 2 && scene != current) {
            current = scene
            onScene(scene)
        }
    }

    companion object {
        private const val INTERVAL_MS = 700L

        /** The scene whose labels add up to the most confidence (at least 0.6), else [Scene.NONE]. */
        fun classify(labels: List<ImageLabel>): Scene {
            val score = FloatArray(Scene.entries.size)
            for (label in labels) {
                val (scene, weight) = KEYWORDS[label.text.lowercase()] ?: continue
                score[scene.ordinal] += label.confidence * weight
            }
            var best = Scene.NONE
            var top = 0.6f
            for (scene in Scene.entries) {
                if (score[scene.ordinal] > top) {
                    best = scene
                    top = score[scene.ordinal]
                }
            }
            return best
        }

        // Labels of ML Kit's base model (developers.google.com/ml-kit/vision/image-labeling/label-map), lower case.
        // Weight < 1 for labels that only hint at a scene (a cup is often, not always, food).
        private val KEYWORDS: Map<String, Pair<Scene, Float>> = buildMap {
            fun add(scene: Scene, weight: Float, vararg names: String) = names.forEach { put(it, scene to weight) }
            add(
                Scene.FOOD, 1f, "food", "fast food", "cuisine", "meal", "lunch", "supper", "hot dog", "cheeseburger",
                "bento", "couscous", "cookie", "pasteles", "gelato", "icing", "fruit", "pho", "pizza", "sushi", "bread",
                "vegetable", "cake", "pie", "juice", "coffee", "cappuccino",
            )
            add(Scene.FOOD, 0.5f, "tableware", "cutlery", "saucer", "cup", "cookware and bakeware", "eating", "cola", "wine", "steaming")
            add(Scene.GREENERY, 1f, "plant", "flower", "petal", "flora", "forest", "jungle", "garden", "flowerpot")
            add(Scene.GREENERY, 0.6f, "twig", "branch", "field", "prairie", "park", "farm")
            add(
                Scene.LANDSCAPE, 1f, "sky", "mountain", "lake", "river", "beach", "waterfall", "cliff", "glacier", "iceberg",
                "desert", "dune", "canyon", "skyline", "rainbow", "volcano", "aurora",
            )
            add(Scene.LANDSCAPE, 0.6f, "fog", "storm", "sand", "pier", "lighthouse")
            add(Scene.SUNSET, 1.5f, "sunset")
            add(
                Scene.PEOPLE, 1f, "person", "selfie", "smile", "laugh", "beard", "moustache", "hair", "eyelash", "skin",
                "baby", "bride", "groom", "lipstick", "bangs",
            )
            add(Scene.PEOPLE, 0.5f, "dude", "crowd", "team", "grandparent", "mouth", "ear", "glasses", "sunglasses")
            add(
                Scene.ANIMALS, 1f, "pet", "dog", "cat", "bird", "fur", "shetland sheepdog", "cairn terrier", "dalmatian",
                "basset hound", "ragdoll", "sphynx", "pixie-bob", "shikoku", "cavalier", "gerbil", "horse", "penguin",
            )
            add(Scene.ANIMALS, 0.6f, "duck", "butterfly", "insect", "cattle", "bull", "herd", "waterfowl", "turtle")
            add(
                Scene.TEXT, 1f, "paper", "poster", "menu", "receipt", "newspaper", "news", "whiteboard", "blackboard",
                "screenshot", "web page", "passport",
            )
            add(Scene.TEXT, 0.5f, "presentation", "money")
        }
    }
}
