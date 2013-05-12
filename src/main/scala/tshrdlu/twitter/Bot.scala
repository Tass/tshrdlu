package tshrdlu.twitter

/**
 * Copyright 2013 Jason Baldridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import akka.actor._
import com.typesafe.config.ConfigFactory
import twitter4j._
import collection.JavaConversions._
import collection.JavaConverters._
import tshrdlu.util.bridge._
import scala.concurrent.duration._

/**
 * An object to define the message types that the actors in the bot use for
 * communication.
 *
 * Also provides the main method for starting up the bot. No configuration
 * currently supported.
 */
object Bot {
  
  object Sample
  object Start
  object Shutdown
  case class MonitorUserStream(listen: Boolean)
  case class RegisterReplier(replier: ActorRef)
  case class ReplierByName(replier: String)
  case class ReplyToStatus(status: Status)
  case class SearchTwitter(query: Query)
  case class UpdateStatus(update: StatusUpdate)
  case class Retweet(id: Long)
  case class FetchTweets(userId: Long, pages: Int, minId: Long, maxId: Long) // one page is 200 tweets
  case class FetchFriends(user: String) // 5000 a piece. Should be enough. List[Long]
  case class FetchIds(users: List[String])          // returns List[Long]
  case class FetchViaQuery(about: Iterable[String]) // returns List[Status]

  def main (args: Array[String]) {
    val config = ConfigFactory.load()
    val system = ActorSystem("TwitterBot", config.getConfig("twitter"))
    val bot = system.actorOf(Props[Bot], name = "Bot")
    bot ! Start
  }

}

/**
 * The main actor for a Bot, which basically performance the actions that a person
 * might do as an active Twitter user.
 *
 * The Bot monitors the user stream and dispatches events to the
 * appropriate actors that have been registered with it. Currently only
 * attends to updates that are addressed to the user account.
 */
class Bot extends Actor with ActorLogging {
  import Bot._
  import tshrdlu.twitter.LocationResolver
  import tshrdlu.twitter.retweet._

  val username = new TwitterStreamFactory().getInstance.getScreenName
  val streamer = new Streamer(context.self)

  val twitter = new TwitterFactory().getInstance
  val replier = context.actorOf(Props[Replier], name = "Replier")
  val retweeter = context.actorOf(Props[Retweeter], name = "Retweet")
  val modelfactory = context.actorOf(Props[ModelFactory], name = "ModelFactory")
  val datastore = context.actorOf(Props[DataStore], name = "DataStore")
  Actors.mf = modelfactory
  Actors.rt = retweeter
  Actors.ds = datastore

  override def preStart {
    modelfactory ! RT(retweeter)
  }

  def receive = {
    case Start => streamer.stream.user

    case Shutdown => streamer.stream.shutdown

    case SearchTwitter(query) => 
      val tweets: Seq[Status] = twitter.search(query).getTweets.toSeq
      sender ! tweets
      
    case UpdateStatus(update) => 
      log.info("Posting update: " + update.getStatus)
      try {
        val status = twitter.updateStatus(update)
        sender ! status
      } catch {
        case e: Exception =>
          sender ! scala.concurrent.Promise.failed(e)
          log.warning(e.toString)
          // And ignore it.
      }

    case status: Status =>
      log.info("New status: " + status.getText)
      val replyName = status.getInReplyToScreenName
      if (replyName == username) {
        log.info("Replying to: " + status.getText)
        replier ! ReplyToStatus(status)
      } else {
        retweeter ! status
      }

    case Retweet(id) =>
      twitter.retweetStatus(id)
    case filter: Filter =>
      modelfactory ! filter
    case improve: ImproveUpon =>
      modelfactory ! improve
  }
}

class Replier extends Actor with ActorLogging {
  import Bot._

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.util.{Success,Failure}
  import tshrdlu.twitter.retweet.Labels._
  import tshrdlu.twitter.retweet._
  implicit val timeout = Timeout(10 seconds)

  lazy val random = new scala.util.Random
  val no = """(?i).*(?:no[!.]?|bad bot!?)""".r

