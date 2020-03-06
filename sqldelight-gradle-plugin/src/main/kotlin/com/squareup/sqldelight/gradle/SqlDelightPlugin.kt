/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqldelight.gradle

import com.squareup.sqldelight.VERSION
import com.squareup.sqldelight.core.SqlDelightPropertiesFile
import com.squareup.sqldelight.gradle.android.packageName
import com.squareup.sqldelight.gradle.kotlin.linkSqlite
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.sources.DefaultKotlinSourceSet
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

open class SqlDelightPlugin : Plugin<Project> {
  private val android = AtomicBoolean(false)
  private val kotlin = AtomicBoolean(false)

  private lateinit var extension: SqlDelightExtension

  override fun apply(project: Project) {
    extension = project.extensions.create("sqldelight", SqlDelightExtension::class.java)
    extension.project = project

    val androidPluginHandler = { _: Plugin<*> ->
      android.set(true)
      project.afterEvaluate {
        project.setupSqlDelightTasks(afterAndroid = true)
      }
    }
    project.plugins.withId("com.android.application", androidPluginHandler)
    project.plugins.withId("com.android.library", androidPluginHandler)
    project.plugins.withId("com.android.instantapp", androidPluginHandler)
    project.plugins.withId("com.android.feature", androidPluginHandler)
    project.plugins.withId("com.android.dynamic-feature", androidPluginHandler)

    val kotlinPluginHandler = { _: Plugin<*> -> kotlin.set(true) }
    project.plugins.withId("org.jetbrains.kotlin.multiplatform", kotlinPluginHandler)
    project.plugins.withId("org.jetbrains.kotlin.android", kotlinPluginHandler)
    project.plugins.withId("org.jetbrains.kotlin.jvm", kotlinPluginHandler)
    project.plugins.withId("kotlin2js", kotlinPluginHandler)

    project.afterEvaluate {
      project.setupSqlDelightTasks(afterAndroid = false)
    }
  }

  private fun Project.setupSqlDelightTasks(afterAndroid: Boolean) {
    if (android.get() && !afterAndroid) return

    if (!kotlin.get()) {
      throw IllegalStateException("SQL Delight Gradle plugin applied in "
          + "project '${project.path}' but no supported Kotlin plugin was found")
    }

    val isMultiplatform = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")

    // Add the runtime dependency.
    if (isMultiplatform) {
      val sourceSets =
          project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
      val sourceSet = (sourceSets.getByName("commonMain") as DefaultKotlinSourceSet)
      project.configurations.getByName(sourceSet.apiConfigurationName).dependencies.add(
          project.dependencies.create("com.squareup.sqldelight:runtime:$VERSION")
      )
    } else {
      project.configurations.getByName("api").dependencies.add(
          project.dependencies.create("com.squareup.sqldelight:runtime-jvm:$VERSION")
      )
    }

    if (extension.linkSqlite) {
      project.linkSqlite()
    }

    extension.run {
      if (databases.isEmpty() && android.get() && !isMultiplatform) {
        // Default to a database for android named "Database" to keep things simple.
        databases.add(SqlDelightDatabase(
            project = project,
            name = "Database",
            packageName = project.packageName()
        ))
      }

      databases.forEach { database ->
        if (database.packageName == null && android.get() && !isMultiplatform) {
          database.packageName = project.packageName()
        }
        database.registerTasks()
      }

      val ideaDir = File(project.rootDir, ".idea")
      if (ideaDir.exists()) {
        val propsDir =
            File(ideaDir, "sqldelight/${project.projectDir.toRelativeString(project.rootDir)}")
        propsDir.mkdirs()

        val properties = SqlDelightPropertiesFile(
            databases = databases.map { it.getProperties() }
        )
        properties.toFile(File(propsDir, SqlDelightPropertiesFile.NAME))
      }
    }
  }
}
