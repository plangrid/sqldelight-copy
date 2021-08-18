package com.squareup.sqldelight.intellij.intentions

import com.google.common.truth.Truth
import com.squareup.sqldelight.core.lang.SqlDelightFileType
import com.squareup.sqldelight.intellij.SqlDelightFixtureTestCase

class QualifyColumnNameIntentionTest : SqlDelightFixtureTestCase() {

  fun testIntentionAvailableOnColumnName() {
    myFixture.configureByText(
      SqlDelightFileType,
      CREATE_TABLE + """
        |SELECT id, nam<caret>e
        |FROM team;
      """.trimMargin()
    )

    val intention = QualifyColumnNameIntention()
    Truth.assertThat(
      intention.isAvailable(
        myFixture.project,
        myFixture.editor,
        myFixture.file.findElementAt(myFixture.editor.caretModel.offset)!!
      )
    )
      .isTrue()
  }

  fun testIntentionNotAvailableOnQualifiedColumnName() {
    myFixture.configureByText(
      SqlDelightFileType,
      CREATE_TABLE + """
        |SELECT id, team.nam<caret>e
        |FROM team;
      """.trimMargin()
    )

    val intention = QualifyColumnNameIntention()
    Truth.assertThat(
      intention.isAvailable(
        myFixture.project,
        myFixture.editor,
        myFixture.file.findElementAt(myFixture.editor.caretModel.offset)!!
      )
    )
      .isFalse()
  }

  fun testIntentionExecutionWithoutTableAlias() {
    myFixture.configureByText(
      SqlDelightFileType,
      CREATE_TABLE + """
        |SELECT id, nam<caret>e
        |FROM team;
      """.trimMargin()
    )

    val intention = QualifyColumnNameIntention()
    intention.invoke(
      myFixture.project,
      myFixture.editor,
      myFixture.file.findElementAt(myFixture.editor.caretModel.offset)!!
    )

    myFixture.checkResult(
      CREATE_TABLE + """
        |SELECT id, team.name
        |FROM team;
      """.trimMargin()
    )
  }

  fun testIntentionExecutionWithTableAlias() {
    myFixture.configureByText(
      SqlDelightFileType,
      CREATE_TABLE + """
        |SELECT id, nam<caret>e
        |FROM team t;
      """.trimMargin()
    )

    val intention = QualifyColumnNameIntention()
    intention.invoke(
      myFixture.project,
      myFixture.editor,
      myFixture.file.findElementAt(myFixture.editor.caretModel.offset)!!
    )

    myFixture.checkResult(
      CREATE_TABLE + """
        |SELECT id, t.name
        |FROM team t;
      """.trimMargin()
    )
  }

  companion object {
    val CREATE_TABLE = """
      |CREATE TABLE team(
      |  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      |  name TEXT NOT NULL UNIQUE
      |);
      |""".trimMargin()
  }
}
