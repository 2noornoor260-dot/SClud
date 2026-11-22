plugins { id("com.android.application") }
android {
    namespace = "com.audiohaptics.lsp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.audiohaptics.lsp"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// The Xposed API is provided by LSPosed at runtime: compile against stubs only, never package them.
dependencies { compileOnly(project(":xposed-stub")) }