  def receive = {
    case ReplyToStatus(status) => {
      val text = status.getText
      val replyText = parse(status) match {
        case Some(filter) => {
          // TODO: Add validation of the usernames
          context.parent ! filter
          val about = filter.about.mkString(" ")
          s"Working on $about."
        }
        case None => text match {
          case no() =>
            status.getInReplyToStatusId match {
              case -1 =>
                "Please reply to the tweet in question so I can improve."
              case statusId =>
                context.parent ! tshrdlu.twitter.retweet.ImproveUpon(statusId, Negative())
                "Sorry, I'll not make that mistake again."
            }
          case _ => "Sorry, I couldn't parse that. Try `tweets about scala like etorreborre jasonbaldridge`."
        }
      }

      val replyName = status.getUser.getScreenName
      val reply = "@" + replyName + " " + replyText
      context.parent ! UpdateStatus(new StatusUpdate(reply).inReplyToStatusId(status.getId))
    }
  }

  val regex = """(?:.* tweets )?(?:about (\w+)) (such as|like) (.*)""".r
  val flippedRegex = """(?:.* tweets )?(such as|like) (.*) (?:about (\w+))""".r

  def parse(status: Status): Option[Filter] = {
    regex.findFirstMatchIn(status.getText).map({ m =>
      Filter(m.group(1).split(" ").toSet.filterNot(_ == "#"), m.group(3).split(" ").toSet, status.getUser.getScreenName) // m.group(2) == "such as")
    }).orElse({
      flippedRegex.findFirstMatchIn(status.getText).map({ m =>
        Filter(m.group(3).split(" ").toSet.filterNot(_ == "#"), m.group(2).split(" ").toSet, status.getUser.getScreenName) // m.group(1) == "such as")
      })
    })
  }
}

object TwitterRegex {

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z_0-9]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z_0-9]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[A-Za-z]+\s))+(.*)$""".r   

  def stripLeadMention(text: String) = text match {
    case StripLeadMentionRE(withoutMention) => withoutMention
    case x => x
  }

}

class Fetcher extends Actor with ActorLogging {
  import Bot._
  import context.dispatcher
  val twitter = new TwitterFactory().getInstance
  var fetchtweets: ActorRef = _

  override def preStart {
    fetchtweets = context.actorOf(Props[FetchTweets], name ="tweets")
  }

  def receive = {
    case fetch: FetchTweets => fetchtweets forward fetch
    case FetchFriends(user) =>
    case FetchViaQuery(about) => 
      // This is not a choke point so far.
      val query = new Query()
      query.setCount(100)
      query.setQuery(about.mkString(" "))
      twitter.search(query).getTweets.asScala.map(_.getUser.getScreenName).toSet
  }

  class FetchTweets extends Actor with ActorLogging with Block {
    def receive = {
      // TODO: Also asnyc tweet fetch
      case FetchTweets(user, pages, minId, maxId) =>
        sender ! (1 to pages).foldRight((Iterable[Status](), maxId))({
          case (n, (iter, maxId)) =>
            val response = twitter.getUserTimeline(user, if(maxId == Long.MaxValue) {new Paging(1,200)} else {new Paging(1, 200, 1, maxId)})
            limit = response.getRateLimitStatus.getRemaining
            maybeSleep(response.getRateLimitStatus.getSecondsUntilReset)
            (iter ++ response.iterator.toIterable, if(response.size == 0) { 1 } else {response.get(response.size - 1).getId})
        })._1
    }
  }

  class FetchFriends extends Actor with ActorLogging with Block {
    def receive = {
      // TODO also async
      case FetchFriends(user) =>
        var cursor: Long = -1
        val friends = scala.collection.mutable.Buffer[Long]()
        var ids: IDs = null
        sender ! (try {
          do {
            ids = twitter.getFriendsIDs(user, cursor)
            limit = ids.getRateLimitStatus.getRemaining
            maybeSleep(ids.getRateLimitStatus.getSecondsUntilReset)
            friends.appendAll(ids.getIDs().toList)
            cursor = ids.getNextCursor()
          } while (cursor != 0)
        } catch {
          case e: TwitterException => friends.toList
        } finally {
          friends.toList
        })
    }
  }

  trait Block {
    var limit = 1
    def maybeSleep(seconds: Int) {
      if (limit == 0) {
        println("waiting.... " + seconds)
        Thread.sleep((seconds + 10)*1000)
        limit = 1                       // Should have reset.
      }
    }
  }
}
