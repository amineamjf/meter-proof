plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meterproof.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meterproof.app.v2" // نسخة redesign: تُثبَّت بجانب الأصلية ولا تستبدلها
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0-redesign"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // CameraX: التقاط صورة واحدة بلا معاينة ثم إغلاق الكاميرا
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // خدمة مرتبطة بدورة حياة (مطلوبة لـ CameraX داخل Service)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // كشف الوجوه على الجهاز (النموذج مضمّن ويعمل دون إنترنت)
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // اختبارات الوحدة للمنطق النقي (التوقف والحركة وملخص اليوم)
    testImplementation("junit:junit:4.13.2")
}
