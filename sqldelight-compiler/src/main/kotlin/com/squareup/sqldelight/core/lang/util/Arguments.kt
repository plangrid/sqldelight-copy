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
package com.squareup.sqldelight.core.lang.util

import com.alecstrong.sql.psi.core.psi.SqlBetweenExpr
import com.alecstrong.sql.psi.core.psi.SqlBinaryExpr
import com.alecstrong.sql.psi.core.psi.SqlBinaryLikeExpr
import com.alecstrong.sql.psi.core.psi.SqlBindExpr
import com.alecstrong.sql.psi.core.psi.SqlCaseExpr
import com.alecstrong.sql.psi.core.psi.SqlCastExpr
import com.alecstrong.sql.psi.core.psi.SqlCollateExpr
import com.alecstrong.sql.psi.core.psi.SqlCompoundSelectStmt
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.alecstrong.sql.psi.core.psi.SqlFunctionExpr
import com.alecstrong.sql.psi.core.psi.SqlInExpr
import com.alecstrong.sql.psi.core.psi.SqlInsertStmt
import com.alecstrong.sql.psi.core.psi.SqlInsertStmtValues
import com.alecstrong.sql.psi.core.psi.SqlIsExpr
import com.alecstrong.sql.psi.core.psi.SqlLimitingTerm
import com.alecstrong.sql.psi.core.psi.SqlNullExpr
import com.alecstrong.sql.psi.core.psi.SqlParenExpr
import com.alecstrong.sql.psi.core.psi.SqlResultColumn
import com.alecstrong.sql.psi.core.psi.SqlSelectStmt
import com.alecstrong.sql.psi.core.psi.SqlSetterExpression
import com.alecstrong.sql.psi.core.psi.SqlUnaryExpr
import com.alecstrong.sql.psi.core.psi.SqlUpdateStmt
import com.alecstrong.sql.psi.core.psi.SqlUpdateStmtLimited
import com.alecstrong.sql.psi.core.psi.SqlUpdateStmtSubsequentSetter
import com.alecstrong.sql.psi.core.psi.SqlValuesExpression
import com.intellij.psi.util.PsiTreeUtil
import com.squareup.kotlinpoet.asClassName
import com.squareup.sqldelight.core.compiler.model.NamedQuery
import com.squareup.sqldelight.core.lang.IntermediateType
import com.squareup.sqldelight.core.lang.IntermediateType.SqliteType.ARGUMENT
import com.squareup.sqldelight.core.lang.IntermediateType.SqliteType.INTEGER
import com.squareup.sqldelight.core.lang.IntermediateType.SqliteType.NULL
import com.squareup.sqldelight.core.lang.IntermediateType.SqliteType.TEXT

/**
 * Return the expected type for this expression, which is the argument type exposed in the generated
 * api.
 */
internal fun SqlBindExpr.argumentType(): IntermediateType {
  return inferredType().copy(bindArg = this)
}

internal fun SqlBindExpr.isArrayParameter(): Boolean {
  return (parent is SqlInExpr && this == parent.lastChild)
}

private fun SqlExpr.inferredType(): IntermediateType {
  val parentRule = parent!!
  return when (parentRule) {
    is SqlExpr -> {
      val result = parentRule.argumentType(this)
      if (result.sqliteType == ARGUMENT) {
        parentRule.inferredType()
      } else {
        result
      }
    }

    is SqlValuesExpression -> parentRule.argumentType(this)
    is SqlSetterExpression -> parentRule.argumentType()
    is SqlLimitingTerm -> IntermediateType(INTEGER)
    is SqlResultColumn -> {
      (parentRule.parent as SqlSelectStmt).argumentType(parentRule)
          ?: IntermediateType(NULL, Any::class.asClassName())
    }
    else -> IntermediateType(NULL, Any::class.asClassName())
  }
}

/**
 * Return the expected type for [argument], which is the argument type exposed in the generated api.
 */
private fun SqlExpr.argumentType(argument: SqlExpr): IntermediateType {
  return when (this) {
    is SqlInExpr -> {
      if (argument === firstChild) return IntermediateType(ARGUMENT)

      return exprList.first().type()
    }

    is SqlCaseExpr, is SqlBetweenExpr, is SqlIsExpr, is SqlBinaryExpr -> {
      children.last { it is SqlExpr && it !== argument }.type()
    }

    is SqlNullExpr -> IntermediateType(NULL).asNullable()
    is SqlBinaryLikeExpr -> {
      val other = children.last { it is SqlExpr && it !== argument }.type()
      IntermediateType(TEXT).copy(name = other.name)
    }

    is SqlCollateExpr, is SqlCastExpr, is SqlParenExpr, is SqlUnaryExpr -> {
      return IntermediateType(ARGUMENT)
    }

    is SqlFunctionExpr -> {
      return type()
    }
    else -> throw AssertionError()
  }
}

private fun SqlValuesExpression.argumentType(expression: SqlExpr): IntermediateType {
  val argumentIndex = children.indexOf(expression)
  if (argumentIndex == -1) throw AssertionError()

  val parentRule = parent!!
  return when (parentRule) {
    is SqlInsertStmtValues -> {
      val insertStmt = parentRule.parent as SqlInsertStmt
      insertStmt.columns[argumentIndex].type()
    }
    is SqlSelectStmt -> {
      val compoundSelect = parentRule.parent as SqlCompoundSelectStmt
      NamedQuery("temp", compoundSelect).resultColumns[argumentIndex]
    }

    else -> throw AssertionError()
  }
}

private fun SqlSelectStmt.argumentType(result: SqlResultColumn): IntermediateType? {
  val index = resultColumnList.indexOf(result)
  val compoundSelect = parent!! as SqlCompoundSelectStmt

  val parentRule = compoundSelect.parent ?: return null
  return when (parentRule) {
    is SqlInsertStmtValues -> {
      val insertStmt = parentRule.parent as SqlInsertStmt
      insertStmt.columns[index].type()
    }

    else -> {
      // Check if this is part of an inner expression of a resulit column.
      val parentResult = PsiTreeUtil.getParentOfType(parentRule, SqlResultColumn::class.java)
          ?: return null
      (parentResult.parent as SqlSelectStmt).argumentType(parentResult)
    }
  }
}


private fun SqlSetterExpression.argumentType(): IntermediateType {
  val parentRule = parent!!
  val column = when (parentRule) {
    is SqlUpdateStmt -> parentRule.columnName
    is SqlUpdateStmtLimited -> parentRule.columnName
    is SqlUpdateStmtSubsequentSetter -> parentRule.columnName
    else -> throw AssertionError()
  }

  return column!!.type()
}