import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin {
    jvmToolchain(25)
    sourceSets.test { kotlin.srcDir("temp/tests") }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    compileOnly("org.jetbrains.compose.material:material-desktop:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    testImplementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.1")
}

tasks.test {
    providers.gradleProperty("hostArchive").orNull?.let { systemProperty("spw.hostArchive", it) }
}

spmod {
    PluginClass = "com.saltplayermod.webdav.WebDavPlugin"
    PluginId = "com.saltplayermod.webdav"
    PluginVersion = "0.2.2"
    PluginName = "WebDAV 音乐库"
    PluginProvider = "QianJue"
    PluginDescription = "WebDAV / AList 音乐库同步与在线播放"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM)
}
