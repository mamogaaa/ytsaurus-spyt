package tech.ytsaurus.spyt.serializers

import org.apache.spark.metrics.yt.YtMetricsRegister
import org.apache.spark.metrics.yt.YtMetricsRegister.ytMetricsSource._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types._
import org.apache.spark.sql.yson.{DatetimeType, UInt64Type, YsonType}
import org.slf4j.LoggerFactory
import tech.ytsaurus.client.TableWriter
import tech.ytsaurus.client.rows.{WireProtocolWriteable, WireRowSerializer}
import tech.ytsaurus.core.tables.{ColumnValueType, TableSchema}
import tech.ytsaurus.spyt.serialization.YsonEncoder
import tech.ytsaurus.spyt.serializers.SchemaConverter.{Unordered, decimalToBinary}
import tech.ytsaurus.spyt.wrapper.LogLazy
import tech.ytsaurus.typeinfo.TiType

import java.util.concurrent.{Executors, TimeUnit}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class InternalRowSerializer(schema: StructType, schemaHint: Map[String, YtLogicalType],
                            typeV3Format: Boolean = false) extends WireRowSerializer[InternalRow] with LogLazy {

  private val log = LoggerFactory.getLogger(getClass)

  private val tableSchema = SchemaConverter.tableSchema(schema, Unordered, schemaHint, typeV3Format)

  override def getSchema: TableSchema = tableSchema

  private def valueId(id: Int, idMapping: Array[Int]): Int = {
    if (idMapping != null) {
      idMapping(id)
    } else id
  }

  private def getColumnType(i: Int): ColumnValueType = {
    def isComposite(t: TiType): Boolean = t.isList || t.isDict || t.isStruct || t.isTuple || t.isVariant

    if (typeV3Format) {
      val column = tableSchema.getColumnSchema(i)
      val t = column.getTypeV3
      if (t.isOptional) {
        val inner = t.asOptional().getItem
        if (inner.isOptional || isComposite(inner)) {
          ColumnValueType.COMPOSITE
        } else {
          column.getType
        }
      } else if (isComposite(t)) {
        ColumnValueType.COMPOSITE
      } else {
        column.getType
      }
    } else {
      tableSchema.getColumnType(i)
    }
  }

  private def writeHeader(writeable: WireProtocolWriteable, idMapping: Array[Int], aggregate: Boolean,
                          i: Int, length: Int): Unit = {
    writeable.writeValueHeader(valueId(i, idMapping), getColumnType(i), aggregate, length)
  }

  private def writeBytes(writeable: WireProtocolWriteable, idMapping: Array[Int], aggregate: Boolean,
                         i: Int, bytes: Array[Byte]): Unit = {
    writeHeader(writeable, idMapping, aggregate, i, bytes.length)
    writeable.onBytes(bytes)
  }

  override def serializeRow(row: InternalRow,
                            writeable: WireProtocolWriteable,
                            keyFieldsOnly: Boolean,
                            aggregate: Boolean,
                            idMapping: Array[Int]): Unit = {
    writeable.writeValueCount(row.numFields)
    for {
      i <- 0 until row.numFields
    } {
      if (row.isNullAt(i)) {
        writeable.writeValueHeader(valueId(i, idMapping), ColumnValueType.NULL, aggregate, 0)
      } else {
        val sparkField = schema(i)
        val ytFieldHint = if (typeV3Format) Some(tableSchema.getColumnSchema(i).getTypeV3) else None
        sparkField.dataType match {
          case BinaryType | YsonType => writeBytes(writeable, idMapping, aggregate, i, row.getBinary(i))
          case StringType => writeBytes(writeable, idMapping, aggregate, i, row.getUTF8String(i).getBytes)
          case d: DecimalType =>
            if (!typeV3Format) {
              throw new IllegalArgumentException("Writing decimal type without enabled type_v3 is not supported")
            }
            val value = row.getDecimal(i, d.precision, d.scale)
            val binary = decimalToBinary(ytFieldHint, d, value)
            writeBytes(writeable, idMapping, aggregate, i, binary)
          case t@(ArrayType(_, _) | StructType(_) | MapType(_, _, _)) =>
            val skipNulls = sparkField.metadata.contains("skipNulls") && sparkField.metadata.getBoolean("skipNulls")
            writeBytes(writeable, idMapping, aggregate, i,
              YsonEncoder.encode(row.get(i, sparkField.dataType), t, skipNulls, typeV3Format, ytFieldHint))
          case atomic =>
            writeHeader(writeable, idMapping, aggregate, i, 0)
            atomic match {
              case ByteType => writeable.onInteger(row.getByte(i))
              case ShortType => writeable.onInteger(row.getShort(i))
              case IntegerType => writeable.onInteger(row.getInt(i))
              case LongType => writeable.onInteger(row.getLong(i))
              case BooleanType => writeable.onBoolean(row.getBoolean(i))
              case FloatType => writeable.onDouble(row.getFloat(i))
              case DoubleType => writeable.onDouble(row.getDouble(i))
              case UInt64Type => writeable.onInteger(row.getLong(i))
              case DateType => writeable.onInteger(row.getLong(i))
              case _: DatetimeType => writeable.onInteger(row.getLong(i))
              case TimestampType => writeable.onInteger(row.getLong(i))
            }
        }
      }
    }
  }
}

object InternalRowSerializer {
  private val deserializers: ThreadLocal[mutable.Map[StructType, InternalRowSerializer]] = ThreadLocal.withInitial(() => mutable.ListMap.empty)
  private val context = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  def getOrCreate(schema: StructType,
                  schemaHint: Map[String, YtLogicalType],
                  filters: Array[Filter] = Array.empty,
                  typeV3Format: Boolean = false): InternalRowSerializer = {
    deserializers.get().getOrElseUpdate(schema, new InternalRowSerializer(schema, schemaHint, typeV3Format))
  }

  final def writeRows(writer: TableWriter[InternalRow],
                      rows: java.util.ArrayList[InternalRow],
                      timeout: Duration): Future[Unit] = {
    Future {
      writeRowsRecursive(writer, rows, timeout)
    }(context)
  }

  @tailrec
  private def writeRowsRecursive(writer: TableWriter[InternalRow],
                                 rows: java.util.ArrayList[InternalRow],
                                 timeout: Duration): Unit = {
    if (!writer.write(rows)) {
      YtMetricsRegister.time(writeReadyEventTime, writeReadyEventTimeSum) {
        writer.readyEvent().get(timeout.toMillis, TimeUnit.MILLISECONDS)
      }
      writeRowsRecursive(writer, rows, timeout)
    }
  }
}


