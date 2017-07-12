package io.eels.component.hive

import com.sksamuel.exts.Logging
import io.eels.FilePattern
import io.eels.component.hdfs.{AclSpec, HdfsSource}
import io.eels.component.hive.partition.PartitionMetaData
import io.eels.schema.{Partition, StringType, StructType}
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.metastore.{IMetaStoreClient, TableType}
import org.apache.hadoop.security.UserGroupInformation
import com.sksamuel.exts.OptionImplicits._
import io.eels.util.HdfsIterator

import scala.collection.JavaConverters._
import scala.util.matching.Regex

case class HiveTable(dbName: String,
                     tableName: String)
                    (implicit fs: FileSystem,
                     client: IMetaStoreClient) extends Logging {

  lazy val ops = new HiveOps(client)

  /**
    * Returns all the partitions used by this hive source.
    */
  def partitions(): Seq[Partition] = ops.partitions(dbName, tableName)

  /**
    * Returns all the partitions along with extra meta data per partition, eg location, creation time.
    */
  def partitionMetaData(): Seq[PartitionMetaData] = ops.partitionsMetaData(dbName, tableName)

  def schema: StructType = {
    ops.schema(dbName, tableName)
  }

  /**
    * Returns a list of all files used by this hive table.
    *
    * @param includePartitionDirs if true then the partition directories will be included
    * @param includeTableDir      if true then the main table directory will be included
    * @return paths of all files and directories
    */
  def paths(includePartitionDirs: Boolean, includeTableDir: Boolean): List[Path] = {

    val files = ops.hivePartitions(dbName, tableName).flatMap { partition =>
      val location = partition.getSd.getLocation
      val files = FilePattern(s"$location/*").toPaths()
      if (includePartitionDirs) {
        files :+ new Path(location)
      } else {
        files
      }
    }
    if (includeTableDir) {
      val location = spec().location
      files :+ new Path(location)
    } else {
      files
    }
  }

  /**
    * Returns a list of all files used by this hive table that match the given regex.
    * The full path of the file will be used when matching against the regex.
    *
    * @param includePartitionDirs if true then the partition directories will be included
    * @param includeTableDir      if true then the main table directory will be included
    * @return paths of all files and directories
    */
  def paths(includePartitionDirs: Boolean, includeTableDir: Boolean, regex: Regex): List[Path] = {
    paths(includePartitionDirs, includeTableDir).filter { path => regex.pattern.matcher(path.toString).matches }
  }

  /**
    * Returns all the files used by this table. The result is a mapping of partition path to the files contained
    * in that partition.
    */
  def files(): Map[Path, Seq[Path]] = {
    ops.hivePartitions(dbName, tableName).map { p =>
      val location = new Path(p.getSd.getLocation)
      val paths = HdfsIterator.remote(fs.listFiles(location, false)).map(_.getPath).toList
      location -> paths
    }.toMap
  }

  def setPermissions(permission: FsPermission,
                     includePartitionDirs: Boolean = false,
                     includeTableDir: Boolean = false): Unit = {
    paths(includePartitionDirs, includeTableDir).foreach(fs.setPermission(_, permission))
  }

  def showDdl(ifNotExists: Boolean = true): String = {
    val _spec = spec()
    val partitions = ops.partitionKeys(dbName, tableName)
    HiveDDL.showDDL(
      tableName,
      schema.fields,
      tableType = _spec.tableType,
      location = _spec.location.some,
      serde = _spec.serde,
      partitions = partitions.map(PartitionColumn(_, StringType)),
      outputFormat = _spec.outputFormat,
      inputFormat = _spec.inputFormat,
      ifNotExists = ifNotExists)
  }

  /**
    * Sets the acl for all files of this hive source.
    * Even if the files are not located inside the table directory, this function will find them
    * and correctly update the spec.
    *
    * @param acl the acl values to set
    */
  def setAcl(acl: AclSpec,
             includePartitionDirs: Boolean = false,
             includeTableDir: Boolean = false): Unit = {
    paths(includePartitionDirs, includeTableDir).foreach { path =>
      HdfsSource(path).setAcl(acl)
    }
  }

  // returns the permission of the table location path
  def tablePermission(): FsPermission = {
    val location = ops.location(dbName, tableName)
    fs.getFileStatus(new Path(location)).getPermission
  }

  /**
    * Returns a TableSpec which contains details of the underlying table.
    * Similar to the Table class in the Hive API but using scala friendly types.
    */
  def spec(): TableSpec = {
    val table = client.getTable(dbName, tableName)
    val tableType = TableType.values().find(_.name.toLowerCase == table.getTableType.toLowerCase)
      .getOrElse(sys.error("Hive table type is not supported by this version of hive"))
    val params = table.getParameters.asScala.toMap ++ table.getSd.getParameters.asScala.toMap
    TableSpec(
      tableName,
      tableType,
      table.getSd.getLocation,
      table.getSd.getCols.asScala,
      table.getSd.getNumBuckets,
      table.getSd.getBucketCols.asScala.toList,
      params,
      table.getSd.getInputFormat,
      table.getSd.getOutputFormat,
      table.getSd.getSerdeInfo.getName,
      table.getRetention,
      table.getCreateTime,
      table.getLastAccessTime,
      table.getOwner
    )
  }

  // returns the location of this table as a hadoop Path
  def location(): Path = new Path(spec().location)

  def deletePartition(partition: Partition, deleteData: Boolean): Unit = {
    logger.debug(s"Deleting partition ${partition.pretty}")
    client.dropPartition(dbName, tableName, partition.values.asJava, deleteData)
  }

  def drop(): Unit = {
    logger.debug(s"Dropping table $dbName:$tableName")
    client.dropTable(dbName, tableName, true, true)
  }

  def truncate(removePartitions: Boolean): Unit = {
    logger.debug(s"Truncating table $dbName:$tableName")
    if (removePartitions)
      new HiveOps(client).partitions(dbName, tableName).foreach(deletePartition(_, true))
    else {
      files().values.foreach(_.foreach(path => fs.delete(path, false)))
    }
  }

  def login(principal: String, keytabPath: java.nio.file.Path): Unit = {
    UserGroupInformation.loginUserFromKeytab(principal, keytabPath.toString)
  }

  def toHdfsSource = HdfsSource(FilePattern(location.toString + "/*"))

  def source = HiveSource(dbName, tableName)
  def sink = HiveSink(dbName, tableName)
}
