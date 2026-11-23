
import java.util.Properties
import java.io.File
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword",
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}


android {
    namespace = "com.sphynx"
    compileSdk = 36

    // PHASE 8 (GGUF backend): llama.cpp built from source (app/src/main/cpp). The NDK and CMake versions are
    // exactly the ones Code on the Go has on the owner's phone (NDK Installer plugin), so the phone
    // can build it; CI installs the same versions.
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // disable linter
    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    // THE BUILD'S GIT COMMIT (owner, 2026-11-12): written into every recording's start row, so a recording tells
    // which build made it. Read straight from .git (no git program needed); "unknown" when there is none.
    val gitCommit: String = run {
        try {
            val gitDir = rootProject.file(".git")
            val head = File(gitDir, "HEAD").readText().trim()
            val sha = if (head.startsWith("ref: ")) {
                val ref = head.removePrefix("ref: ").trim()
                val loose = File(gitDir, ref)
                if (loose.isFile) loose.readText().trim()
                else File(gitDir, "packed-refs").takeIf { it.isFile }?.readLines()
                    ?.firstOrNull { it.endsWith(" " + ref) }?.substringBefore(' ') ?: "unknown"
            } else head
            sha.take(7)
        } catch (e: Exception) { "unknown" }
    }

    defaultConfig {
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        // The app id (owner, 2026-11-14, v37.23: com.sphynx.autopilot -> com.sphynx; the code package, the
        // namespace above, is com.sphynx too since v37.25). Android treats a new id as a NEW app: it installs
        // next to the old one, which can be uninstalled afterwards. Calibration and learned data come back
        // from Sphynx-Storage/Output.
        applicationId = "com.sphynx"
        minSdk = 30
        targetSdk = 36
        // THE APP VERSION (owner, 2026-11-14): one step up with EVERY merged pull request
        // (37.20 -> 37.21 -> ...; after 37.99 comes 38.00). Written only here: the version code follows
        // from it (37.20 -> 3720) and the app reads it through BuildConfig.VERSION_NAME (core.AppVersion).
        // The newest What's new entry carries the same version; a unit test checks they match.
        val sphynxVersion = "38.16"
        versionCode = sphynxVersion.split(".").let { it[0].toInt() * 100 + it[1].toInt() }
        versionName = sphynxVersion

        vectorDrawables {
            useSupportLibrary = true
        }
        // Phase 5 (owner, 2026-11-21): lifecycle checks on an emulator (app/src/androidTest, workflow android-emulator.yml)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // the llama.cpp library is built for 64-bit ARM phones only (the other ABIs' libraries from
        // LiteRT-LM and TFLite are still packaged as before)
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // JVM unit tests (app/src/test): Android framework calls return default values instead of
    // throwing, so plain Kotlin logic can be tested without a device.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // a failing test prints its whole message in the CI log (not only "FAILED"), so CI failures can be read
        // without downloading the test report
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true

    }
    packaging {
        // Phase 7 L8: native libraries are unpacked on install, so LiteRT-LM finds the NPU libraries in
        // applicationInfo.nativeLibraryDir (Backend.NPU(nativeLibraryDir))
        jniLibs { useLegacyPackaging = true }
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")

            // The part below is only needed for compose builds.
            // This packaging block is required to solve interdependency conflicts.
            // They arise only when using local maven repo, so I suppose online repos have some way of solving such issues.

            // Caused by: com.android.builder.merge.DuplicateRelativeFileException: 4 files found with path 'commonMain/default/linkdata/module' from inputs:
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\collection\collection\1.4.2\collection-1.4.2.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\lifecycle\lifecycle-common\2.8.7\lifecycle-common-2.8.7.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\annotation\annotation\1.8.1\annotation-1.8.1.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\org\jetbrains\kotlinx\kotlinx-coroutines-core\1.7.3\kotlinx-coroutines-core-1.7.3.jar
            // And some others.
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }

    configurations.all {
        resolutionStrategy {
            // Force the use of Kotlin stdlib 1.9.22 for all modules
            force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")

            // Force specific AndroidX versions to avoid conflicts
            force("androidx.collection:collection:1.4.2")
            force("androidx.annotation:annotation:1.8.1")
            force("androidx.core:core-ktx:1.8.0")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
            force("androidx.collection:collection-ktx:1.4.2")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}



dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // pinned (was latest.release, which resolved to 0.19.1 on 2026-11-07): a new LiteRT-LM release
    // can change Gemma loading / inference, so upgrades are made on purpose, in their own PR
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // Phase 7 L8 (experimental NPU, off by default): LiteRT's MediaTek dispatch library and compiler plugin, which let
    // LiteRT-LM compile Gemma for a MediaTek NPU on the phone. Only the two .so files are wanted (not transitive), so the
    // app's LiteRT / TFLite versions stay as they are. MediaTek's Neuron adapter itself comes from the phone (manifest).
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.android.material)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.interpolator)

    testImplementation("junit:junit:4.13.2")
    // the real org.json for unit tests (android.jar only has stubs of it)
    testImplementation("org.json:json:20240303")
    // Phase 5: instrumented lifecycle tests, run only by the emulator workflow (not needed to build the app)
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
