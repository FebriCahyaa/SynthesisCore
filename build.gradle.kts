// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    dependencies {
        // Security floor for the build classpath (Android Gradle Plugin and its
        // transitive tools). None of these ship in the APK, but they run during the
        // build and are reported by the dependency graph / Dependabot. Constraints
        // only raise versions; drop an entry once AGP itself requires a newer one.
        classpath(platform("io.netty:netty-bom:4.1.138.Final")) // HTTP/2 reset flood, request smuggling, SslHandler DoS
        constraints {
            classpath("org.bitbucket.b_c:jose4j:0.9.7") { because("CVE-2024-29371: DoS via compressed JWE") }
            classpath("org.apache.httpcomponents:httpclient:4.5.14") { because("CVE-2020-13956: URI authority parsing") }
            classpath("org.apache.httpcomponents:httpmime:4.5.14") { because("aligned with httpclient 4.5.14") }
            classpath("org.apache.commons:commons-lang3:3.20.0") { because("CVE-2025-48924: ClassUtils recursion") }
            classpath("org.jdom:jdom2:2.0.6.1") { because("CVE-2021-33813: XXE in SAXBuilder") }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
