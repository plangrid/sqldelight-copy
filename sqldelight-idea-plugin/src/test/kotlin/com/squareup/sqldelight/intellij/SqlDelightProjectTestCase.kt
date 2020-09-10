package com.squareup.sqldelight.intellij

import com.alecstrong.sql.psi.core.DialectPreset
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.squareup.sqldelight.core.SqlDelightCompilationUnitImpl
import com.squareup.sqldelight.core.SqlDelightDatabaseProperties
import com.squareup.sqldelight.core.SqlDelightDatabasePropertiesImpl
import com.squareup.sqldelight.core.SqlDelightSourceFolderImpl
import com.squareup.sqldelight.core.SqldelightParserUtil
import com.squareup.sqldelight.core.compiler.SqlDelightCompiler
import com.squareup.sqldelight.core.lang.SqlDelightFileType
import com.squareup.sqldelight.core.lang.SqlDelightQueriesFile
import com.squareup.sqldelight.intellij.gradle.FileIndexMap
import com.squareup.sqldelight.intellij.util.GeneratedVirtualFile
import java.io.File
import java.io.PrintStream
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

abstract class SqlDelightProjectTestCase : LightJavaCodeInsightFixtureTestCase() {
  protected val tempRoot: VirtualFile
    get() = module.rootManager.contentRoots.single()
  override fun setUp() {
    super.setUp()
    DialectPreset.SQLITE_3_18.setup()
    SqldelightParserUtil.overrideSqlParser()
    myFixture.copyDirectoryToProject("", "")
    FileIndexMap.defaultIndex = FileIndex(configurePropertiesFile(), tempRoot)
    ApplicationManager.getApplication().runWriteAction {
      generateSqlDelightFiles()
    }
  }

  override fun getTestDataPath() = "testData/project"

  open fun configurePropertiesFile(): SqlDelightDatabaseProperties {
    return SqlDelightDatabasePropertiesImpl(
        className = "QueryWrapper",
        packageName = "com.example",
        compilationUnits = listOf(
            SqlDelightCompilationUnitImpl("internalDebug", listOf(SqlDelightSourceFolderImpl(File(tempRoot.path, "src/main/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/internal/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/debug/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/internalDebug/sqldelight"), false))),
            SqlDelightCompilationUnitImpl("internalRelease", listOf(SqlDelightSourceFolderImpl(File(tempRoot.path, "src/main/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/internal/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/release/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/internalRelease/sqldelight"), false))),
            SqlDelightCompilationUnitImpl("productionDebug", listOf(SqlDelightSourceFolderImpl(File(tempRoot.path, "src/main/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/production/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/debug/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/productionDebug/sqldelight"), false))),
            SqlDelightCompilationUnitImpl("productionRelease", listOf(SqlDelightSourceFolderImpl(File(tempRoot.path, "src/main/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/production/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/release/sqldelight"), false), SqlDelightSourceFolderImpl(File(tempRoot.path, "src/productionRelease/sqldelight"), false)))
        ),
        outputDirectoryFile = File(tempRoot.path, "build"),
        dependencies = emptyList(),
        dialectPresetName = DialectPreset.SQLITE_3_18.name,
        rootDirectory = File(tempRoot.path).absoluteFile
    )
  }

  protected inline fun <reified T : PsiElement> searchForElement(text: String): Collection<T> {
    return PsiTreeUtil.collectElements(file) {
      it is LeafPsiElement && it.text == text
    }.mapNotNull { it.getNonStrictParentOfType<T>() }
  }

  private fun generateSqlDelightFiles() {
    val mainDir = module.rootManager.contentRoots.single().findFileByRelativePath("src/main")!!
    val virtualFileWriter = { filePath: String ->
      val vFile: VirtualFile by GeneratedVirtualFile(filePath, module)
      PrintStream(vFile.getOutputStream(this))
    }
    var fileToGenerateDb: SqlDelightQueriesFile? = null
    module.rootManager.fileIndex.iterateContentUnderDirectory(mainDir) { file ->
      if (file.fileType != SqlDelightFileType) return@iterateContentUnderDirectory true
      val sqlFile = (psiManager.findFile(file)!! as SqlDelightQueriesFile)
      sqlFile.viewProvider.contentsSynchronized()
      fileToGenerateDb = sqlFile
      return@iterateContentUnderDirectory true
    }
    SqlDelightCompiler.writeInterfaces(module, fileToGenerateDb!!, module.name, virtualFileWriter)
    SqlDelightCompiler.writeDatabaseInterface(module, fileToGenerateDb!!, module.name, virtualFileWriter)
  }
}
