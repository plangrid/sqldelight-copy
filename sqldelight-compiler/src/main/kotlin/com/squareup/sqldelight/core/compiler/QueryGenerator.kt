package com.squareup.sqldelight.core.compiler

import com.alecstrong.sql.psi.core.psi.SqlBinaryEqualityExpr
import com.alecstrong.sql.psi.core.psi.SqlBindExpr
import com.alecstrong.sql.psi.core.psi.SqlTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.sqldelight.core.compiler.model.BindableQuery
import com.squareup.sqldelight.core.compiler.model.NamedQuery
import com.squareup.sqldelight.core.lang.DRIVER_NAME
import com.squareup.sqldelight.core.lang.util.childOfType
import com.squareup.sqldelight.core.lang.util.isArrayParameter
import com.squareup.sqldelight.core.lang.util.range
import com.squareup.sqldelight.core.lang.util.rawSqlText

abstract class QueryGenerator(private val query: BindableQuery) {
  /**
   * Creates the block of code that prepares [query] as a prepared statement and binds the
   * arguments to it. This code block does not make any use of class fields, and only populates a
   * single variable [STATEMENT_NAME]
   *
   * val numberIndexes = createArguments(count = number.size, offset = 2)
   * val statement = database.prepareStatement("""
   *     |SELECT *
   *     |FROM player
   *     |WHERE number IN $numberIndexes
   *     """.trimMargin(), SqlPreparedStatement.Type.SELECT, 1 + (number.size - 1))
   * number.forEachIndexed { index, number ->
   *     statement.bindLong(index + 2, number)
   *     }
   */
  protected fun executeBlock(): CodeBlock {
    val result = CodeBlock.builder()

    val precedingArrays = mutableListOf<String>()
    val bindStatements = CodeBlock.builder()
    val replacements = mutableListOf<Pair<IntRange, String>>()
    val argumentCounts = mutableListOf<String>()

    var needsFreshStatement = false

    val positionToArgument = mutableListOf<Triple<Int, BindableQuery.Argument, SqlBindExpr?>>()
    query.arguments.forEach { argument ->
      if (argument.bindArgs.isNotEmpty()) {
        argument.bindArgs.forEach { bindArg ->
          positionToArgument.add(Triple(bindArg.node.textRange.startOffset, argument, bindArg))
        }
      } else {
        positionToArgument.add(Triple(0, argument, null))
      }
    }

    val seenArrayArguments = mutableSetOf<BindableQuery.Argument>()

    // A list of [SqlBindExpr] in order of appearance in the query.
    val orderedBindArgs = positionToArgument.sortedBy { it.first }

    // The number of non-array bindArg's we've encountered so far
    var nonArrayBindArgsCount = 0

    val argumentNameAllocator = NameAllocator().apply {
      query.arguments.forEach { newName(it.type.name) }
    }

    // For each argument in the sql
    orderedBindArgs.forEach { (_, argument, bindArg) ->
      val type = argument.type
      // Need to replace the single argument with a group of indexed arguments, calculated at
      // runtime from the list parameter:
      // val idIndexes = id.mapIndexed { index, _ -> "?${1 + previousArray.size + index}" }.joinToString(prefix = "(", postfix = ")")
      val offset = (precedingArrays.map { "$it.size" } + "${nonArrayBindArgsCount + 1}")
          .joinToString(separator = " + ")
      if (bindArg?.isArrayParameter() == true) {
        needsFreshStatement = true

        if (seenArrayArguments.add(argument)) {
          result.addStatement("""
            |val ${type.name}Indexes = createArguments(count = ${type.name}.size, offset = $offset)
          """.trimMargin())
        }

        // Replace the single bind argument with the array of bind arguments:
        // WHERE id IN ${idIndexes}
        replacements.add(bindArg.range to "\$${type.name}Indexes")

        // Perform the necessary binds:
        // id.forEachIndex { index, parameter ->
        //   statement.bindLong(1 + previousArray.size + index, parameter)
        // }
        val indexCalculator = "index + $offset"
        val elementName = argumentNameAllocator.newName(type.name)
        bindStatements.addStatement("""
          |${type.name}.forEachIndexed { index, $elementName ->
          |%L}
        """.trimMargin(), type.copy(name = elementName).preparedStatementBinder(indexCalculator))

        precedingArrays.add(type.name)
        argumentCounts.add("${type.name}.size")
      } else {
        nonArrayBindArgsCount += 1
        if (type.javaType.isNullable) {
          val parent = bindArg?.parent
          if (parent is SqlBinaryEqualityExpr) {
            needsFreshStatement = true

            var symbol = parent.childOfType(SqlTypes.EQ) ?: parent.childOfType(SqlTypes.EQ2)
            val nullableEquality: String
            if (symbol != null) {
              nullableEquality = "${symbol.leftWhitspace()}IS${symbol.rightWhitespace()}"
            } else {
              symbol = parent.childOfType(SqlTypes.NEQ) ?: parent.childOfType(SqlTypes.NEQ2)!!
              nullableEquality = "${symbol.leftWhitspace()}IS NOT${symbol.rightWhitespace()}"
            }

            val block = CodeBlock.of("if (${type.name} == null) \"$nullableEquality\" else \"${symbol.text}\"")
            replacements.add(symbol.range to "\${ $block }")
          }
        }
        // Binds each parameter to the statement:
        // statement.bindLong(1, id)
        bindStatements.add(type.preparedStatementBinder(offset))

        // Replace the named argument with a non named/indexed argument.
        // This allows us to use the same algorithm for non Sqlite dialects
        // :name becomes ?
        if (bindArg != null) {
          replacements.add(bindArg.range to "?")
        }
      }
    }

    val id = if (needsFreshStatement) "null" else "${query.id}"

    // Adds the actual SqlPreparedStatement:
    // statement = database.prepareStatement("SELECT * FROM test")
    val executeMethod = if (query is NamedQuery) {
      "return $DRIVER_NAME.executeQuery"
    } else {
      "$DRIVER_NAME.execute"
    }
    if (nonArrayBindArgsCount != 0) {
      argumentCounts.add(0, nonArrayBindArgsCount.toString())
    }
    val arguments = mutableListOf<Any>(
        query.statement.rawSqlText(replacements),
        argumentCounts.ifEmpty { listOf(0) }.joinToString(" + ")
    )
    val binder: String

    if (argumentCounts.isEmpty()) {
      binder = ""
    } else {
      arguments.add(CodeBlock.builder()
          .addStatement(" {")
          .indent()
          .add(bindStatements.build())
          .unindent()
          .add("}")
          .build())
      binder = "%L"
    }
    result.add("$executeMethod($id, %P, %L)$binder\n", *arguments.toTypedArray())

    return result.build()
  }

  private fun PsiElement.leftWhitspace(): String {
    return if (prevSibling is PsiWhiteSpace) "" else " "
  }

  private fun PsiElement.rightWhitespace(): String {
    return if (nextSibling is PsiWhiteSpace) "" else " "
  }

  protected fun addJavadoc(builder: FunSpec.Builder) {
    query.javadocText()?.let { builder.addKdoc(it) }
  }
}
