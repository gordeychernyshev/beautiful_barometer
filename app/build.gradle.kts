import org.gradle.external.javadoc.StandardJavadocDocletOptions

val keytrueStorePasswordProvider = providers.gradleProperty("KEYTRUE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("KEYTRUE_STORE_PASSWORD"))
val keytrueKeyPasswordProvider = providers.gradleProperty("KEYTRUE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("KEYTRUE_KEY_PASSWORD"))
    .orElse(keytrueStorePasswordProvider)
val keytrueSigningReady = rootProject.file("keytrue").isFile &&
        keytrueStorePasswordProvider.isPresent &&
        keytrueKeyPasswordProvider.isPresent

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.beautiful_barometer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.beautiful_barometer"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                // Room будет класть схемы сюда (удобно для миграций/проверок)
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        create("keytrue") {
            storeFile = rootProject.file("keytrue")
            storeType = "pkcs12"
            keyAlias = "key0"
            if (keytrueSigningReady) {
                storePassword = keytrueStorePasswordProvider.get()
                keyPassword = keytrueKeyPasswordProvider.get()
            }
        }
    }

    buildTypes {
        debug {
            if (keytrueSigningReady) {
                signingConfig = signingConfigs.getByName("keytrue")
            }
        }
        release {
            if (keytrueSigningReady) {
                signingConfig = signingConfigs.getByName("keytrue")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.register<Javadoc>("generateJavadoc") {
    group = "documentation"
    description = "Generates Javadoc documentation for selected project classes."

    val mainSourceSet = android.sourceSets.getByName("main")

    setSource(mainSourceSet.java.srcDirs)

    include(
        "**/data/*.java"
    )
    val debugClasspath = configurations.getByName("debugCompileClasspath")
    doFirst {
        val aarClassJars = debugClasspath.files
            .filter { it.extension.equals("aar", ignoreCase = true) }
            .map { zipTree(it).matching { include("classes.jar") } }

        val plainClasspath = debugClasspath.files
            .filterNot { it.extension.equals("aar", ignoreCase = true) }

        classpath = files(android.bootClasspath) +
                files(plainClasspath) +
                files(aarClassJars)
    }

    destinationDir = layout.buildDirectory
        .dir("docs/javadoc")
        .get()
        .asFile

    isFailOnError = true

    options.encoding = "UTF-8"

    (options as StandardJavadocDocletOptions).apply {
        charSet = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.google.android.gms:play-services-location:21.2.0")
    // Preferences
    implementation("androidx.preference:preference:1.2.1")

    // Room (Java)
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // MPAndroidChart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Lifecycle (по желанию)
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
