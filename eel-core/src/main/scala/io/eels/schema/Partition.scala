package io.eels.schema

// Represents a partition, which is the full sequence of partition key/values pairs for a particular record
// eg key1=value1,key2=value2,key3=value3 is a partition
case class Partition(entries: Seq[PartitionEntry]) {

  def pretty: String = entries.map { entry => s"${entry.key}=${entry.value}" }.mkString("/")

  def keys: Seq[String] = entries.map(_.key)

  def values: Seq[String] = entries.map(_.value)

  // returns the PartitionEntry for the given key
  def get(key: String): Option[PartitionEntry] = entries.find(_.key == key)

  def value(key: String): Option[String] = get(key).map(_.value)
}

object Partition {
  val empty = Partition(Nil)
}

// a part of a partition, ie in country=usa/state=alabama, an entry would be state=alabama or country=usa
case class PartitionEntry(key: String, value: String) {

  // returns the key value part in the standard hive key=value format with unquoted values
  def unquoted(): String = s"$key=$value"

  // returns the key value part in the standard hive key=value format with quoted values
  def quoted(): String = s"$key='$value'"
}