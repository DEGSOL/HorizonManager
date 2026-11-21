import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.horizonmanager"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.horizonmanager.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.3.9"
        buildConfigField("String", "AGENT_VERSION", "\"0.1.10\"")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            for (name in listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort"))
                System.getProperty(name)?.let { value -> it.systemProperty(name, value) }
            it.maxHeapSize = "1700m"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        val releaseStore = providers.environmentVariable("HM_RELEASE_KEYSTORE").orNull
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("HM_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("HM_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("HM_RELEASE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
val bootstrap = tasks.register<Zip>("packageAgent") {
    destinationDirectory.set(layout.buildDirectory.dir("generated/bootstrap"))
    archiveFileName.set("bundle.zip")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(rootProject.file("../bootstrap")) {
        include("horizon.commit", "build-runtime.sh", "horizon-manager.service")
    }
    from(rootProject.file("../agent")) {
        include("requirements.txt", "horizon_manager/*.py")
        into("agent")
    }
}
val prepareBootstrap = tasks.register<Sync>("prepareBootstrap") {
    dependsOn(bootstrap)
    from(bootstrap.flatMap { it.archiveFile })
    from(rootProject.file("../bootstrap")) { include("launch.sh", "install.sh", "probe.sh") }
    into(layout.buildDirectory.dir("generated/bootstrapAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets"))
tasks.named("preBuild").configure { dependsOn(prepareBootstrap) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.unifiedpush.android:connector:3.3.6")
    implementation("com.github.mwiede:jsch:2.27.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.commonmark:commonmark:0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
