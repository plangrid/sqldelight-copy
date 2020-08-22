package com.squareup.sqldelight

import org.junit.Test

class GradlePluginCombinationTests {
  @Test
  fun `sqldelight can be applied after kotlin-android-extensions`() {
    withTemporaryFixture {
      gradleFile("""
        |buildscript {
        |  apply from: "${"$"}{rootDir}/../../../../gradle/dependencies.gradle"
        |
        |  repositories {
        |    maven {
        |      url "file://${"$"}{rootDir}/../../../../build/localMaven"
        |    }
        |    mavenCentral()
        |    google()
        |    jcenter()
        |  }
        |  dependencies {
        |    classpath 'com.squareup.sqldelight:gradle-plugin:+'
        |    classpath deps.plugins.kotlin
        |    classpath deps.plugins.android
        |  }
        |}
        |
        |apply plugin: 'org.jetbrains.kotlin.multiplatform'
        |apply plugin: 'com.android.application'
        |apply plugin: 'com.squareup.sqldelight'
        |apply plugin: 'kotlin-android-extensions'
        |apply from: "${"$"}{rootDir}/../../../../gradle/dependencies.gradle"
        |
        |repositories {
        |  maven {
        |    url "file://${"$"}{rootDir}/../../../../build/localMaven"
        |  }
        |}
        |
        |sqldelight {
        |  CommonDb {
        |    packageName = "com.sample"
        |  }
        |}
        |
        |androidExtensions {
        |    experimental = true
        |}
        |
        |android {
        |  compileSdkVersion versions.compileSdk
        |}
        |
        |kotlin {
        |  android()
        |}
      """.trimMargin())

      configure()
    }
  }

  @Test
  fun `sqldelight fails when linkSqlite=false on native without additional linker settings`() {
    withTemporaryFixture {
      gradleFile("""
    |buildscript {
    |  apply from: "${"$"}{rootDir}/../../../../gradle/dependencies.gradle"
    |
    |  repositories {
    |    maven {
    |      url "file://${"$"}{rootDir}/../../../../build/localMaven"
    |    }
    |    mavenCentral()
    |    google()
    |  }
    |  dependencies {
    |    classpath 'com.squareup.sqldelight:gradle-plugin:+'
    |    classpath deps.plugins.kotlin
    |  }
    |}
    |
    |apply plugin: 'org.jetbrains.kotlin.multiplatform'
    |apply plugin: 'com.squareup.sqldelight'
    |apply from: "${"$"}{rootDir}/../../../../gradle/dependencies.gradle"
    |
    |repositories {
    |  maven {
    |    url "file://${"$"}{rootDir}/../../../../build/localMaven"
    |  }
    |}
    |
    |sqldelight {
    |  linkSqlite = false
    |  CommonDb {
    |    packageName = "com.sample"
    |  }
    |}
    |
    |kotlin {
    |  iosX64 {
    |    binaries { framework() }
    |  }
    |}
    |
    |import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinNativeCompile
    |
    |task checkForSqlite {
    |  doLast {
    |    // Verify no kotlin compile tasks have "-lsqlite3" in their extraOpts
    |    tasks.withType(AbstractKotlinNativeCompile.class) { task ->
    |      if (task.additionalCompilerOptions.contains("-lsqlite3")) throw new GradleException("sqlite should not be linked; linkSqlite is false")
    |    }
    |  }
    |}
    |
    """.trimMargin())
      configure("checkForSqlite")
    }
  }
}
