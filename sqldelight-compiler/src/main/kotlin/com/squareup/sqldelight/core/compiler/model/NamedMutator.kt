/*
 * Copyright (C) 2018 Square, Inc.
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
package com.squareup.sqldelight.core.compiler.model

import com.alecstrong.sql.psi.core.psi.SqlDeleteStmtLimited
import com.alecstrong.sql.psi.core.psi.SqlInsertStmt
import com.alecstrong.sql.psi.core.psi.SqlTableName
import com.alecstrong.sql.psi.core.psi.SqlUpdateStmtLimited
import com.intellij.psi.PsiElement
import com.squareup.sqldelight.core.lang.SqlDelightQueriesFile
import com.squareup.sqldelight.core.lang.psi.StmtIdentifierMixin
import com.squareup.sqldelight.core.lang.util.referencedTables
import com.squareup.sqldelight.core.lang.util.sqFile

sealed class NamedMutator(
  statement: PsiElement,
  identifier: StmtIdentifierMixin,
  tableName: SqlTableName
) : NamedExecute(identifier, statement) {
  val containingFile = statement.sqFile() as SqlDelightQueriesFile

  internal val tableEffected: SqlTableName by lazy {
    tableName.referencedTables().single()
  }

  class Insert(
    insert: SqlInsertStmt,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(insert, identifier, insert.tableName)

  class Delete(
    delete: SqlDeleteStmtLimited,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(delete, identifier, delete.qualifiedTableName!!.tableName)

  class Update(
    internal val update: SqlUpdateStmtLimited,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(update, identifier, update.qualifiedTableName.tableName)
}
