/*
 * Plugin resolution only. The build currently uses nothing but Gradle's built-in `java` plugin, so this
 * block changes nothing today - it is here so that adding a third-party plugin later does not reintroduce
 * the plugin-portal round trip that the mirrors below are meant to avoid.
 *
 * Mirrors come first, the official portal is the fallback.
 */
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
        gradlePluginPortal()
    }
}

rootProject.name = "EML-HeadlessBOT"
