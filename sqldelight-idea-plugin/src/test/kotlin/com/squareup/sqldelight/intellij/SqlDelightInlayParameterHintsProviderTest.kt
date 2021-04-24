package com.squareup.sqldelight.intellij

import com.squareup.sqldelight.core.lang.SqlDelightFileType

class SqlDelightInlayParameterHintsProviderTest : SqlDelightFixtureTestCase() {

  fun testInlays() {
    myFixture.configureByText(
      SqlDelightFileType,
      """
      |CREATE TABLE hockeyPlayer(
      |  column1 TEXT as @Nullable String NOT NULL,
      |  column2 TEXT as OffsetDateTime NOT NULL,
      |  column3 TEXT as List<String> NOT NULL,
      |  column4 TEXT as Integer NOT NULL,
      |  column5 TEXT as Map<Int, Int> NOT NULL,
      |  column6 TEXT as List<Short> NOT NULL
      |);
      |
      |INSERT INTO hockeyPlayer (column1, column2, column3, column4, column5, column6)
      |VALUES (<hint text="column1:"/>?, <hint text="column2:"/>?, <hint text="column3:"/>?, <hint text="column4:"/>?, <hint text="column5:"/>?, <hint text="column6:"/>?);
    """.trimMargin()
    )
    myFixture.testInlays()
  }
}
