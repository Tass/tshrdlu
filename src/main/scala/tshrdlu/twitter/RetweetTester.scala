package tshrdlu.twitter.retweet
import tshrdlu.twitter._
import akka.actor._
import tshrdlu.repl._

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

  def setup {
    val system = ActorSystem("TwitterBot")
    Runtime.getRuntime.addShutdownHook(
      new Thread( new Runnable { def run {system.shutdown; system.awaitTermination}}))
    val bot = system.actorOf(Props[FakeBot], name = "Bot")
    val listener = system.actorOf(Props[DeadLetterListener], name = "DeadLetters")
    system.eventStream.subscribe(listener, classOf[DeadLetter])
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

  def main(args: Array[String]) {
    setup
    Thread.sleep(5000)
    classifyTweet("A tweet about scala")
  }
}
