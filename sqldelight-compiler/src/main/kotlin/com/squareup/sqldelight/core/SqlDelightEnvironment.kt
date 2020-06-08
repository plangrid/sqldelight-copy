/*
 * Copyright (C) 2017 Square, Inc.
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
package com.squareup.sqldelight.core

import com.alecstrong.sql.psi.core.DialectPreset
import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.SqlCoreEnvironment
import com.alecstrong.sql.psi.core.SqlFileBase
import com.alecstrong.sql.psi.core.psi.SqlCreateTableStmt
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.intellij.mock.MockModule
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.squareup.sqldelight.core.compiler.SqlDelightCompiler
import com.squareup.sqldelight.core.lang.MigrationFile
import com.squareup.sqldelight.core.lang.MigrationFileType
import com.squareup.sqldelight.core.lang.MigrationParserDefinition
import com.squareup.sqldelight.core.lang.SqlDelightFile
import com.squareup.sqldelight.core.lang.SqlDelightFileType
import com.squareup.sqldelight.core.lang.SqlDelightParserDefinition
import com.squareup.sqldelight.core.lang.SqlDelightQueriesFile
import com.squareup.sqldelight.core.lang.util.findChildrenOfType
import com.squareup.sqldelight.core.psi.SqlDelightImportStmt
import java.io.File
import java.util.ArrayList
import java.util.StringTokenizer
import kotlin.math.log10
import kotlin.system.measureTimeMillis
import org.picocontainer.MutablePicoContainer

/**
 * Mocks an intellij environment for compiling sqldelight files without an instance of intellij
 * running.
 */
