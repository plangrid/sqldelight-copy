package com.squareup.sqldelight.gradle

import com.alecstrong.sql.psi.core.DialectPreset
import com.android.builder.model.AndroidProject.FD_GENERATED
import com.squareup.sqldelight.core.SqlDelightCompilationUnitImpl
import com.squareup.sqldelight.core.SqlDelightDatabaseNameImpl
import com.squareup.sqldelight.core.SqlDelightDatabasePropertiesImpl
import com.squareup.sqldelight.core.SqlDelightSourceFolderImpl
import com.squareup.sqldelight.core.lang.MigrationFileType
import com.squareup.sqldelight.core.lang.SqlDelightFileType
import com.squareup.sqldelight.gradle.kotlin.Source
import com.squareup.sqldelight.gradle.kotlin.sources
import groovy.lang.GroovyObject
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project

class SqlDelightDatabase(
  val project: Project,
  var name: String,
  var packageName: String? = null,
  var schemaOutputDirectory: File? = null,
  var sourceFolders: Collection<String>? = null,
  var dialect: String = "sqlite:3.18",
  var deriveSchemaFromMigrations: Boolean = false,
  var migrationOutputDirectory: File? = null,
  var migrationOutputFileFormat: String = ".sql"
) {
  private val generatedSourcesDirectory
    get() = File(project.buildDir, "$FD_GENERATED/sqldelight/code/$name")

  private val sources by lazy { sources() }
  private val dependencies = mutableListOf<SqlDelightDatabase>()

  private var recursionGuard = false

  fun methodMissing(name: String, args: Any): Any {
    return (project as GroovyObject).invokeMethod(name, args)
  }

  @Suppress("unused") // Public API used in gradle files.
  fun dependency(dependencyProject: Project) {
    project.evaluationDependsOn(dependencyProject.path)

    val dependency = dependencyProject.extensions.findByType(SqlDelightExtension::class.java)
        ?: throw IllegalStateException("Cannot depend on a module with no sqldelight plugin.")
    val database = dependency.databases.singleOrNull { it.name == name }
        ?: throw IllegalStateException("No database named $name in $dependencyProject")
    check(database.packageName != packageName) { "Detected a schema that already has the package name $packageName in project $dependencyProject" }
    dependencies.add(database)
  }

  internal fun getProperties(): SqlDelightDatabasePropertiesImpl {
    val packageName = requireNotNull(packageName) { "property packageName must be provided" }

    check(!recursionGuard) { "Found a circular dependency in $project with database $name" }
    recursionGuard = true

    val dialectMapping = mapOf(
        "sqlite:3.18" to DialectPreset.SQLITE_3_18,
        "sqlite:3.24" to DialectPreset.SQLITE_3_24,
        "sqlite:3.25" to DialectPreset.SQLITE_3_25,
        "mysql" to DialectPreset.MYSQL,
        "postgresql" to DialectPreset.POSTGRESQL,
        "hsql" to DialectPreset.HSQL
    )

    val dialect = dialectMapping[dialect]
        ?: throw GradleException("The dialect $dialect is not supported. Supported dialects: ${dialectMapping.keys.joinToString()}.")

    try {
      return SqlDelightDatabasePropertiesImpl(
          packageName = packageName,
          compilationUnits = sources.map { source ->
            SqlDelightCompilationUnitImpl(
                name = source.name,
                sourceFolders = sourceFolders(source).sortedBy { it.folder.absolutePath }
            )
          },
          outputDirectoryFile = generatedSourcesDirectory,
          rootDirectory = project.projectDir,
          className = name,
          dependencies = dependencies.map { SqlDelightDatabaseNameImpl(it.packageName!!, it.name) },
          dialectPresetName = dialect.name,
          deriveSchemaFromMigrations = deriveSchemaFromMigrations
      )
    } finally {
      recursionGuard = false
    }
  }

  private fun sourceFolders(source: Source): List<SqlDelightSourceFolderImpl> {
    val sourceFolders = sourceFolders ?: listOf("sqldelight")

    val relativeSourceFolders = sourceFolders.flatMap { folder ->
      source.sourceSets.map { SqlDelightSourceFolderImpl(
          folder = File(project.projectDir, "src/$it/$folder"),
          dependency = false
      ) }
    }

    return relativeSourceFolders + dependencies.flatMap { dependency ->
      val dependencySource = source.closestMatch(dependency.sources)
          ?: return@flatMap emptyList<SqlDelightSourceFolderImpl>()
      val compilationUnit = dependency.getProperties().compilationUnits
          .single { it.name == dependencySource.name }

      return@flatMap compilationUnit.sourceFolders.map {
        SqlDelightSourceFolderImpl(
            folder = File(project.projectDir, project.relativePath(it.folder.absolutePath)),
            dependency = true
        )
      }
    }
  }

  internal fun registerTasks() {
    // Ideally each sourceSet has its proper source set up, but with sqldelight they all go into one
    // place right now. Can revisit later but prioritise common for now:

    val common = sources.singleOrNull { it.sourceSets.singleOrNull() == "commonMain" }
    common?.sourceDirectorySet?.srcDir(generatedSourcesDirectory.toRelativeString(project.projectDir))

    sources.forEach { source ->
      // Add the source dependency on the generated code.
      if (common == null) {
        source.sourceDirectorySet.srcDir(generatedSourcesDirectory.toRelativeString(project.projectDir))
      }

      val allFiles = sourceFolders(source)
      val sourceFiles = project.files(*allFiles.filter { !it.dependency }.map { it.folder }.toTypedArray())
      val dependencyFiles = project.files(*allFiles.filter { it.dependency }.map { it.folder }.toTypedArray())

      // Register the sqldelight generating task.
      val task = project.tasks.register("generate${source.name.capitalize()}${name}Interface", SqlDelightTask::class.java) {
        it.properties = getProperties()
        it.sourceFolders = sourceFiles.files
        it.dependencySourceFolders = dependencyFiles.files
        it.outputDirectory = generatedSourcesDirectory
        it.source(sourceFiles + dependencyFiles)
        it.include("**${File.separatorChar}*.${SqlDelightFileType.defaultExtension}")
        it.include("**${File.separatorChar}*.${MigrationFileType.defaultExtension}")
        it.group = SqlDelightPlugin.GROUP
        it.description = "Generate ${source.name} Kotlin interface for $name"
      }

      project.tasks.named("generateSqlDelightInterface").configure {
        it.dependsOn(task)
      }

      // Register the task as a dependency of source compilation.
      source.registerTaskDependency(task)

      if (!deriveSchemaFromMigrations) {
        addMigrationTasks(sourceFiles.files + dependencyFiles.files, source)
      } else if (migrationOutputDirectory != null) {
        addMigrationOutputTasks(sourceFiles.files + dependencyFiles.files, source)
      }
    }
  }

  private fun addMigrationTasks(
    sourceSet: Collection<File>,
    source: Source
  ) {
    val verifyMigrationTask =
        project.tasks.register("verify${source.name.capitalize()}${name}Migration", VerifyMigrationTask::class.java) {
          it.sourceFolders = sourceSet
          it.source(sourceSet)
          it.include("**${File.separatorChar}*.${SqlDelightFileType.defaultExtension}")
          it.include("**${File.separatorChar}*.${MigrationFileType.defaultExtension}")
          it.workingDirectory = File(project.buildDir, "sqldelight/migration_verification/${source.name.capitalize()}$name")
          it.group = SqlDelightPlugin.GROUP
          it.description = "Verify ${source.name} $name migrations and CREATE statements match."
          it.properties = getProperties()
        }

    if (schemaOutputDirectory != null) {
      project.tasks.register("generate${source.name.capitalize()}${name}Schema", GenerateSchemaTask::class.java) {
        it.sourceFolders = sourceSet
        it.outputDirectory = schemaOutputDirectory
        it.source(sourceSet)
        it.include("**${File.separatorChar}*.${SqlDelightFileType.defaultExtension}")
        it.include("**${File.separatorChar}*.${MigrationFileType.defaultExtension}")
        it.group = SqlDelightPlugin.GROUP
        it.description = "Generate a .db file containing the current $name schema for ${source.name}."
        it.properties = getProperties()
      }
    }
    project.tasks.named("check").configure {
      it.dependsOn(verifyMigrationTask)
    }
    project.tasks.named("verifySqlDelightMigration").configure {
      it.dependsOn(verifyMigrationTask)
    }
  }

  private fun addMigrationOutputTasks(
    sourceSet: Collection<File>,
    source: Source
  ) {
    project.tasks.register("generate${source.name.capitalize()}${name}Migrations", GenerateMigrationOutputTask::class.java) {
      it.sourceFolders = sourceSet
      it.source(sourceSet)
      it.include("**${File.separatorChar}*.${MigrationFileType.defaultExtension}")
      it.migrationOutputExtension = migrationOutputFileFormat
      it.outputDirectory = migrationOutputDirectory
      it.group = SqlDelightPlugin.GROUP
      it.description = "Generate valid sql migration files for ${source.name} $name."
      it.properties = getProperties()
    }
  }
}
