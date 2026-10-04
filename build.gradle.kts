// Top-level build file -- the app module's build.gradle.kts applies
// specific plugins; this only declares them (apply false) so version
// numbers are resolved once, from the version catalogue, rather than
// duplicated per module.
//
// No separate Kotlin Android plugin declared here -- the app module only
// applies android.application + kotlin.compose, meaning it's already on
// AGP's built-in Kotlin support path (same migration MX3 Launcher went
// through) rather than the traditional org.jetbrains.kotlin.android
// plugin.
// Forces patched versions of vulnerable libraries that AGP pulls in
// transitively onto the plugin classpath (build-time only, none of these
// end up in the APK) -- see the comment on each in
// gradle/libs.versions.toml for the advisory it addresses.
buildscript {
    dependencies {
        constraints {
            classpath(libs.jdom2)
            classpath(libs.apache.httpclient)
            classpath(libs.apache.httpmime)
            classpath(libs.bouncycastle.bcprov)
            classpath(libs.bouncycastle.bcpkix)
            classpath(libs.bouncycastle.bcutil)
            classpath(libs.apache.commons.lang3)
            classpath(libs.jose4j)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
