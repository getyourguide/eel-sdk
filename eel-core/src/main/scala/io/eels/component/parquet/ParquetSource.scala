package io.eels.component.parquet

import com.sksamuel.exts.Logging
import com.sksamuel.exts.io.Using
import io.eels.{FilePattern, Part, Row, Source}
import io.eels.component.avro.AvroSchemaFns
import io.eels.component.avro.AvroSchemaMerge
import io.eels.schema.Schema
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.Footer
import org.apache.parquet.hadoop.ParquetFileReader
import rx.lang.scala.Observable

object ParquetSource {

  def apply(path: java.nio.file.Path)(implicit fs: FileSystem, conf: Configuration): ParquetSource =
    apply(FilePattern(path))

  def apply(path: Path)(implicit fs: FileSystem, conf: Configuration): ParquetSource =
    apply(FilePattern(path))
}

case class ParquetSource(pattern: FilePattern)(implicit fs: FileSystem, conf: Configuration) extends Source with Logging with Using {


  // the schema returned by the parquet source should be a merged version of the
  // schemas contained in all the files.
  override def schema(): Schema = {
    val paths = pattern.toPaths()
    val schemas = paths.map { path =>
      using(ParquetReaderFns.createReader(path, None, None)) { reader =>
        val record = Option(reader.read()).getOrElse {
          sys.error(s"Cannot read $path for schema; file contains no records")
        }
        record.getSchema
      }
    }
    val avroSchema = AvroSchemaMerge("record", "namspace", schemas)
    AvroSchemaFns.fromAvroSchema(avroSchema)
  }

  override def parts(): List[Part] = {
    val paths = pattern.toPaths()
    logger.debug(s"Parquet source will read from $paths")
    val _schema = schema()
    paths.map { it => new ParquetPart(it, _schema) }
  }

  import scala.collection.JavaConverters._

  def footers(): List[Footer] = {
    val paths = pattern.toPaths()
    logger.debug(s"Parquet source will read from $paths")
    paths.flatMap { it =>
      val status = fs.getFileStatus(it)
      logger.debug(s"status=$status; path=$it")
      ParquetFileReader.readAllFootersInParallel(conf, status).asScala
    }
  }
}

class ParquetPart(val path: Path, val schema: Schema) extends Part {
  override def data(): Observable[Row] = Observable { sub =>
    try {
      sub.onStart()
      val reader = ParquetReaderFns.createReader(path, None, None)
      ParquetRowIterator(reader).foreach { it =>
        sub.onNext(it)
      }
    } catch {
      case t: Throwable =>
        sub.onError(t)
    }
    if (!sub.isUnsubscribed)
      sub.onCompleted()
  }
}