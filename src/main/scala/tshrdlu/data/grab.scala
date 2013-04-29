package tshrdlu.data
import twitter4j._
import scala.collection.JavaConversions._
import java.io._
import spray.json._
import DefaultJsonProtocol._

class State
object Candidate extends State
object Processing extends State
object Rejected extends State
object Accepted extends State

object Grab {
  val states = Map[String, State]("candidate" -> Candidate, "processing" -> Processing, "rejected" -> Rejected, "accepted" -> Accepted)
  val statenames = states.map(_.swap)
  val twitter = new TwitterFactory().getInstance
  val usersFile = "twitter-users"
  val dataFile = "twitter-data"
  val grabFriends = false
  def main(args: Array[String]) {
    val from = io.Source.fromString(dataFile)
    val users = parseUsers.foldLeft(scala.collection.mutable.Map[String, State]())(_ + _)
    println(users)
    users.filter(_._2 == Processing).foreach({
      user => ???
    })

    users.filter(_._2 == Candidate).foreach({user => 
      val name = user._1
      users += (name -> Processing)
      print("testing " + name + " ")
      try {
        val peek = fetch(name)
        if (relevant(peek)) {
          println("relevant")
          peek.foreach(dumpStatus(_))
          fetch(name, 15, peek.last.getId).foreach(dumpStatus(_))
          users += (name -> Accepted)
          if (grabFriends) {
            println("grabbing friends...")
            val following = friendsOf(name)
            following.filter((u => users.keySet(u.getScreenName)))
            following.iterator.foreach({n => users += (n.getScreenName -> Candidate)})
          }
        } else {
          println("irrelevant")
          users += (name -> Rejected)
        }
      } catch {
        case e: TwitterException => {
          println(e)
          users += (name -> Rejected)
        }
      }
      dumpUsers(users.toMap)
    })
  }

  def friendsOf(name: String): Iterable[User] = {
    var cursor: Long = -1
    val friends = scala.collection.mutable.Buffer[User]()
    var ids: PagableResponseList[User] = null
    try {
      do {
        ids = twitter.getFriendsList(name, cursor)
        friends.appendAll(ids.iterator())
        cursor = ids.getNextCursor()
      } while ((cursor != 0) && (ids.getRateLimitStatus.getRemaining != 0))
    } catch {
      case e: TwitterException => friends.toList
    }
    friends.toList
  }

  def dumpUsers(users: Map[String, State]) {
    val file = new PrintWriter(new File(usersFile))
    users.foreach(user => file.print(List[String](user._1, statenames(user._2)).mkString("", "\t", "\n")))
    file.flush()
  }

  def parseUsers(): Map[String, State] = {
    val file = io.Source.fromFile(usersFile)
    val format = """(\w+)\s+(candidate|processing|rejected|accepted)""".r
    file.getLines.flatMap({ line =>
      val groups = format.findFirstMatchIn(line).map(_.subgroups)
      groups.map({g => (g(0), states(g(1)))})
    }).toMap
  }

  val to = new FileWriter(new File(dataFile), true)
  def dumpStatus(status: Status) {
    to.write(Tuple5(status.getId, status.getUser.getName, status.getCreatedAt.getTime, status.isRetweet.toString, status.getText).toJson.compactPrint)
    to.write("\n")
    to.flush()
  }

  def fetch(user: String, amount: Int = 1, maxId: Long = Long.MaxValue): Iterable[Status] = {
    (1 to amount).foldRight((Iterable[Status](), maxId))({case (n, (iter, maxId)) =>
      val response = twitter.getUserTimeline(user, if(maxId == Long.MaxValue) {new Paging(1,200)} else {new Paging(1, 200, 1, maxId)})
      if (response.getRateLimitStatus.getRemaining == 0) {
        println("waiting.... " + response.getRateLimitStatus.getSecondsUntilReset)
        Thread.sleep((response.getRateLimitStatus.getSecondsUntilReset + 10)*1000)
      }
      (iter ++ response.iterator.toIterable, if(response.size == 0) { 1 } else {response.get(response.size - 1).getId})
    })._1
  }

  def relevant(statuses: Iterable[Status]): Boolean = {
    statuses.map({ status => 
      val text = status.getText
      text match {
        case text if text.contains("scala") => 1
        case text if text.contains("akka") => 2
        case _ => 0
      }
    }).foldLeft(0)(_ + _) > 5
  }
}
