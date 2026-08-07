import com.android.build.api.dsl.ApplicationExtension
import com.github.skydoves.navgraph.gradle.RenderBackend
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    id("com.github.skydoves.navgraph") version "0.2.1"
}
navgraph {
    renderThumbnails.set(true)
    renderBackend.set(RenderBackend.ROBOLECTRIC)
    galleryRenderBackend.set(RenderBackend.ROBOLECTRIC)
    robolectricApplication.set("com.feldman.ha.NavGraphRenderApplication")
    variant.set("debug")
    failOnNavChange.set(System.getenv("CI") == "true")
    allowMissingBaseline.set(true)
    galleryEnabled.set(true)
}

ksp {
    arg("navgraph.annotatedOnly", "true")
}

val navgraphRobolectricSources = layout.buildDirectory.dir("generated/navgraph/robolectric")
val writeNavGraphRobolectricRenderTest = tasks.register("writeNavGraphRobolectricRenderTest") {
    val testSource = navgraphRobolectricSources.map {
        it.file("com/skydoves/navgraph/generated/NavGraphRobolectricRenderTest.kt")
    }

    outputs.file(testSource)

    doLast {
        val file = testSource.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.github.skydoves.navgraph.generated

            import com.github.skydoves.navgraph.testing.NavPreviewRenderTestBase
            import org.robolectric.annotation.Config

            @Config(application = com.feldman.ha.NavGraphRenderApplication::class)
            internal class NavGraphRobolectricRenderTest : NavPreviewRenderTestBase()
            """.trimIndent()
        )
    }
}

tasks.matching {
    it.name == "kspDebugUnitTestKotlin" ||
        it.name == "compileDebugUnitTestKotlin" ||
        it.name == "compileDebugUnitTestJavaWithJavac"
}.configureEach {
    dependsOn(writeNavGraphRobolectricRenderTest)
}

val navGraphWarmupPreviewName = "__NavGraphWarmup"

fun warmRobolectricRenderList(renderList: File) {
    if (!renderList.isFile) return

    val lines = renderList.readLines().filter { it.isNotBlank() }
    if (lines.isEmpty() || lines.any { navGraphWarmupPreviewName in it }) return

    val source = lines.firstOrNull { "DashboardDestinationPreview" in it } ?: lines.first()
    val columns = source.split('\t')
    if (columns.size < 4) return

    val warmupColumns = columns.toMutableList()
    warmupColumns[2] = navGraphWarmupPreviewName
    warmupColumns[3] = "false"
    renderList.writeText((listOf(warmupColumns.joinToString("\t")) + lines).joinToString("\n") + "\n")
}

fun removeWarmupPreviewArtifacts(outputDir: File) {
    if (!outputDir.isDirectory) return

    outputDir
        .resolve("preview-index.txt")
        .takeIf { it.isFile }
        ?.let { previewIndex ->
            val keptLines = previewIndex.readLines().filterNot { navGraphWarmupPreviewName in it }
            previewIndex.writeText(keptLines.joinToString("\n") + if (keptLines.isEmpty()) "" else "\n")
        }

    outputDir
        .resolve("thumbs")
        .takeIf { it.isDirectory }
        ?.listFiles { file -> navGraphWarmupPreviewName in file.name }
        ?.forEach { it.delete() }
}

val warmNavGraphRobolectricRenderList = tasks.register("warmNavGraphRobolectricRenderList") {
    dependsOn("prepareNavGraphRobolectricRenderList")

    doLast {
        val renderList = layout.buildDirectory
            .file("navgraph-render/robolectric-render-list.tsv")
            .get()
            .asFile
        warmRobolectricRenderList(renderList)
    }
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(warmNavGraphRobolectricRenderList)
    doFirst {
        warmRobolectricRenderList(layout.buildDirectory.file("navgraph-render/robolectric-render-list.tsv").get().asFile)
        warmRobolectricRenderList(layout.buildDirectory.file("navgraph-gallery-render/robolectric-render-list.tsv").get().asFile)
    }
}

tasks.matching {
    it.name == "mergeNavGraph" ||
        it.name == "generateNavGraph" ||
        it.name == "mergeNavGallery" ||
        it.name == "generatePreviewGallery"
}.configureEach {
    doLast {
        removeWarmupPreviewArtifacts(layout.buildDirectory.dir("navgraph").get().asFile)
        removeWarmupPreviewArtifacts(layout.buildDirectory.dir("navgallery").get().asFile)
    }
}

extensions.configure<ApplicationExtension> {
    namespace = "com.feldman.ha"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.feldman.ha"
        minSdk = 31
        targetSdk = 37
        versionCode = 9
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull

    signingConfigs {
        if (
            releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    sourceSets {
        val navgraphRobolectricSourceDir = layout.buildDirectory.asFile.get()
            .resolve("generated/navgraph/robolectric")
        getByName("test").java.srcDir(navgraphRobolectricSourceDir)
        maybeCreate("testDebug").java.srcDir(navgraphRobolectricSourceDir)
    }
}

val releaseVersionName = extensions.getByType<ApplicationExtension>().defaultConfig.versionName
    ?: error("Release versionName is not configured")
val releaseApkDirectory = layout.buildDirectory.dir("outputs/apk/release")
val signedReleaseApk = releaseApkDirectory.map { it.file("app-release.apk") }
val versionedReleaseApk = releaseApkDirectory.map { it.file("app-release-$releaseVersionName.apk") }
val copyVersionedReleaseApk = tasks.register("copyVersionedReleaseApk") {
    dependsOn("packageRelease")
    inputs.file(signedReleaseApk).optional()
    outputs.file(versionedReleaseApk)
    onlyIf {
        listOf(
            "RELEASE_STORE_FILE",
            "RELEASE_STORE_PASSWORD",
            "RELEASE_KEY_ALIAS",
            "RELEASE_KEY_PASSWORD"
        ).all { providers.environmentVariable(it).isPresent } && signedReleaseApk.get().asFile.exists()
    }
    doLast {
        signedReleaseApk.get().asFile.copyTo(
            versionedReleaseApk.get().asFile,
            overwrite = true
        )
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyVersionedReleaseApk)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM must come before any Compose lib
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)

    // Debug/Test tools
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)





    // LazyVerticalGrid
    implementation(libs.androidx.foundation)

    // Retrofit + Gson
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    // OkHttp for auth header
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.moshi.kotlin)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.material.kolor)
    implementation(libs.motionbutton)


    implementation(libs.coil.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
}

val definitionsFile = layout.projectDirectory.file("src/main/java/com/feldman/ha/widgets/WidgetDefinitions.kt")

androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercase() }
    val generateWidgets = tasks.register<DefaultTask>("generate${variantName}Widgets") {
        inputs.file(definitionsFile)
        
        doLast {
            val file = definitionsFile.asFile
            if (!file.exists()) return@doLast

            val content = file.readText()
            val regex = Regex("""key\s*=\s*"([^"]+)",\s*title\s*=\s*"([^"]+)"""")
            val matches = regex.findAll(content)
            
            val widgets = matches.map { match ->
                val key = match.groupValues[1]
                val title = match.groupValues[2]
                mapOf("key" to key, "title" to title)
            }.distinctBy { it["key"] }.toList()

            val genDir = file("src/main/java")
            val resDir = file("src/main/res")
            val pkgPath = "com/feldman/ha/widgets/generated"
            val pkgDir = File(genDir, pkgPath)
            
            pkgDir.mkdirs()
            val xmlDir = File(resDir, "xml")
            xmlDir.mkdirs()
            val valuesDir = File(resDir, "values")
            valuesDir.mkdirs()
            
            val manifestDir = file("src/main/generated_manifest")
            manifestDir.mkdirs()

            val stringsFile = File(valuesDir, "generated_widget_strings.xml")
            val stringsXml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
            
            val manifestXml = StringBuilder()

            val catalogCode = StringBuilder("""
                package com.feldman.ha.widgets.generated
                
                enum class FactoryWidgetProviderCatalog(val widgetKey: String, val receiverClassName: String) {
            """.trimIndent())

            widgets.forEachIndexed { index, widget ->
                val wType = widget["key"]!!
                val wLabel = widget["title"]!!
                val wName = wType.replaceFirstChar { it.uppercase() }
                val receiverClass = "com.feldman.ha.widgets.generated.${wName}WidgetReceiver"
                val configClass = "com.feldman.ha.widgets.generated.${wName}ConfigureActivity"

                // Enum entry
                catalogCode.append("\n    ${wType.uppercase()}(\"$wType\", \"$receiverClass\")")
                if (index < widgets.size - 1) catalogCode.append(",")

                // Manifest entry
                manifestXml.append("""
                    
                        <receiver
                            android:name="$receiverClass"
                            android:exported="true"
                            android:label="@string/widget_label_$wType">
                            <intent-filter>
                                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
                            </intent-filter>
                            <meta-data
                                android:name="android.appwidget.provider"
                                android:resource="@xml/${wType}_widget_info" />
                        </receiver>
                
                        <activity
                            android:name="$configClass"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
                            </intent-filter>
                        </activity>
                """.trimIndent())

                // XML
                File(xmlDir, "${wType}_widget_info.xml").writeText("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
                        android:minWidth="110dp"
                        android:minHeight="110dp"
                        android:targetCellWidth="2"
                        android:targetCellHeight="2"
                        android:minResizeWidth="109dp"
                        android:minResizeHeight="56dp"
                        android:resizeMode="horizontal|vertical"
                        android:updatePeriodMillis="1800000"
                        android:description="@string/widget_desc_$wType"
                        android:configure="$configClass"
                        android:widgetFeatures="reconfigurable"
                        android:widgetCategory="home_screen" />
                """.trimIndent())

                stringsXml.append("    <string name=\"widget_label_$wType\">$wLabel</string>\n")
                stringsXml.append("    <string name=\"widget_desc_$wType\">Home Assistant $wLabel widget</string>\n")

                // Boilerplate classes
                val receiverCode = """
                    package com.feldman.ha.widgets.generated
                    import com.feldman.ha.widgets.FactoryWidgetReceiver
                    class ${wName}WidgetReceiver : FactoryWidgetReceiver() {
                        override val widgetKey = "$wType"
                    }
                """.trimIndent()
                File(pkgDir, "${wName}WidgetReceiver.kt").writeText(receiverCode)

                val configCode = """
                    package com.feldman.ha.widgets.generated
                    import com.feldman.ha.widgets.FactoryConfigureActivity
                    class ${wName}ConfigureActivity : FactoryConfigureActivity() {
                        override val widgetKey = "$wType"
                    }
                """.trimIndent()
                File(pkgDir, "${wName}ConfigureActivity.kt").writeText(configCode)
            }

            catalogCode.append("\n}\n")
            File(pkgDir, "FactoryWidgetProviderCatalog.kt").writeText(catalogCode.toString())

            // strings
            stringsXml.append("</resources>")
            stringsFile.writeText(stringsXml.toString())

            // Inject into AndroidManifest.xml using markers
            val mainManifest = file("src/main/AndroidManifest.xml")
            if (mainManifest.exists()) {
                val manifestText = mainManifest.readText()
                val startMarker = "<!-- GENERATED_WIDGETS_START -->"
                val endMarker = "<!-- GENERATED_WIDGETS_END -->"
                val startIdx = manifestText.indexOf(startMarker)
                val endIdx = manifestText.indexOf(endMarker)
                
                if (startIdx != -1 && endIdx != -1 && startIdx < endIdx) {
                    val newManifestText = manifestText.substring(0, startIdx + startMarker.length) + 
                                         "\n" + manifestXml.toString() + "\n        " + 
                                         manifestText.substring(endIdx)
                    mainManifest.writeText(newManifestText)
                }
            }
        }
    }
    // Hook the task into the build process
    tasks.named("preBuild").configure {
        dependsOn(generateWidgets)
    }
}
