package com.squareup.sqldelight.drivers.native

import co.touchlab.sqliter.DatabaseFileContext.deleteDatabase
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.driver.test.TransacterTest

class NativeTransacterTest: TransacterTest() {
  override fun setupDatabase(schema: SqlDriver.Schema): SqlDriver {
    val name = "testdb"
    deleteDatabase(name)
    return NativeSqliteDriver(schema, name)
  }
}