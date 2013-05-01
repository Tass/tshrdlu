package tshrdlu.twitter.retweet
import tshrdlu.twitter._
import tshrdlu.util._
import tshrdlu.util.bridge._
import nak.core._
import nak.NakContext._
import nak.data._
import akka.actor._
import twitter4j._

class Retweeter extends Actor with ActorLogging {
  import Bot._
  val models = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]()

  val streamer = new Streamer(context.self)
  def receive = {
    case status: Status => {
      val text = status.getText
      val bot = context.parent
      val tagged = POSTagger(text)
      log.info(s"Got: $tagged")
      val interestingFor = models.keySet.filter(_.subsetOf(tagged.map(_.token.toLowerCase.filterNot(_ == "#")).toSet))
      interestingFor.foreach({ keywords =>
        models(keywords).foreach ({
          case (userOption, model) =>
            log.info(s"Evaluating $text for $keywords")
            if(relevant(status, model)) {
              userOption match {
                case Some(user) => bot ! Bot.UpdateStatus(new StatusUpdate(s"@$user $text" take 140).inReplyToStatusId(status.getId))
                case None => bot ! Bot.Retweet(status.getId)
              }
            }
        })
      })
    }
    case AddModel(topic, user, model) => {
      log.info(s"Got model about $topic of $user")
      models.get(topic) match {
        case Some(map) => map += (user -> model)
        case None => models += (topic -> scala.collection.mutable.Map(user -> model))
      }
      println(models)
      val query = new FilterQuery(0, Array[Long](), models.keys.map(_.mkString(" ")).toArray)
      log.info(s"Updating filter stream to $query")
      streamer.stream.filter(query)
    }
  }

  def relevant(status: Status, model: FeaturizedClassifier[String, String]): Boolean = {
    val result = model.evalRaw(status.getText)
    val positive = result(model.indexOfLabel("positive"))
    val negative = result(model.indexOfLabel("negative"))
    log.info(s"positive: $positive, negative: $negative")
    positive > 0.8
  }
}

case class AddModel(topic: Set[String], user: Option[String], model: FeaturizedClassifier[String, String])
case class BotModel(topic: Set[String], model: FeaturizedClassifier[String, String])
case class RT(ref: ActorRef)
class ModelFactory extends Actor {
  import nak.liblinear.LiblinearConfig
  import tshrdlu.data.Grab._
  import scala.collection.JavaConverters._
  var rt: ActorRef = context.parent

  override def preStart = {
    self ! BotModel(Set("scala"), ScalaModel.classifier)
  }

  def receive = {
    case Filter(about, from, by, include) => {
      // Blocking makes handling rate limits easier.
      val pos = positive(about, from)
      val neg = negative(about, from, pos.size)
      rt ! AddModel(about, Some(by), ScalaModel.train(about, pos, neg))
    }
    case BotModel(about, model) => {
      rt ! AddModel(about, None, model)
    }
    case RT(ref) => {
      rt = ref
    }
  }

  def positive(about: Iterable[String], from: Iterable[String]): Iterable[String] = {
    from.flatMap(fetch(_, 10)).filter(_.isRetweet).map(_.getText)
  }

  def negative(about: Iterable[String], from: Iterable[String], amount: Int): Iterable[String] = {
    val connected = from.flatMap(friendsOf(_)).map(_.getScreenName).toSet // add followers?
    val query = new Query()
    query.setCount(100)
    query.setQuery(about.mkString(" "))
    val found = twitter.search(query).getTweets.asScala.map(_.getUser.getScreenName).toSet
    val candidates = found -- connected
    candidates.take(amount/200).flatMap(fetch(_)).map(_.getText)
  }
}


trait Feature {
  def apply(parsed: Iterable[Token]): Iterable[String]
}

class FeatureCollector(about: Iterable[String]) extends Featurizer[String, String]{
  val feats = List(Tokens, Tags, TokensnTags, TagBigrams, TokenBigrams)
  val ignore = about.toSet
  def apply(raw: String) = {
    val parsed = POSTagger(raw)
    feats.flatMap(
      _.apply(parsed.filterNot(item => ignore(item.token.toLowerCase))
      ).map(FeatureObservation(_)))
  }
}
object Tokens extends Feature {
  def apply(parsed: Iterable[Token]): Iterable[String] = parsed.map(token => "token=" + token.token.toLowerCase)
}

object Tags extends Feature {
  def apply(parsed: Iterable[Token]): Iterable[String] = parsed.map(token => "tag=" + token.tag)
}

object TokensnTags extends Feature {
  def apply(parsed: Iterable[Token]): Iterable[String] = parsed.map(token => "token+tag=" + token.token.toLowerCase + "+" + token.tag)
}

object TagBigrams extends Feature {
  def apply(parsed: Iterable[Token]): Iterable[String] = parsed.sliding(2).map(list => list.map(_.tag).mkString("bigramTags=", "+", "")).toIterable
}

object TokenBigrams extends Feature {
  def apply(parsed: Iterable[Token]): Iterable[String] = parsed.sliding(2).map(list => list.map(_.token.toLowerCase).mkString("bigramTokens=", "+", "")).toIterable
}

import spray.json._
import DefaultJsonProtocol._

object ScalaModel {
  import nak.liblinear.LiblinearConfig
  lazy val neg: Iterable[String] = io.Source.fromURL(getClass.getResource("/retweet/scala-lang-neg")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).map(_._5).toIterable.take(10000)
  lazy val pos: Iterable[String] = io.Source.fromURL(getClass.getResource("/retweet/scala-lang")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).take(neg.size).map(_._5).toIterable.take(10000)

  val classifier = train(Set("scala"), pos, neg)

  // val file = "/retweet/scala-lang-classifier"
  // lazy val classifier = loadClassifier[FeaturizedClassifier[String,String]](file)

  // def main(args: Array[String]) {
  //   val to = new FileOutputStream(getClass.getResourceAsStream(file))
  //   saveClassifier(train(Set("scala"), pos, neg), to)
  // }

  def train(about: Set[String], pos: Iterable[String], neg: Iterable[String]): FeaturizedClassifier[String, String]  = {
    val train = List((pos, "positive"), (neg, "negative")).flatMap({
      case (collection, label) =>
        collection.map(item => Example(label, item))
    })
    val config = LiblinearConfig(cost=0.7)
    trainClassifier(config, featurizer(about), train)
  }

  def featurizer(about: Iterable[String]): Featurizer[String, String] = {
    new FeatureCollector(about)
  }
}
