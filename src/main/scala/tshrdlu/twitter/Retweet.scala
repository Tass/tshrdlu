package tshrdlu.twitter.retweet
import tshrdlu.twitter._
import tshrdlu.util._
import nak.core._
import nak.NakContext._
import nak.data._
import akka.actor._
import twitter4j._

class RT extends Actor {
  val models = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]()

  def receive = {
    case status: Status => {
      val bot = context.parent
      val text = status.getText
      val tagged = POSTagger(text)
      val interestingFor = models.keySet.filter(tagged.map(_.token).toSet.contains(_))
      interestingFor.foreach({ keywords =>
        models(keywords).foreach ({
          case (userOption, model) =>
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
      models.get(topic) match {
        case Some(map) => map += (user -> model)
        case None => models += (topic -> scala.collection.mutable.Map(user -> model))
      }
    }
  }

  def relevant(status: Status, model: FeaturizedClassifier[String, String]): Boolean = {
    val result = model.evalRaw(status.getText)
    ???
  }
}

case class AddModel(topic: Set[String], user: Option[String], model: FeaturizedClassifier[String, String])
class ModelFactory extends Actor {
  import nak.liblinear.LiblinearConfig
  import tshrdlu.data.Grab._
  import scala.collection.JavaConverters._
  val cost = 0.7
  var rt: ActorRef = null

  override def preStart = {
    rt = context.parent
  }

  def receive = {
    case Filter(about, from, by, include) => {
      // Blocking makes handling rate limits easier.
      val pos = positive(about, from)
      val neg = negative(about, from, pos.size)
      val train = List((pos, "positive"), (neg, "negative")).flatMap({
        case (collection, label) =>
          collection.map(item => Example(label, item))
      })
      val config = LiblinearConfig(cost=cost)
      rt ! AddModel(about, Some(by), trainClassifier(config, featurizer(about), train))
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

  def featurizer(about: Iterable[String]): Featurizer[String, String] = {
    new FeatureCollector(about)
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
      _.apply(parsed.filter(item => ignore(item.token))
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
