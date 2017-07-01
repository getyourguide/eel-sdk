package io.eels.component.parquet

import com.sksamuel.exts.Logging
import com.sksamuel.exts.OptionImplicits._
import com.sksamuel.exts.io.Using
import io.eels.component.FlowableIterator
import io.eels.component.parquet.util.ParquetIterator
import io.eels.schema.StructType
import io.eels.{Part, Predicate, Row}
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.schema.MessageType

class ParquetPart(path: Path,
                  predicate: Option[Predicate],
                  projection: Seq[String])
                 (implicit conf: Configuration) extends Part with Logging with Using {

  def readSchema: Option[MessageType] = {
    if (projection.isEmpty) None
    else {
      val messageType = ParquetFileReader.open(conf, path).getFileMetaData.getSchema
      val structType = ParquetSchemaFns.fromParquetMessageType(messageType)
      val projected = StructType(structType.fields.filter(field => projection.contains(field.name)))
      ParquetSchemaFns.toParquetMessageType(projected).some
    }
  }

  override def open(): Flowable[Row] = {

    val reader = RowParquetReaderFn(path, predicate, readSchema)
    val iterator = ParquetIterator(reader)

    val disposable = new Disposable {
      override def dispose(): Unit = reader.close()
      override def isDisposed: Boolean = false
    }

    FlowableIterator(iterator, disposable)
  }
}