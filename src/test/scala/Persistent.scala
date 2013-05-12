import org.scalatest._
import tshrdlu.twitter.retweet.PersistentMap

object Pers extends PersistentMap

import Pers._

class PersistentSpec extends FunSuite {
  import scala.collection.mutable._
  test("save a map and load it again") {
    val map = Map[String, Map[Int, Int]]("foo" -> Map(1 -> 2), "bar" -> Map(3 -> 4, 5 -> 6))
    val file = "testfile"
    val loaded = Map[String, Map[Int, Int]]()
    save(map, file)
    load(loaded, file)
    assert(map == loaded)
  }
  test("should also work for empty maps") {
    val file = "testfile"
    val map = Map[String, Map[Int, Int]]()
    save(map, file)
  }
  test("should be able to load the tweets.dump file") {
    import twitter4j._
    import scala.collection._
    import tshrdlu.twitter.retweet._
    import ModelKeys._
    val retweeted = mutable.Map[Long, Tuple2[Status, mutable.Set[ModelKey]]]()
    val tweetFile = "tweets.dump"
    load(retweeted, tweetFile)
  }
}
