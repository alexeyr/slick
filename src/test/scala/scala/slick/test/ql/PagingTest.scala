package scala.slick.test.ql

import org.junit.Test
import org.junit.Assert._
import scala.slick.ql._
import scala.slick.session.Database.threadLocalSession
import scala.slick.testutil._
import scala.slick.testutil.TestDB._

object PagingTest extends DBTestObject(H2Mem, SQLiteMem, Postgres, MySQL, DerbyMem, HsqldbMem, SQLServer)

class PagingTest(val tdb: TestDB) extends DBTest {
  import tdb.profile.Table
  import tdb.profile.Implicit._

  object IDs extends Table[Int]("ids") {
    def id = column[Int]("id", O.PrimaryKey)
    def * = id
  }

  @deprecated("Testing deprecated method Query.orderBy", "0.10.0-M2")
  @Test def test() {
    db withSession {

      IDs.ddl.create;
      IDs.insertAll((1 to 10):_*)

      val q1 = for(i <- IDs; _ <- Query orderBy i.id) yield i
      println("q1: "+q1.selectStatement)
      println("    "+q1.list)
      assertEquals((1 to 10).toList, q1.list)

      val q2 = q1 take 5
      println("q2: "+q2.selectStatement)
      println("    "+q2.list)
      assertEquals((1 to 5).toList, q2.list)

      val q3 = q1 drop 5
      println("q3: "+q3.selectStatement)
      println("    "+q3.list)
      assertEquals((6 to 10).toList, q3.list)

      val q4 = q1 drop 5 take 3
      println("q4: "+q4.selectStatement)
      println("    "+q4.list)
      assertEquals((6 to 8).toList, q4.list)

      val q5 = q1 take 5 drop 3
      println("q5: "+q5.selectStatement)
      println("    "+q5.list)
      assertEquals((4 to 5).toList, q5.list)

      val q6 = q1 take 0
      println("q6: "+q6.selectStatement)
      println("    "+q6.list)
      assertEquals(List(), q6.list)
    }
  }
}
