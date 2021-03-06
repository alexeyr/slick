package scala.slick.driver

import scala.slick.SLICKException
import scala.slick.ql._
import scala.slick.ast._
import java.sql.{Timestamp, Time, Date}
import scala.slick.util.ValueLinearizer

/**
 * SLICK driver for SQLite.
 *
 * <p>This driver implements the ExtendedProfile with the following
 * limitations:</p>
 * <ul>
 *   <li>Sequences are not supported because SQLite does not have them.</li>
 *   <li>Blobs are not supported by the SQLite JDBC driver (but binary data in
 *     the form of <code>Array[Byte]</code> is).</li>
 *   <li>SQLite does not allow mutation of result sets. All cursors are
 *     read-only.</li>
 *   <li><code>Functions.user</code> and <code>Functions.database</code> are
 *     not available in SQLite. SLICK will return empty strings for
 *     both.</li>
 * </ul>
 */
trait SQLiteDriver extends ExtendedDriver { driver =>

  override val typeMapperDelegates = new TypeMapperDelegates
  override def createQueryBuilder(node: Node, vl: ValueLinearizer[_]): QueryBuilder = new QueryBuilder(node, vl)
  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  override def createColumnDDLBuilder(column: RawNamedColumn, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)

  class QueryBuilder(ast: Node, linearizer: ValueLinearizer[_]) extends super.QueryBuilder(ast, linearizer) {
    override protected val supportsTuples = false
    override protected val concatOperator = Some("||")

    override protected def buildOrdering(n: Node, o: Ordering) {
      if(o.nulls.last && !o.direction.desc) {
        b += "("
        expr(n)
        b += ") is null,"
      } else if(o.nulls.first && o.direction.desc) {
        b += "("
        expr(n)
        b += ") is null desc,"
      }
      expr(n)
      if(o.direction.desc) b += " desc"
    }

    override protected def buildFetchOffsetClause(fetch: Option[Long], offset: Option[Long]) = (fetch, offset) match {
      case (Some(t), Some(d)) => b += " LIMIT " += d += "," += t
      case (Some(t), None) => b += " LIMIT " += t
      case (None, Some(d)) => b += " LIMIT " += d += ",-1"
      case _ =>
    }

    override def expr(c: Node, skipParens: Boolean = false): Unit = c match {
      case EscFunction("ucase", ch) => b += "upper("; expr(ch, true); b += ')'
      case EscFunction("lcase", ch) => b += "lower("; expr(ch, true); b += ')'
      case EscFunction("mod", l, r) => b += '('; expr(l); b += '%'; expr(r); b += ')'
      case EscFunction("ceiling", ch) => b += "round("; expr(ch); b += "+0.5)"
      case EscFunction("floor", ch) => b += "round("; expr(ch); b += "-0.5)"
      case EscFunction("user") => b += "''"
      case EscFunction("database") => b += "''"
      case s: SimpleFunction if s.scalar && s.name != "concat" =>
        /* The SQLite JDBC driver does not support ODBC {fn ...} escapes, so we try
         * unescaped function calls by default */
        b += s.name += '('
        b.sep(s.nodeChildren, ",")(expr(_, true))
        b += ")"
      case _ => super.expr(c, skipParens)
    }
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) {
    override protected val foreignKeys = Nil // handled directly in addTableOptions
    override protected val primaryKeys = Nil // handled directly in addTableOptions

    override protected def addTableOptions(b: StringBuilder) {
      for(pk <- table.primaryKeys) {
        b append ","
        addPrimaryKey(pk, b)
      }
      for(fk <- table.foreignKeys) {
        b append ","
        addForeignKey(fk, b)
      }
    }
  }

  class ColumnDDLBuilder(column: RawNamedColumn) extends super.ColumnDDLBuilder(column) {
    override protected def appendOptions(sb: StringBuilder) {
      if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if(autoIncrement) sb append " PRIMARY KEY AUTOINCREMENT"
      else if(notNull) sb append " NOT NULL"
      else if(primaryKey) sb append " PRIMARY KEY"
    }
  }

  class TypeMapperDelegates extends super.TypeMapperDelegates {
    override val booleanTypeMapperDelegate = new BooleanTypeMapperDelegate
    override val dateTypeMapperDelegate = new DateTypeMapperDelegate
    override val timeTypeMapperDelegate = new TimeTypeMapperDelegate
    override val timestampTypeMapperDelegate = new TimestampTypeMapperDelegate
    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate

    /* SQLite does not have a proper BOOLEAN type. The suggested workaround is
     * INTEGER with constants 1 and 0 for TRUE and FALSE. */
    class BooleanTypeMapperDelegate extends super.BooleanTypeMapperDelegate {
      override def sqlTypeName = "INTEGER"
      override def valueToSQLLiteral(value: Boolean) = if(value) "1" else "0"
    }
    /* The SQLite JDBC driver does not support the JDBC escape syntax for
     * date/time/timestamp literals. SQLite expects these values as milliseconds
     * since epoch. */
    class DateTypeMapperDelegate extends super.DateTypeMapperDelegate {
      override def valueToSQLLiteral(value: Date) = value.getTime.toString
    }
    class TimeTypeMapperDelegate extends super.TimeTypeMapperDelegate {
      override def valueToSQLLiteral(value: Time) = value.getTime.toString
    }
    class TimestampTypeMapperDelegate extends super.TimestampTypeMapperDelegate {
      override def valueToSQLLiteral(value: Timestamp) = value.getTime.toString
    }
    class UUIDTypeMapperDelegate extends super.UUIDTypeMapperDelegate {
      override def sqlType = java.sql.Types.BLOB
    }
  }
}

object SQLiteDriver extends SQLiteDriver
