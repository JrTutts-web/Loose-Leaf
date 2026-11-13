plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.jrtutts.looseleaf"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jrtutts.looseleaf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // "full" includes the OCR module (Tesseract plus English data, roughly 15 MB
    // more per ABI). "lite" leaves it out entirely: no code, no native library.
    flavorDimensions += "ocr"
    productFlavors {
        create("full") { dimension = "ocr" }
        create("lite") { dimension = "ocr"; versionNameSuffix = "-lite" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Translations: Android builds the list of app languages from the values-xx
    // folders that exist (see docs/TRANSLATING.md). A half-finished translation
    // must not break the build; missing strings fall back to English.
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "MissingTranslation"
    }
    // F-Droid: no Google-signed dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // PDF writing (Apache-2.0). Rendering uses the platform's android.graphics.pdf.PdfRenderer.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Offline OCR engine, full flavour only (Apache-2.0).
    "fullImplementation"("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    // Drag-to-reorder for the page thumbnails (Apache-2.0).
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests (on a device it is part of Android).
    testImplementation("org.json:json:20240303")
}

// Loose Leaf must never be able to reach the network. This checks every merged
// manifest (after all library manifests have been folded in) and fails the
// build if a network permission is present. It runs as part of `check` and
// before every `assemble`.
val checkNoNetwork by tasks.registering {
    group = "verification"
    description = "Fails if any merged manifest requests a network permission."
    val manifests = fileTree(layout.buildDirectory.dir("intermediates")) {
        include("merged_manifests/**/AndroidManifest.xml", "merged_manifest/**/AndroidManifest.xml")
    }
    val forbidden = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
    )
    doLast {
        val files = manifests.files
        if (files.isEmpty()) throw GradleException("checkNoNetwork: no merged manifest found to check")
        for (f in files) {
            val text = f.readText()
            for (p in forbidden) {
                if (Regex("<uses-permission[^>]*\"" + Regex.escape(p) + "\"").containsMatchIn(text)) {
                    throw GradleException("Network permission $p found in ${f.relativeTo(rootDir)}")
                }
            }
        }
        println("checkNoNetwork: OK, ${files.size} merged manifest(s) request no network permission")
    }
}
tasks.matching { it.name.startsWith("process") && it.name.endsWith("Manifest") }.configureEach {
    finalizedBy(checkNoNetwork)
}
tasks.named("check") { dependsOn(checkNoNetwork) }
