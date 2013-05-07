package tshrdlu.twitter.retweet
import akka.util.Timeout
import scala.concurrent.duration._
import tshrdlu.twitter._
import tshrdlu.util._
import tshrdlu.util.bridge._
import nak.core._
import nak.NakContext._
import nak.data._
import akka.actor._
import twitter4j._
import akka.pattern._

// Actor globals, shouldn't be too evil.
object Actors {
  var mf: ActorRef = null
  var ds: ActorRef = null
  var rt: ActorRef = null
}

object ModelKeys {
  // Uniquely identifies each model. The Option is a username or None
  // if the model is used for user-independent models (like the scala
  // one). The Set is the set of topics used. Will likely crash if the
  // set is empty. Make sure it isn't.
  type ModelKey = Tuple2[Option[String], Set[String]]
}
import ModelKeys.ModelKey

case class Relevant(key: ModelKey, status:Status)
// This Actor handles if a tweet should be retweeted (or replied to
// with @user).
class Retweeter extends Actor with ActorLogging {
  import Actors._
  import Bot._
  // The Option is used to say that it's a global model, so the
  // retweets don't go to a specific person.
  type ModelsClass = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]
  // Topics first, then User.
  val models: ModelsClass = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]()
  val modelFile = new java.io.File("models.dump")
  val config = Settings(context.system)
  val username = new TwitterStreamFactory().getInstance.getScreenName

  // override def preStart {
  //   if (modelFile.exists) {
  //     log.info("loading up " + modelFile.toString)
  //     models ++= scala.util.Marshal.load(io.Source.fromFile(modelFile).map(_.toByte).toArray)
  //   }
  // }

  // override def postStop {
  //   if (models.size > 0) {
  //     log.info("dumping to " + modelFile.toString)
  //     import java.io._
  //     val out = new FileOutputStream(modelFile)
  //     out.write(scala.util.Marshal.dump(models))
  //     out.flush
  //   }
  // }

  // Global here. Somewhat ugly.
  lazy val streamer = new Streamer(context.self)
  def receive = {
    case status: Status => {
      val text = status.getText
      val bot = context.parent
      val tagged = POSTagger(text)
      log.info(s"Got: $text")
      if (!text.contains("@" + username) && status.getUser.getScreenName != username) {
        // This intersects tokens with the keywords for the models. If
        // all of the keywords are found, the tweet is interesting for
        // the model.
        val interestingFor = models.keySet.filter(_.subsetOf(tagged.map(_.token.toLowerCase.filterNot(_ == "#")).toSet))
        interestingFor.foreach({ keywords =>
          models(keywords).foreach ({
            case (userOption, model) =>
              log.info(s"Evaluating $text for $keywords")
              if(relevant(status, model)) {
                mf ! SaveTweet(status.getId, SavedTweet((userOption, keywords), text))
                userOption match {
                  case Some(user) => bot ! Bot.UpdateStatus(new StatusUpdate(s"@$user $text" take 140).inReplyToStatusId(status.getId))
                  case None => bot ! Bot.Retweet(status.getId)
                }
              }
          })
        })
      }
    }
    // Used for RetweetTester
    case Relevant(key, status) => {
      ds ! SaveTweet(status.getId, SavedTweet(key, status.getText))
      sender ! relevant(status, models(key._2)(key._1))
    }
    // A new model should be added to the stream. Expects the model to
    // be trained.
    case AddModel(key, model) => {
      val topic = key._2
      val user = key._1
      log.info(s"Got model about $topic of $user")
      models.get(topic) match {
        case Some(map) => map += (user -> model)
        case None => models += (topic -> scala.collection.mutable.Map(user -> model))
      }
      if (config.SetupStream) {
        val query = new FilterQuery(0, Array[Long](), models.keys.map(_.mkString(" ")).toArray)
        log.info(s"Updating filter stream to $query")
        streamer.stream.filter(query)
      }
      val bot = context.parent
      val topics = topic.mkString(" ")
      user.foreach({ u => bot ! Bot.UpdateStatus(new StatusUpdate(s"@$u ready to tweet about $topics"))})
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

case class AddModel(key: ModelKey, model: FeaturizedClassifier[String, String])
case class RT(ref: ActorRef)
case class UpdateModel(key: ModelKey, tweets: List[String], label: String)
case class Ping()
case class Pong()
// Create new models and passes them on to the retweet actor.
class ModelFactory extends Actor with ActorLogging {
  import nak.liblinear.LiblinearConfig
  import tshrdlu.data.Grab._
  import scala.collection.JavaConverters._
  val negativeExampleFactor = 20
  implicit val timeout = Timeout(1 minute)
  import context._
  import Actors._

  def receive = {
    // Create a new model based on a filter and pass it on to the retweeter.
    case Filter(about, from, by) => {
      // Blocking makes handling rate limits easier.
      val pos = positive(about, from)
      val neg = negative(about, from, pos.size)
      train((Some(by), about), pos, neg)
    }
    // Set the retweeter.
    case RT(ref) => {
      rt = ref
    }
    // A certain tweet with id long has been responded to with positive/negative.
    case ImproveUpon(long: Long, label: String) => {
      (ds ? LoadTweet(long)).mapTo[SavedTweet].foreach(x => self ! UpdateModel(x.key, List.fill(20)(x.text), label))
    }
    // Used by ImproveUpon currently. May be used independently later
    // on if more tweets are fetched for a model.
    case UpdateModel(key, tweets, label) => {
      (ds ? Load(key)).mapTo[Tuple2[List[String], List[String]]].map({
        case (pos, neg) =>
          label match {
            case "positive" => train(key, pos ++ tweets, neg)
            case "negative" => train(key, pos, neg ++ tweets)
          }
      })
    }
    // To wait for models to be trained. Send this message and wait
    // for it to return, you will then know the message queue has been
    // worked through so far.
    case Ping => sender ! Pong
  }

  def train(key: ModelKey, pos: Iterable[String], neg: Iterable[String]) {
    log.info(s"training model on $key")
    ds ! Save(key, (pos.toList, neg.toList))
    rt ! AddModel(key, ScalaModel.train(key._2, pos, neg))
  }

  def positive(about: Iterable[String], from: Iterable[String]): Iterable[String] = {
    from.flatMap(fetch(_, 10)).filter(_.isRetweet).map(_.getText)
  }

  def negative(about: Iterable[String], from: Iterable[String], amount: Int): Iterable[String] = {
    val connected = from.flatMap(friendsOf(_)).map(_.getScreenName).toSet ++ from // add followers?
    val query = new Query()
    query.setCount(100)
    query.setQuery(about.mkString(" "))
    val found = twitter.search(query).getTweets.asScala.map(_.getUser.getScreenName).toSet
    val candidates = found -- connected
    candidates.take(amount/200).flatMap(fetch(_)).map(_.getText)
  }
}

case class Save(key: ModelKey, pos_neg: Tuple2[List[String], List[String]])
case class Load(key: ModelKey)
case class SaveTweet(id: Long, savedTweet: SavedTweet)
case class LoadTweet(id: Long)
case class SavedTweet(key: ModelKey, text: String)
// A Simple n Stupid datastore so models can be retrained. Might be
// replaced with something more sophisticated in the future.
class DataStore extends Actor with ActorLogging {
  import scala.collection._
  // This one I keep for improving so it doesn't need to refetch the status.
  val retweeted = mutable.Map[Long, SavedTweet]()
  // Positive / negative examples used.
  val entries = mutable.Map[ModelKey, Tuple2[List[String], List[String]]]()

  def receive = {
    case Save(key, pos_neg) => entries += (key -> pos_neg)
    case Load(key) => sender ! entries.get(key)
    case SaveTweet(id, savedTweet) => retweeted += (id -> savedTweet)
    case LoadTweet(id) => sender ! retweeted(id)
  }

  val tweetFile = new java.io.File("tweets.dump")
  val entriesFile = new java.io.File("entries.dump")

  // override def preStart {
  //   if (tweetFile.exists) {
  //     log.info("loading up " + tweetFile.toString)
  //     retweeted ++= scala.util.Marshal.load(io.Source.fromFile(tweetFile).map(_.toByte).toArray)
  //   }
  //   if (entriesFile.exists) {
  //     log.info("loading up " + entriesFile.toString)
  //     entries ++= scala.util.Marshal.load(io.Source.fromFile(entriesFile).map(_.toByte).toArray)
  //   }
  // }

  // override def postStop {
  //   import java.io._
  //   if (retweeted.size > 0) {
  //     val out = new FileOutputStream(tweetFile)
  //     out.write(scala.util.Marshal.dump(retweeted))
  //     out.flush
  //   }
  //   if (entries.size > 0) {
  //     val out2 = new FileOutputStream(entriesFile)
  //     out2.write(scala.util.Marshal.dump(entries))
  //     out2.flush
  //   }
  // }

}


// Handles the feature stuff in the models. Splits a tweet into
// tokens, then features. Currently ignores the keyword.
class FeatureCollector(about: Iterable[String]) extends Featurizer[String, String]{
  val feats = List(
    {(parsed: Iterable[Token]) => parsed.map(token => "token=" + token.token.toLowerCase)},
    {(parsed: Iterable[Token]) => parsed.map(token => "tag=" + token.tag)},
    {(parsed: Iterable[Token]) => parsed.map(token => "token+tag=" + token.token.toLowerCase + "+" + token.tag)},
    {(parsed: Iterable[Token]) => parsed.sliding(2).map(list => list.map(_.tag).mkString("bigramTags=", "+", "")).toIterable},
    {(parsed: Iterable[Token]) => parsed.sliding(2).map(list => list.map(_.token.toLowerCase).mkString("bigramTokens=", "+", "")).toIterable}
  )
  val ignore = about.toSet
  def apply(raw: String) = {
    val parsed = POSTagger(raw)
    feats.flatMap(
      _.apply(parsed.filterNot(item => ignore(item.token.toLowerCase))
      ).map(FeatureObservation(_)))
  }
}

import spray.json._
import DefaultJsonProtocol._

// Contains the logic to train the bot model for scala.
object ScalaModel {
  import nak.liblinear.LiblinearConfig
  lazy val neg: Iterable[String] = io.Source.fromURL(getClass.getResource("/retweet/scala-lang-neg")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).map(_._5).toIterable.take(50000)
  lazy val pos: Iterable[String] = io.Source.fromURL(getClass.getResource("/retweet/scala-lang")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).take(neg.size).map(_._5).toIterable.take(50000)

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
