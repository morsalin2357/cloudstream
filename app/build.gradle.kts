import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val head = headFile.get().asFile
        val hash = try {
            if (head.exists()) {
                val headContent = head.readText().trim()
                if (headContent.startsWith("ref:")) {
                    val refPath = headContent.substring(5)
                    val commitFile = File(head.parentFile, refPath)
                    if (commitFile.exists()) commitFile.readText().trim() else ""
                } else headContent
            } else ""
        } catch (_: Throwable) {
            ""
        }.take(7)

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }
}

// ✅ এখানে টাস্কের নাম <GenerateGitHashTask> যোগ করা হয়েছে
val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    val gitDir = layout.projectDirectory.dir("../.git")
    headFile.set(gitDir.file("HEAD"))
    headsDir.set(gitDir.dir("refs/heads"))
    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash, GenerateGitHashTask::outputDir
            )
        }
    }

    signingConfigs {
        if (System.getenv("SIGNING_KEY_ALIAS") != null) {
            create("prerelease") {
                val tmpFilePath = System.getProperty("user.home") + "/work/_temp/keystore/"
                val prereleaseStoreFile: File? = File(tmpFilePath).listFiles()?.first()
                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lagradost.cloudstream3"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        val localProperties = gradleLocalProperties(rootDir, project.providers)
        buildConfigField("long", "BUILD_DATE", "${System.currentTimeMillis()}")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"" + (System.getenv("SIMKL_CLIENT_ID") ?: localProperties["simkl.id"]) + "\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"" + (System.getenv("SIMKL_CLIENT_SECRET") ?: localProperties["simkl.secret"]) + "\"")
        buildConfigField("String", "MAL_KEY", "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\"")
        buildConfigField("String", "ANILIST_KEY", "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions.add("state")
    productFlavors {
        create("stable") {
            dimension = "state"
        }
        create("prerelease") {
            dimension = "state"
            applicationIdSuffix = ".prerelease"
            if (signingConfigs.names.contains("prerelease")) {
                signingConfig = signingConfigs.getByName("prerelease")
            } else {
                logger.warn("No prerelease signing config!")
            }
            versionNameSuffix = "-PRE"
            versionCode = (System.currentTimeMillis() / 60000).toInt()
        }
    }

    // ✅ এখানে আপনার ৩২-বিট টিভি বক্সের জন্য APK সাইজ কমানোর কোড দেওয়া হলো
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a") // 32-bit only
            isUniversalApk = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
}
