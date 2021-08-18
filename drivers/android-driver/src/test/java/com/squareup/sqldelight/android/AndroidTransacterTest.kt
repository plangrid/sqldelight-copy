package com.squareup.sqldelight.android

import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.squareup.sqldelight.Transacter
import com.squareup.sqldelight.TransactionWithReturn
import com.squareup.sqldelight.TransactionWithoutReturn
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.db.SqlDriver.Schema
import com.squareup.sqldelight.driver.test.TransacterTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class AndroidTransacterTest : TransacterTest() {
  override fun setupDatabase(schema: Schema): SqlDriver {
    return AndroidSqliteDriver(schema, getApplicationContext())
  }

  @Test fun `detect the afterRollback call has escaped the original transaction thread in transaction()`() {
    assertChecksThreadConfinement<TransactionWithoutReturn>(
      scope = { transaction(false, it) },
      block = { afterRollback {} }
    )
  }

  @Test fun `detect the afterCommit call has escaped the original transaction thread in transaction()`() {
    assertChecksThreadConfinement<TransactionWithoutReturn>(
      scope = { transaction(false, it) },
      block = { afterCommit {} }
    )
  }

  @Test fun `detect the rollback call has escaped the original transaction thread in transaction()`() {
    assertChecksThreadConfinement<TransactionWithoutReturn>(
      scope = { transaction(false, it) },
      block = { rollback() }
    )
  }

  @Test fun `detect the afterRollback call has escaped the original transaction thread in transactionWithReturn()`() {
    assertChecksThreadConfinement<TransactionWithReturn<Unit>>(
      scope = { transactionWithResult(false, it) },
      block = { afterRollback {} }
    )
  }

  @Test fun `detect the afterCommit call has escaped the original transaction thread in transactionWithReturn()`() {
    assertChecksThreadConfinement<TransactionWithReturn<Unit>>(
      scope = { transactionWithResult(false, it) },
      block = { afterCommit {} }
    )
  }

  @Test fun `detect the rollback call has escaped the original transaction thread in transactionWithReturn()`() {
    assertChecksThreadConfinement<TransactionWithReturn<Unit>>(
      scope = { transactionWithResult<Unit>(false, it) },
      block = { rollback(Unit) }
    )
  }

  private inline fun <T> assertChecksThreadConfinement(
    crossinline scope: Transacter.(T.() -> Unit) -> Unit,
    crossinline block: T.() -> Unit
  ) {
    lateinit var thread: Thread
    var result: Result<Unit>? = null
    val semaphore = Semaphore(0)

    transacter.scope {
      thread = thread {
        result = runCatching {
          this@scope.block()
        }

        semaphore.release()
      }
    }

    semaphore.acquire()
    thread.interrupt()
    assertThrows(IllegalStateException::class.java) {
      result!!.getOrThrow()
    }
  }
}
