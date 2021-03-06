package scala.slick.driver

import scala.slick.SLICKException
import scala.slick.ql._
import scala.slick.ast._
import scala.slick.util.ValueLinearizer

/**
 * SLICK driver for MySQL.
 *
 * <p>This driver implements the ExtendedProfile with the following
 * limitations:</p>
 * <ul>
 *   <li><code>Sequence.curr</code> to get the current value of a sequence is
 *     not supported. Other sequence features are emulated.</li>
 * </ul>
 *
 * @author szeiger
 */
trait MySQLDriver extends ExtendedDriver { driver =>

  override val typeMapperDelegates = new TypeMapperDelegates

  override def createQueryBuilder(node: Node, vl: ValueLinearizer[_]): QueryBuilder = new QueryBuilder(node, vl)
  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  override def createColumnDDLBuilder(column: RawNamedColumn, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)
  override def createSequenceDDLBuilder(seq: Sequence[_]): SequenceDDLBuilder[_] = new SequenceDDLBuilder(seq)

  override def quoteIdentifier(id: String) = '`' + id + '`'

  class QueryBuilder(ast: Node, linearizer: ValueLinearizer[_]) extends super.QueryBuilder(ast, linearizer) {
    override protected val scalarFrom = Some("DUAL")
    override protected val supportsCast = false

    override def expr(n: Node, skipParens: Boolean = false): Unit = n match {
      case EscFunction("concat", l, r) => b += "concat("; expr(l); b += ','; expr(r); b += ')'
      case Sequence.Nextval(seq) => b += quoteIdentifier(seq.name + "_nextval") += "()"
      case Sequence.Currval(seq) => b += quoteIdentifier(seq.name + "_currval") += "()"
      case _ => super.expr(n, skipParens)
    }

    override protected def buildFetchOffsetClause(fetch: Option[Long], offset: Option[Long]) = (fetch, offset) match {
      case (Some(t), Some(d)) => b += " LIMIT " += d += ',' += t
      case (Some(t), None) => b += " LIMIT " += t
      case (None, Some(d)) => b += " LIMIT " += d += ",18446744073709551615"
      case _ =>
    }

    override protected def buildOrdering(n: Node, o: Ordering) {
      if(o.nulls.last && !o.direction.desc) {
        b += "isnull("
        expr(n)
        b += "),"
      } else if(o.nulls.first && o.direction.desc) {
        b += "isnull("
        expr(n)
        b += ") desc,"
      }
      expr(n)
      if(o.direction.desc) b += " desc"
    }
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) {
    override protected def dropForeignKey(fk: ForeignKey[_ <: TableNode, _]) = {
      "ALTER TABLE " + table.tableName + " DROP FOREIGN KEY " + fk.name
    }
  }

  class ColumnDDLBuilder(column: RawNamedColumn) extends super.ColumnDDLBuilder(column) {
    override protected def appendOptions(sb: StringBuilder) {
      if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if(notNull) sb append " NOT NULL"
      if(autoIncrement) sb append " AUTO_INCREMENT"
      if(primaryKey) sb append " PRIMARY KEY"
    }
  }

  class SequenceDDLBuilder[T](seq: Sequence[T]) extends super.SequenceDDLBuilder(seq) {
    override def buildDDL: DDL = {
      import seq.integral._
      val sqlType = seq.typeMapper(driver).sqlTypeName
      val t = sqlType + " not null"
      val increment = seq._increment.getOrElse(one)
      val desc = increment < zero
      val minValue = seq._minValue getOrElse (if(desc) fromInt(java.lang.Integer.MIN_VALUE) else one)
      val maxValue = seq._maxValue getOrElse (if(desc) fromInt(-1) else fromInt(java.lang.Integer.MAX_VALUE))
      val start = seq._start.getOrElse(if(desc) maxValue else minValue)
      val beforeStart = start - increment
      if(!seq._cycle && (seq._minValue.isDefined && desc || seq._maxValue.isDefined && !desc))
        throw new SLICKException("Sequences with limited size and without CYCLE are not supported by MySQLDriver's sequence emulation")
      val incExpr = if(seq._cycle) {
        if(desc) "if(id-"+(-increment)+"<"+minValue+","+maxValue+",id-"+(-increment)+")"
        else "if(id+"+increment+">"+maxValue+","+minValue+",id+"+increment+")"
      } else {
        "id+("+increment+")"
      }
      //TODO Implement currval function
      new DDL {
        val createPhase1 = Iterable(
          "create table " + quoteIdentifier(seq.name + "_seq") + " (id " + t + ")",
          "insert into " + quoteIdentifier(seq.name + "_seq") + " values (" + beforeStart + ")",
          "create function " + quoteIdentifier(seq.name + "_nextval") + "() returns " + sqlType + " begin update " +
            quoteIdentifier(seq.name + "_seq") + " set id=last_insert_id(" + incExpr + "); return last_insert_id(); end")
        val createPhase2 = Nil
        val dropPhase1 = Nil
        val dropPhase2 = Iterable(
          "drop function " + quoteIdentifier(seq.name + "_nextval"),
          "drop table " + quoteIdentifier(seq.name + "_seq"))
      }
    }
  }

  class TypeMapperDelegates extends super.TypeMapperDelegates {
    override val stringTypeMapperDelegate = new StringTypeMapperDelegate {
      override def valueToSQLLiteral(value: String) = if(value eq null) "NULL" else {
        val sb = new StringBuilder
        sb append '\''
        for(c <- value) c match {
          case '\'' => sb append "\\'"
          case '"' => sb append "\\\""
          case 0 => sb append "\\0"
          case 26 => sb append "\\Z"
          case '\b' => sb append "\\b"
          case '\n' => sb append "\\n"
          case '\r' => sb append "\\r"
          case '\t' => sb append "\\t"
          case '\\' => sb append "\\\\"
          case _ => sb append c
        }
        sb append '\''
        sb.toString
      }
    }

    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate {
      override def sqlType = java.sql.Types.BINARY
      override def sqlTypeName = "BINARY(16)"
    }
  }
}

object MySQLDriver extends MySQLDriver
