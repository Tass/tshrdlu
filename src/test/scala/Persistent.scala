import org.scalatest._
import tshrdlu.twitter.retweet.PersistentMap

object Pers extends PersistentMap

import Pers._

class PersistentSpec extends FunSuite {
  import scala.collection.mutable._
  test("save a map and load it again") {
    val map = Map[String, Map[Int, Int]]("foo" -> Map(1 -> 2), "bar" -> Map(3 -> 4, 5 -> 6))
    val file = new java.io.File(System.getProperty("java.io.tmpdir") + "/testfile")
    val loaded = Map[String, Map[Int, Int]]()
    save(map, file)
    load(loaded, file)
    assert(map == loaded)
  }
  test("should also work for empty maps") {
    val file = new java.io.File(System.getProperty("java.io.tmpdir") + "/testfile")
    val map = Map[String, Map[Int, Int]]()
    save(map, file)
  }
}
