package com.squareup.sqldelight.android

import android.content.Context
import android.database.Cursor
import android.util.LruCache
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.squareup.sqldelight.Transacter
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.db.SqlPreparedStatement
import com.squareup.sqldelight.db.SqlCursor

private val DEFAULT_CACHE_SIZE = 20

class AndroidSqliteDriver private constructor(
  private val openHelper: SupportSQLiteOpenHelper? = null,
  database: SupportSQLiteDatabase? = null,
  private val cacheSize: Int
) : SqlDriver {
  private val transactions = ThreadLocal<Transacter.Transaction>()
  private val database by lazy {
    openHelper?.writableDatabase ?: database!!
  }

  constructor(
    openHelper: SupportSQLiteOpenHelper
  ) : this(openHelper = openHelper, database = null, cacheSize = DEFAULT_CACHE_SIZE)

  /**
   * @param [cacheSize] The number of compiled sqlite statements to keep in memory per connection.
   *   Defaults to 20.
   */
  @JvmOverloads constructor(
    schema: SqlDriver.Schema,
    context: Context,
    name: String? = null,
    factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    callback: SupportSQLiteOpenHelper.Callback = AndroidSqliteDriver.Callback(schema),
    cacheSize: Int = DEFAULT_CACHE_SIZE
  ) : this(
      database = null,
      openHelper = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context)
          .callback(callback)
          .name(name)
          .build()),
      cacheSize = cacheSize
  )

  @JvmOverloads constructor(
    database: SupportSQLiteDatabase,
    cacheSize: Int = DEFAULT_CACHE_SIZE
  ) : this(openHelper = null, database = database, cacheSize = cacheSize)

  private val statements = object : LruCache<Int, AndroidStatement>(cacheSize) {
    override fun entryRemoved(
      evicted: Boolean,
      key: Int,
      oldValue: AndroidStatement,
      newValue: AndroidStatement?
    ) {
      if (evicted) oldValue.close()
    }
  }

  override fun newTransaction(): Transacter.Transaction {
    val enclosing = transactions.get()
    val transaction = Transaction(enclosing)
    transactions.set(transaction)

    if (enclosing == null) {
      database.beginTransactionNonExclusive()
    }

    return transaction
  }

  override fun currentTransaction() = transactions.get()

  inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?
  ) : Transacter.Transaction() {
    override fun endTransaction(successful: Boolean) {
      if (enclosingTransaction == null) {
        if (successful) {
          database.setTransactionSuccessful()
          database.endTransaction()
        } else {
          database.endTransaction()
        }
      }
      transactions.set(enclosingTransaction)
    }
  }

  private fun <T> execute(
    identifier: Int?,
    createStatement: () -> AndroidStatement,
    binders: (SqlPreparedStatement.() -> Unit)?,
    result: AndroidStatement.() -> T
  ): T {
    var statement: AndroidStatement? = null
    if (identifier != null){
      statement = statements.remove(identifier)
    }
    if (statement == null) {
      statement = createStatement()
    }
    try {
      if (binders != null) { statement.binders() }
      return statement.result()
    } finally {
      if (identifier != null) statements.put(identifier, statement)?.close()
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?
  ) = execute(identifier, { AndroidPreparedStatement(database.compileStatement(sql)) }, binders, AndroidStatement::execute)

  override fun executeQuery(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?
  ) = execute(identifier, { AndroidQuery(sql, database, parameters) }, binders, AndroidStatement::executeQuery)

  override fun close() {
    if (openHelper == null) {
      throw IllegalStateException("Tried to call close during initialization")
    }
    statements.evictAll()
    return openHelper.close()
  }

  open class Callback(
    private val schema: SqlDriver.Schema
  ) : SupportSQLiteOpenHelper.Callback(schema.version) {
    override fun onCreate(db: SupportSQLiteDatabase) {
      schema.create(AndroidSqliteDriver(openHelper = null, database = db, cacheSize = 1))
    }

    override fun onUpgrade(
      db: SupportSQLiteDatabase,
      oldVersion: Int,
      newVersion: Int
    ) {
      schema.migrate(AndroidSqliteDriver(openHelper = null, database = db, cacheSize = 1), oldVersion, newVersion)
    }
  }
}

private interface AndroidStatement : SqlPreparedStatement {
  fun execute()
  fun executeQuery(): SqlCursor
  fun close()
}

private class AndroidPreparedStatement(
  private val statement: SupportSQLiteStatement
) : AndroidStatement {
  override fun bindBytes(index: Int, bytes: ByteArray?) {
    if (bytes == null) statement.bindNull(index) else statement.bindBlob(index, bytes)
  }

  override fun bindLong(index: Int, long: Long?) {
    if (long == null) statement.bindNull(index) else statement.bindLong(index, long)
  }

  override fun bindDouble(index: Int, double: Double?) {
    if (double == null) statement.bindNull(index) else statement.bindDouble(index, double)
  }

  override fun bindString(index: Int, string: String?) {
    if (string == null) statement.bindNull(index) else statement.bindString(index, string)
  }

  override fun executeQuery() = throw UnsupportedOperationException()

  override fun execute() {
    statement.execute()
  }

  override fun close() {
    statement.close()
  }
}

private class AndroidQuery(
  private val sql: String,
  private val database: SupportSQLiteDatabase,
  private val argCount: Int
) : SupportSQLiteQuery, AndroidStatement {
  private val binds: MutableMap<Int, (SupportSQLiteProgram) -> Unit> = LinkedHashMap()

  override fun bindBytes(index: Int, bytes: ByteArray?) {
    binds[index] = { if (bytes == null) it.bindNull(index) else it.bindBlob(index, bytes) }
  }

  override fun bindLong(index: Int, long: Long?) {
    binds[index] = { if (long == null) it.bindNull(index) else it.bindLong(index, long) }
  }

  override fun bindDouble(index: Int, double: Double?) {
    binds[index] = { if (double == null) it.bindNull(index) else it.bindDouble(index, double) }
  }

  override fun bindString(index: Int, string: String?) {
    binds[index] = { if (string == null) it.bindNull(index) else it.bindString(index, string) }
  }

  override fun execute() = throw UnsupportedOperationException()

  override fun executeQuery() = AndroidCursor(database.query(this))

  override fun bindTo(statement: SupportSQLiteProgram) {
    for (action in binds.values) {
      action(statement)
    }
  }

  override fun getSql() = sql

  override fun toString() = sql

  override fun getArgCount() = argCount

  override fun close() { }
}

private class AndroidCursor(
  private val cursor: Cursor
) : SqlCursor {
  override fun next() = cursor.moveToNext()
  override fun getString(index: Int) = if (cursor.isNull(index)) null else cursor.getString(index)
  override fun getLong(index: Int) = if (cursor.isNull(index)) null else cursor.getLong(index)
  override fun getBytes(index: Int) = if (cursor.isNull(index)) null else cursor.getBlob(index)
  override fun getDouble(index: Int) = if (cursor.isNull(index)) null else cursor.getDouble(index)
  override fun close() = cursor.close()
}
