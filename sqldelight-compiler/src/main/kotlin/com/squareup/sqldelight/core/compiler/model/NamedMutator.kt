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

import com.alecstrong.sqlite.psi.core.psi.SqliteDeleteStmtLimited
import com.alecstrong.sqlite.psi.core.psi.SqliteInsertStmt
import com.alecstrong.sqlite.psi.core.psi.SqliteTableName
import com.alecstrong.sqlite.psi.core.psi.SqliteUpdateStmtLimited
import com.intellij.psi.PsiElement
import com.squareup.sqldelight.core.lang.psi.StmtIdentifierMixin
import com.squareup.sqldelight.core.lang.util.referencedTables

sealed class NamedMutator(
  id: Int,
  statement: PsiElement,
  identifier: StmtIdentifierMixin,
  tableName: SqliteTableName
) : NamedExecute(id, identifier, statement) {
  internal val tableEffected: SqliteTableName by lazy {
    tableName.referencedTables().single()
  }

  class Insert(
    id: Int,
    insert: SqliteInsertStmt,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(id, insert, identifier, insert.tableName)

  class Delete(
    id: Int,
    delete: SqliteDeleteStmtLimited,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(id, delete, identifier, delete.qualifiedTableName.tableName)

  class Update(
    id: Int,
    internal val update: SqliteUpdateStmtLimited,
    identifier: StmtIdentifierMixin
  ) : NamedMutator(id, update, identifier, update.qualifiedTableName.tableName)
}
