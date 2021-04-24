package com.squareup.sqldelight.gradle

import groovy.lang.Closure
import org.gradle.api.Project
import org.gradle.util.ConfigureUtil

open class SqlDelightExtension {
  internal val databases = mutableListOf<SqlDelightDatabase>()
  internal var configuringDatabase: SqlDelightDatabase? = null
  internal lateinit var project: Project

  var linkSqlite = true

  fun methodMissing(name: String, args: Any): Any {
    configuringDatabase?.methodMissing(name, args)?.let { return it }

    val closure = (args as? Array<*>)?.getOrNull(0) as? Closure<*>
      ?: throw IllegalStateException(
        """
        |Expected a closure for database names:
        |
        |sqldelight {
        |  $name {
        |    packageName = "com.sample"
        |    sourceSet = files("src/main/sqldelight")
        |  }
        |}
      """.trimMargin()
      )

    val database = SqlDelightDatabase(project, name = name)
    configuringDatabase = database
    ConfigureUtil.configure(closure, database)
    configuringDatabase = null

    check(!databases.any { it.name == database.name }) { "There is already a database defined for ${database.name}" }

    databases.add(database)
    return Unit
  }

  /**
   * Supports configuration in Kotlin script build files.
   *
   * sqldelight {
   *   database("MyDatabase") {
   *     packageName = "com.example"
   *     sourceSet = files("src/main/sqldelight")
   *   }
   * }
   */
  fun database(name: String, config: SqlDelightDatabase.() -> Unit) {
    val database = SqlDelightDatabase(project, name = name).apply(config)

    check(!databases.any { it.name == database.name }) { "There is already a database defined for ${database.name}" }

    databases.add(database)
  }
}
