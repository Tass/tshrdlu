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
import twitter4j._
import collection.JavaConversions._
import tshrdlu.util.bridge._

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

  def main (args: Array[String]) {
    val system = ActorSystem("TwitterBot")
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
      twitter.updateStatus(update)

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
  }
}
  
case class Filter(about: Set[String], from: Set[String], by: String, include: Boolean)
class Replier extends Actor with ActorLogging {
  import Bot._

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.util.{Success,Failure}
  implicit val timeout = Timeout(10 seconds)

  lazy val random = new scala.util.Random

  def receive = {
    case ReplyToStatus(status) => {
      val text = status.getText
      var replyText = parse(status) match {
        case Some(query) => {
          context.parent ! query
          val about = query.about.mkString(" ")
          "Working on $about."
        }
        case None => "Sorry, I couldn't parse that."
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
      Filter(m.group(1).split(" ").toSet.filterNot(_ == "#"), m.group(3).split(" ").toSet, status.getUser.getScreenName, m.group(2) == "such as")
    }).orElse({
      flippedRegex.findFirstMatchIn(status.getText).map({ m =>
        Filter(m.group(3).split(" ").toSet.filterNot(_ == "#"), m.group(2).split(" ").toSet, status.getUser.getScreenName, m.group(1) == "such as")
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
