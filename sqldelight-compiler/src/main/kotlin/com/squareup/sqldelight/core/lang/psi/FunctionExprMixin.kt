package com.squareup.sqldelight.core.lang.psi

import com.alecstrong.sql.psi.core.DialectPreset
import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.alecstrong.sql.psi.core.psi.SqlResultColumn
import com.alecstrong.sql.psi.core.psi.impl.SqlFunctionExprImpl
import com.intellij.lang.ASTNode
import com.squareup.sqldelight.core.lang.IntermediateType
import com.squareup.sqldelight.core.lang.SqlDelightFile
import com.squareup.sqldelight.core.lang.util.encapsulatingType
import com.squareup.sqldelight.core.lang.util.type

internal class FunctionExprMixin(node: ASTNode?) : SqlFunctionExprImpl(node) {
  fun argumentType(expr: SqlExpr) = when (functionName.text.toLowerCase()) {
    "instr" -> when (expr) {
      exprList.getOrNull(1) -> IntermediateType(IntermediateType.SqliteType.TEXT)
      else -> functionType()
    }
    else -> functionType()
  }

  fun functionType() = when (functionName.text.toLowerCase()) {
    "round" -> {
      // Single arg round function returns an int. Otherwise real.
      if (exprList.size == 1) {
        IntermediateType(IntermediateType.SqliteType.INTEGER).nullableIf(exprList[0].type().javaType.isNullable)
      } else {
        IntermediateType(IntermediateType.SqliteType.REAL).nullableIf(exprList.any { it.type().javaType.isNullable })
      }
    }

    /**
     * sum's output is always nullable because it returns NULL for an input that's empty or only contains NULLs.
     *
     * https://www.sqlite.org/lang_aggfunc.html#sumunc
     * >>> The result of sum() is an integer value if all non-NULL inputs are integers. If any input to sum() is neither
     * >>> an integer or a NULL then sum() returns a floating point value which might be an approximation to the true sum.
     *
     */
    "sum" -> {
      val type = exprList[0].type()
      if (type.sqliteType == IntermediateType.SqliteType.INTEGER && !type.javaType.isNullable) {
        type.asNullable()
      } else {
        IntermediateType(IntermediateType.SqliteType.REAL).asNullable()
      }
    }

    "lower", "ltrim", "printf", "replace", "rtrim", "substr", "trim", "upper", "group_concat" -> {
      IntermediateType(IntermediateType.SqliteType.TEXT).nullableIf(exprList[0].type().javaType.isNullable)
    }

    "date", "time", "datetime", "julianday", "strftime", "char", "hex", "quote", "soundex",
    "sqlite_compileoption_get", "sqlite_source_id", "sqlite_version", "typeof" -> {
      IntermediateType(IntermediateType.SqliteType.TEXT)
    }

    "changes", "last_insert_rowid", "random", "sqlite_compileoption_used",
    "total_changes", "count" -> {
      IntermediateType(IntermediateType.SqliteType.INTEGER)
    }

    "instr", "length", "unicode" -> {
      IntermediateType(IntermediateType.SqliteType.INTEGER).nullableIf(exprList.any { it.type().javaType.isNullable })
    }

    "randomblob", "zeroblob" -> IntermediateType(IntermediateType.SqliteType.BLOB)
    "total", "bm25" -> IntermediateType(IntermediateType.SqliteType.REAL)
    "avg" -> IntermediateType(IntermediateType.SqliteType.REAL).asNullable()
    "abs", "likelihood", "likely", "unlikely" -> exprList[0].type()
    "coalesce", "ifnull" -> encapsulatingType(exprList, IntermediateType.SqliteType.INTEGER, IntermediateType.SqliteType.REAL, IntermediateType.SqliteType.TEXT, IntermediateType.SqliteType.BLOB)
    "nullif" -> exprList[0].type().asNullable()
    "max" -> encapsulatingType(exprList, IntermediateType.SqliteType.INTEGER, IntermediateType.SqliteType.REAL, IntermediateType.SqliteType.TEXT, IntermediateType.SqliteType.BLOB).asNullable()
    "min" -> encapsulatingType(exprList, IntermediateType.SqliteType.BLOB, IntermediateType.SqliteType.TEXT, IntermediateType.SqliteType.INTEGER, IntermediateType.SqliteType.REAL).asNullable()

    // json1

    "json", "json_remove", "json_extract", "json_insert", "json_replace", "json_set" -> {
      IntermediateType(IntermediateType.SqliteType.TEXT).nullableIf(exprList[0].type().javaType.isNullable)
    }
    "json_array", "json_object", "json_group_array", "json_group_object" -> IntermediateType(IntermediateType.SqliteType.TEXT)
    "json_array_length" -> IntermediateType(IntermediateType.SqliteType.INTEGER).nullableIf(exprList[0].type().javaType.isNullable)
    "json_patch" -> IntermediateType(IntermediateType.SqliteType.TEXT).nullableIf(exprList.any { it.type().javaType.isNullable })
    "json_type" -> IntermediateType(IntermediateType.SqliteType.TEXT).asNullable()
    "json_valid" -> IntermediateType(IntermediateType.SqliteType.INTEGER, IntermediateType.SqliteType.BOOLEAN)
    "json_quote" -> exprList[0].type().asNonNullable()

    "highlight", "snippet" -> IntermediateType(IntermediateType.SqliteType.TEXT).asNullable()
    else -> when ((containingFile as SqlDelightFile).dialect) {
      DialectPreset.MYSQL -> mySqlFunctionType()
      DialectPreset.POSTGRESQL -> postgreSqlFunctionType()
      else -> null
    }
  }

  private fun mySqlFunctionType() = when (functionName.text.toLowerCase()) {
    "greatest" -> encapsulatingType(exprList, IntermediateType.SqliteType.INTEGER,
        IntermediateType.SqliteType.REAL, IntermediateType.SqliteType.TEXT,
        IntermediateType.SqliteType.BLOB)
    "concat" -> encapsulatingType(exprList, IntermediateType.SqliteType.TEXT)
    "last_insert_id" -> IntermediateType(IntermediateType.SqliteType.INTEGER)
    "month", "year", "minute" -> IntermediateType(IntermediateType.SqliteType.INTEGER)
    "sin", "cos", "tan" -> IntermediateType(IntermediateType.SqliteType.REAL)
    else -> null
  }

  private fun postgreSqlFunctionType() = when (functionName.text.toLowerCase()) {
    "greatest" -> encapsulatingType(exprList, IntermediateType.SqliteType.INTEGER,
        IntermediateType.SqliteType.REAL, IntermediateType.SqliteType.TEXT,
        IntermediateType.SqliteType.BLOB)
    "concat" -> encapsulatingType(exprList, IntermediateType.SqliteType.TEXT)
    else -> null
  }

  override fun annotate(annotationHolder: SqlAnnotationHolder) {
    if (parent is SqlResultColumn && functionType() == null) {
      annotationHolder.createErrorAnnotation(this, "Unknown function ${functionName.text}")
    }
  }
}
