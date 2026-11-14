plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    // Same package and resource namespace as WhatsApp, so view ids read "com.whatsapp:id/message_text".
    namespace = "com.whatsapp"
    compileSdk = 35
    defaultConfig { applicationId = "com.whatsapp"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "test" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // WhatsApp's chat is a RecyclerView: rows are recycled while scrolling, like here.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
