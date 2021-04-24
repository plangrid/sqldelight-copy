package com.squareup.sqldelight.core.dialect.postgresql

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName
import com.squareup.sqldelight.core.dialect.api.DialectType
import com.squareup.sqldelight.core.lang.CURSOR_NAME

internal enum class PostgreSqlType(override val javaType: TypeName) : DialectType {
  SMALL_INT(SHORT) {
    override fun decode(value: CodeBlock) = CodeBlock.of("%L.toShort()", value)

    override fun encode(value: CodeBlock) = CodeBlock.of("%L.toLong()", value)
  },
  INTEGER(INT) {
    override fun decode(value: CodeBlock) = CodeBlock.of("%L.toInt()", value)

    override fun encode(value: CodeBlock) = CodeBlock.of("%L.toLong()", value)
  },
  BIG_INT(LONG);

  override fun prepareStatementBinder(columnIndex: String, value: CodeBlock): CodeBlock {
    return CodeBlock.builder()
      .add(
        when (this) {
          SMALL_INT, INTEGER, BIG_INT -> "bindLong"
        }
      )
      .add("($columnIndex, %L)\n", value)
      .build()
  }

  override fun cursorGetter(columnIndex: Int): CodeBlock {
    return CodeBlock.of(
      when (this) {
        SMALL_INT, INTEGER, BIG_INT -> "$CURSOR_NAME.getLong($columnIndex)"
      }
    )
  }
}
