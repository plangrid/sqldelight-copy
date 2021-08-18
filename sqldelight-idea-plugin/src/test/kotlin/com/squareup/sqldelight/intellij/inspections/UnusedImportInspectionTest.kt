package com.squareup.sqldelight.intellij.inspections

import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.squareup.sqldelight.intellij.SqlDelightFixtureTestCase

class UnusedImportInspectionTest : SqlDelightFixtureTestCase() {

  override val fixtureDirectory: String = "import-inspection"

  fun testUnusedImportInspection() {
    myFixture.testInspection("", LocalInspectionToolWrapper(UnusedImportInspection()))
  }
}
