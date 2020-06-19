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
package com.squareup.sqldelight.core.lang

import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.SqlFileBase
import com.alecstrong.sql.psi.core.psi.SqlAnnotatedElement
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.PsiTreeUtil
import com.squareup.sqldelight.core.SqlDelightFileIndex
import com.squareup.sqldelight.core.compiler.model.NamedExecute
import com.squareup.sqldelight.core.compiler.model.NamedMutator.Delete
import com.squareup.sqldelight.core.compiler.model.NamedMutator.Insert
import com.squareup.sqldelight.core.compiler.model.NamedMutator.Update
import com.squareup.sqldelight.core.compiler.model.NamedQuery
import com.squareup.sqldelight.core.lang.psi.StmtIdentifierMixin
import com.squareup.sqldelight.core.psi.SqlDelightStmtList

class SqlDelightQueriesFile(
  viewProvider: FileViewProvider
) : SqlDelightFile(viewProvider, SqlDelightLanguage),
    SqlAnnotatedElement {
  override val packageName by lazy { SqlDelightFileIndex.getInstance(module).packageName(this) }

  internal val namedQueries by lazy {
    sqliteStatements()
        .filter { it.statement.compoundSelectStmt != null && it.identifier.name != null }
        .map { NamedQuery(it.identifier.name!!, it.statement.compoundSelectStmt!!, it.identifier) }
  }

  internal val namedMutators by lazy {
    sqliteStatements().filter { it.identifier.name != null }
        .mapNotNull {
          when {
            it.statement.deleteStmtLimited != null -> Delete(it.statement.deleteStmtLimited!!, it.identifier)
            it.statement.insertStmt != null -> Insert(it.statement.insertStmt!!, it.identifier)
            it.statement.updateStmtLimited != null -> Update(it.statement.updateStmtLimited!!, it.identifier)
            else -> null
          }
    }
  }

  internal val namedExecutes by lazy {
    sqliteStatements()
        .filter {
          it.identifier.name != null &&
            it.statement.deleteStmtLimited == null &&
            it.statement.insertStmt == null &&
            it.statement.updateStmtLimited == null &&
            it.statement.compoundSelectStmt == null
        }
        .map { NamedExecute(it.identifier, it.statement) }
  }

  internal val triggers by lazy { triggers(this) }

  override val order = null

  override fun getFileType() = SqlDelightFileType

  internal fun sqliteStatements(): Collection<LabeledStatement> {
    val sqlStmtList = PsiTreeUtil.getChildOfType(this, SqlDelightStmtList::class.java)!!
    return sqlStmtList.stmtIdentifierList.zip(sqlStmtList.stmtList) { id, stmt ->
      return@zip LabeledStatement(id as StmtIdentifierMixin, stmt)
    }
  }

  public override fun iterateSqlFiles(iterator: (SqlFileBase) -> Boolean) {
    val index = SqlDelightFileIndex.getInstance(module)
    val sourceFolders = index.sourceFolders(this)
    if (sourceFolders.isEmpty()) {
      iterator(this)
      return
    }
    sourceFolders.forEach { sqldelightDirectory ->
      if (!PsiTreeUtil.findChildrenOfAnyType(sqldelightDirectory, SqlFileBase::class.java)
          .all {
            if (it is MigrationFile && !index.deriveSchemaFromMigrations) return@all true
            if (originalFile == it) {
              iterator(this@SqlDelightQueriesFile)
            } else {
              iterator(it)
            }
          }) {
        return@forEach
      }
    }
  }

  override fun annotate(annotationHolder: SqlAnnotationHolder) {
    if (packageName.isEmpty()) {
      annotationHolder.createErrorAnnotation(this, "SqlDelight files must be placed in a package directory.")
    }
  }

  data class LabeledStatement(val identifier: StmtIdentifierMixin, val statement: SqlStmt)
}
