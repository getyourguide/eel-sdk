package io.eels.component.jdbc

import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, Executors, TimeUnit}

import com.sksamuel.scalax.collection.BlockingQueueConcurrentIterator
import com.sksamuel.scalax.jdbc.ResultSetIterator
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.StrictLogging
import io.eels.{FrameSchema, Row, Sink, Writer}

import scala.language.implicitConversions

case class JdbcSink(url: String, table: String, props: JdbcSinkProps = JdbcSinkProps())
  extends Sink
    with StrictLogging {

  private val BufferSize = 1000
  private val config = ConfigFactory.load()
  private val warnIfMissingRewriteBatchedStatements = config.getBoolean("eel.jdbc.warnIfMissingRewriteBatchedStatements")
  private val autoCommit = config.getBoolean("eel.jdbc.autoCommit")

  if (!url.contains("rewriteBatchedStatements")) {
    if (warnIfMissingRewriteBatchedStatements) {
      logger.warn("JDBC connection string does not contain the property 'rewriteBatchedStatements=true' which can be a major performance boost when writing data via JDBC. " +
        "To remove this warning, add the property to your connection string, or set eel.jdbc.warnIfMissingRewriteBatchedStatements=false")
    }
  }

  private def tableExists(conn: Connection): Boolean = {
    logger.debug("Fetching tables to detect if table exists")
    val tables = ResultSetIterator(conn.getMetaData.getTables(null, null, null, Array("TABLE"))).toList
    logger.debug(s"${tables.size} tables found")
    tables.map(_.apply(3).toLowerCase) contains table.toLowerCase
  }

  override def writer: Writer = new Writer {

    val dialect = props.dialectFn(url)
    logger.debug(s"Writer will use dialect=$dialect")

    logger.debug(s"Connecting to jdbc sink $url...")
    val conn = DriverManager.getConnection(url)
    conn.setAutoCommit(autoCommit)
    logger.debug(s"Connected to $url")

    val created = new AtomicBoolean(false)

    def createTable(row: Row, schema: FrameSchema): Unit = {
      if (!created.get) {
        JdbcSink.this.synchronized {
          if (!created.get && props.createTable && !tableExists(conn)) {
            logger.info(s"Creating sink table $table")

            val sql = dialect.create(schema, table)
            logger.debug(s"Executing [$sql]")

            val stmt = conn.createStatement()
            try {
              stmt.executeUpdate(sql)
            } finally {
              stmt.close()
            }
          }
          created.set(true)
        }
      }
    }

    implicit def toRunnable(thunk: => Unit): Runnable = new Runnable {
      override def run(): Unit = thunk
    }

    var schema: FrameSchema = null
    val queue = new ArrayBlockingQueue[Row](BufferSize)

    import com.sksamuel.scalax.concurrent.ExecutorImplicits._

    val latch = new CountDownLatch(props.threads)
    val executor = Executors.newFixedThreadPool(props.threads)
    for (k <- 1 to props.threads) {
      executor.submit {
        try {
          BlockingQueueConcurrentIterator(queue, Row.Sentinel)
            .grouped(props.batchSize)
            .withPartial(true)
            .foreach { rows =>
              doBatch(rows, schema)
            }
        }
        catch {
          case t: Throwable =>
            logger.error("Fatal error occurred", t)
            executor.shutdownNow()
        }
        finally {
          latch.countDown()
        }
      }
    }
    executor.submit {
      latch.await(1, TimeUnit.DAYS)
      conn.close()
      logger.info("Closed JDBC Connection")
    }
    executor.shutdown()

    def doBatch(rows: Seq[Row], schema: FrameSchema): Unit = {
      logger.info(s"Inserting batch [${rows.size} rows]")
      val stmt = conn.createStatement()
      rows.map(dialect.insert(_, schema, table)).foreach(stmt.addBatch)
      try {
        stmt.executeBatch()
        if (!autoCommit)
          conn.commit()
        logger.info("Batch complete")
      } catch {
        case e: Exception =>
          logger.error("Batch failure", e)
          throw e
      } finally {
        if (!autoCommit)
          conn.rollback()
        stmt.close()
      }
    }

    override def close(): Unit = {
      queue.put(Row.Sentinel)
      logger.debug("Waiting for sink writer to complete")
      executor.awaitTermination(1, TimeUnit.DAYS)
    }

    override def write(row: Row, schema: FrameSchema): Unit = {
      createTable(row, schema)
      this.schema = schema
      queue.put(row)
    }
  }
}

case class JdbcSinkProps(createTable: Boolean = false,
                         batchSize: Int = 100,
                         dialectFn: String => JdbcDialect = url => JdbcDialect(url),
                         threads: Int = 1)