class SqlDelightEnvironment(
  /**
   * The sqlite source directories for this environment.
   */
  private val sourceFolders: List<File>,
  /**
   * The sqlite source directories for this environment.
   */
  private val dependencyFolders: List<File>,
  /**
   * The package name to be used for the generated SqlDelightDatabase class.
   */
  private val properties: SqlDelightDatabaseProperties,
  /**
   * An output directory to place the generated class files.
   */
  private val outputDirectory: File? = null,
  moduleName: String
) : SqlCoreEnvironment(SqlDelightParserDefinition(), SqlDelightFileType, sourceFolders),
    SqlDelightProjectService {
  val project: Project = projectEnvironment.project
  val module = MockModule(project, project)
  private val moduleName = SqlDelightFileIndex.sanitizeDirectoryName(moduleName)

  init {
    SqlDelightFileIndex.setInstance(module, FileIndex())
    (project.picoContainer as MutablePicoContainer).registerComponentInstance(SqlDelightProjectService::class.java.name, this)

    with(applicationEnvironment) {
      registerFileType(MigrationFileType, MigrationFileType.defaultExtension)
      registerParserDefinition(MigrationParserDefinition())
    }
  }

  override fun module(vFile: VirtualFile) = module

  override var dialectPreset: DialectPreset
    get() = properties.dialectPreset
    set(_) { throw UnsupportedOperationException() }

  /**
   * Run the SQLDelight compiler and return the error or success status.
   */
  fun generateSqlDelightFiles(logger: (String) -> Unit): CompilationStatus {
    val errors = ArrayList<String>()
    annotate(object : SqlAnnotationHolder {
      override fun createErrorAnnotation(element: PsiElement, s: String) {
        errors.add(errorMessage(element, s))
      }
    })
    if (errors.isNotEmpty()) return CompilationStatus.Failure(errors)

    val writer = writer@{ fileName: String ->
      val file = File(fileName)
      if (!file.exists()) {
        file.parentFile.mkdirs()
        file.createNewFile()
      }
      return@writer file.writer()
    }

    var sourceFile: SqlDelightQueriesFile? = null
    var topMigrationFile: MigrationFile? = null
    forSourceFiles {
      if (it is MigrationFile) {
        if (topMigrationFile == null || it.order!! > topMigrationFile!!.order!!) topMigrationFile = it
      }

      if (it !is SqlDelightQueriesFile) return@forSourceFiles
      logger("----- START ${it.name} ms -------")
      val timeTaken = measureTimeMillis {
        SqlDelightCompiler.writeInterfaces(module, it, moduleName, writer)
        sourceFile = it
      }
      logger("----- END ${it.name} in $timeTaken ms ------")
    }

    topMigrationFile?.let { migrationFile ->
      logger("----- START ${migrationFile.name} ms -------")
      val timeTaken = measureTimeMillis {
        SqlDelightCompiler.writeInterfaces(
            module = module,
            file = migrationFile,
            implementationFolder = moduleName,
            output = writer,
            includeAll = true
        )
      }
      logger("----- END ${migrationFile.name} in $timeTaken ms ------")
    }

    sourceFile?.let {
      SqlDelightCompiler.writeDatabaseInterface(module, it, moduleName, writer)
      SqlDelightCompiler.writeImplementations(module, it, moduleName, writer)
    }

    return CompilationStatus.Success()
  }

  override fun forSourceFiles(action: (SqlFileBase) -> Unit) {
    super.forSourceFiles { file ->
      if (file.fileType == SqlDelightFileType || properties.deriveSchemaFromMigrations) {
        action(file)
      }
    }
  }

  fun forMigrationFiles(body: (MigrationFile) -> Unit) {
    val localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
    val psiManager = PsiManager.getInstance(projectEnvironment.project)
    sourceFolders
        .map { localFileSystem.findFileByPath(it.absolutePath)!! }
        .map { psiManager.findDirectory(it)!! }
        .flatMap { it.findChildrenOfType<MigrationFile>() }
        .sortedBy { it.version }
        .forEach {
          val errorElements = ArrayList<PsiErrorElement>()
          PsiTreeUtil.processElements(it) { element ->
            when (element) {
              is PsiErrorElement -> errorElements.add(element)
              // Uncomment when sqm files understand their state of the world.
              // is SqlAnnotatedElement -> element.annotate(annotationHolder)
            }
            return@processElements true
          }
          if (errorElements.isNotEmpty()) {
            throw SqlDelightException("Error Reading ${it.name}:\n\n" +
                errorElements.joinToString(separator = "\n") { errorMessage(it, it.errorDescription) })
          }
          body(it)
        }
  }

  private fun errorMessage(element: PsiElement, message: String): String {
    return "${element.containingFile.virtualFile.path} " +
        "line ${element.lineStart}:${element.charPositionInLine} - $message\n${detailText(element)}"
  }

  private fun detailText(element: PsiElement) = try {
    val context = context(element) ?: element
    val result = StringBuilder()
    val tokenizer = StringTokenizer(context.text, "\n", false)

    val maxDigits = (log10(context.lineEnd.toDouble()) + 1).toInt()
    for (line in context.lineStart..context.lineEnd) {
      if (!tokenizer.hasMoreTokens()) break
      result.append(("%0${maxDigits}d    %s\n").format(line, tokenizer.nextToken()))
      if (element.lineStart == element.lineEnd && element.lineStart == line) {
        // If its an error on a single line highlight where on the line.
        result.append(("%${maxDigits}s    ").format(""))
        if (element.charPositionInLine > 0) {
          result.append(("%${element.charPositionInLine}s").format(""))
        }
        result.append(("%s\n").format("^".repeat(element.textLength)))
      }
    }

    result.toString()
  } catch (e: Exception) {
    // If there is an exception while trying to print an error, just give back the unformatted error
    // and print the stack trace for more debugging.
    e.printStackTrace()
    element.text
  }

  private val PsiElement.charPositionInLine: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return textOffset - file.getLineStartOffset(file.getLineNumber(textOffset))
    }

  private val PsiElement.lineStart: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return file.getLineNumber(textOffset) + 1
    }

  private val PsiElement.lineEnd: Int
    get() {
      val file = PsiDocumentManager.getInstance(project).getDocument(containingFile)!!
      return file.getLineNumber(textOffset + textLength) + 1
    }

  private fun context(element: PsiElement?): PsiElement? =
      when (element) {
        null -> element
        is SqlCreateTableStmt -> element
        is SqlStmt -> element
        is SqlDelightImportStmt -> element
        else -> context(element.parent)
      }

  sealed class CompilationStatus {
    class Success : CompilationStatus()
    class Failure(val errors: List<String>) : CompilationStatus()
  }

  private inner class FileIndex : SqlDelightFileIndex {
    override val contentRoot
      get() = throw UnsupportedOperationException("Content root only usable from IDE")

    override val packageName: String
      get() = this@SqlDelightEnvironment.properties.packageName

    override val className: String
      get() = this@SqlDelightEnvironment.properties.className

    override val dependencies: List<SqlDelightDatabaseName>
      get() = this@SqlDelightEnvironment.properties.dependencies

    override val isConfigured = true

    override val outputDirectory
      get() = this@SqlDelightEnvironment.outputDirectory!!.absolutePath

    private val virtualDirectoriesWithDependencies: List<VirtualFile> by lazy {
      val localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
      return@lazy (sourceFolders + dependencyFolders)
          .map { localFileSystem.findFileByPath(it.absolutePath)!! }
    }

    private val directoriesWithDependencies: List<PsiDirectory> by lazy {
      val psiManager = PsiManager.getInstance(projectEnvironment.project)
      return@lazy virtualDirectoriesWithDependencies.map { psiManager.findDirectory(it)!! }
    }

    private val virtualDirectories: List<VirtualFile> by lazy {
      val localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
      return@lazy sourceFolders
          .map { localFileSystem.findFileByPath(it.absolutePath)!! }
    }

    private val directories: List<PsiDirectory> by lazy {
      val psiManager = PsiManager.getInstance(projectEnvironment.project)
      return@lazy virtualDirectories.map { psiManager.findDirectory(it)!! }
    }

    override fun ordering(file: MigrationFile): Int? {
      if (!properties.deriveSchemaFromMigrations) return null
      return file.virtualFile.nameWithoutExtension.filter { it in '0'..'9' }.toInt()
    }

    override fun packageName(file: SqlDelightFile): String {
      fun PsiFileSystemItem.relativePathUnder(ancestor: PsiDirectory): List<String>? {
        if (this.virtualFile.path == ancestor.virtualFile.path) return emptyList()
        parent?.let {
          return it.relativePathUnder(ancestor)?.plus(name)
        }
        return null
      }

      for (sourceFolder in sourceFolders(file)) {
        val path = file.parent!!.relativePathUnder(sourceFolder)
        if (path != null) return path.joinToString(separator = ".") { SqlDelightFileIndex.sanitizeDirectoryName(it) }
      }

      throw IllegalStateException("Tried to find package name of file ${file.virtualFile!!.path} when" +
          " it is not under any of the source folders $sourceFolders")
    }

    override fun sourceFolders(file: VirtualFile, includeDependencies: Boolean) =
        if (includeDependencies) virtualDirectoriesWithDependencies else virtualDirectories

    override fun sourceFolders(file: SqlDelightFile, includeDependencies: Boolean) =
        if (includeDependencies) directoriesWithDependencies else directories
  }
}
