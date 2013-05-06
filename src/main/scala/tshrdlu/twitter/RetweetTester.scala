package tshrdlu.twitter.retweet
import com.typesafe.config.ConfigFactory
import java.io.EOFException
import nak.util.ConfusionMatrix
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import tshrdlu.twitter._
import akka.actor._
import akka.pattern._
import tshrdlu.repl._
import akka.util.Timeout
import scala.concurrent.duration._

object RetweetTester {
  class FakeBot extends Actor with ActorLogging {
    override def preStart {
      Actors.rt = context.actorOf(Props[Retweeter], name = "Retweet")
      Actors.mf = context.actorOf(Props[ModelFactory], name = "ModelFactory")
      Actors.ds = context.actorOf(Props[DataStore], name = "DataStore")
    }

    def receive = {
      case x: Any => println(x)
    }
  }

  class DeadLetterListener extends Actor {
    def receive = {
      case d: DeadLetter ⇒ println(d)
    }
  }

  import Actors._
  import akka.actor.{ Actor, DeadLetter, Props }

  def setup = {
    val config = ConfigFactory.load()
    val system = ActorSystem("TwitterBot", config.getConfig("repl").withFallback(config))
    Runtime.getRuntime.addShutdownHook(
      new Thread( new Runnable { def run {system.shutdown; system.awaitTermination}}))

    val bot = system.actorOf(Props[FakeBot], name = "Bot")
    val listener = system.actorOf(Props[DeadLetterListener], name = "DeadLetters")
    system.eventStream.subscribe(listener, classOf[DeadLetter])
    system
  }

  def addModel(about: Set[String], from: Set[String]) {
    mf ! Filter(about, from, "thedoctor")
  }

  def addModel(about: Set[String], from: Set[String], by: String) {
    mf ! Filter(about, from, by)
  }

  var id: Long = 0
  def classifyTweet(text: String) {
    rt ! FakeStatus(id, text, "thedoctor")
    id += 1
  }

  import twitter4j._
  def main(args: Array[String]) {
    import scala.collection.JavaConversions._
    import scala.collection.JavaConverters._
    implicit val timeout = Timeout(1 minute)

    val system = setup
    Thread.sleep(10000)                 // Ugly, but works.
    val twitter = (new TwitterFactory).getInstance()
    Actors.mf ! Filter(Set("scala"), Set("etorreborre", "jasonbaldridge"), "reactormonk")
    val query = new Query("scala")
    query.count(30)
    val result = twitter.search(query)
    val statuses = result.getTweets.asScala
    val iterations = statuses.grouped(10)
    // Nice n hacky way to wait until it's trained.
    Await.result(Actors.mf ? Ping, 1 minute)
    iterations.foreach({
      case iteration =>
        val improve = evaluate(iteration.toList)
        improve.foreach({ imp => 
          Actors.mf ! ImproveUpon(imp._1.getId, imp._2)
          // Nice n hacky way to wait until it's retrained.
          Await.result(Actors.mf ? Ping, 1 minute)
        })
    })
  }

  def evaluate(tweets: List[Status]): Option[Tuple2[Status, String]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = Timeout(1 minute)

    val modelAnswer = Future.sequence(tweets.map({ case tweet =>
      (Actors.rt ? Relevant((Some("reactormonk"), Set("scala")), tweet)).mapTo[Boolean].map({
        case true => "positive"
        case false => "negative"
      })
    }))

    println("Please annotate the tweets (10). Answer with 'p' or 'n' and then hit enter.")
    val responses = tweets.map({ tweet =>
      println(tweet.getText)
      print("Positive or negative? [p/n] ")
      var response: Option[String] = None
      while(response.isEmpty) {
        response = readline
        response match {
          case Some(x) =>
            response = x match {
            case "n" => Some("negative")
            case "p" => Some("positive")
            case _ => None
          }
          case None => // ignore
        }
        println("Please answer with n or p")
      }
      response.get
    })

    val results = Await.result(modelAnswer, 1 minute)
    val zipped = results.zip(tweets.zip(responses))
    println(ConfusionMatrix(responses, results, tweets.map(_.getText)))

    val wrong = zipped
    // Response is the gold standard
      .map({case (result, response) => (result, response._1, result != response._2)})
      .filter(_._3)
      .map(x => (x._2, x._1))
    wrong.size match {
      case 0 => {
        println("Nothing classified wrongly, can't improve.")
        None
      }
      case 1 => {
        println("Feeding back the only wrong one")
        Some(wrong(0))
      }
      case _ =>
        {
          println("Choose one of the tweets to be fed back into the system.")
          wrong.zipWithIndex.foreach({
            case ((tweet, result), index) =>
              val text = tweet.getText
              println(s"$index: classified as $result $text")
          })
          var index: Option[Int] = None
          while(index.isEmpty) {
            index = readline.map(getValue(_)).flatten
            index match {
              case Some(x) => x
              case None => println("Please select a tweet")
            }
          }
          Some(wrong(index.get))
        }
    }
  }

  def readline: Option[String] =
    try { 
      Console.readLine() match { 
        case null => None 
        case x => Some(x) 
      }
    } catch { 
        case _: EOFException => None 
    } 

  def getValue(s: String): Option[Int] = s match {
    case "inf" => Some(Integer.MAX_VALUE)
    case Int(x) => Some(x)
    case _ => None
  }
}

object Int {
  def unapply(s : String) : Option[Int] = try {
    Some(s.toInt)
  } catch {
    case _ : java.lang.NumberFormatException => None
  }
}
