// Versions are pinned so CI builds are reproducible. compileSdk 36 / AGP 8.13: keep libraries whose
// minCompileSdk is <= 36 (e.g. androidx.core 1.19+ needs compileSdk 37 and AGP 9).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
