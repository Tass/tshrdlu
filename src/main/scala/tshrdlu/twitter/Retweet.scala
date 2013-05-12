package tshrdlu.twitter.retweet
import akka.util.Timeout
import scala.concurrent.Future
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
import java.io._

// Actor globals, shouldn't be too evil.
object Actors {
  var mf: ActorRef = null
  var ds: ActorRef = null
  var rt: ActorRef = null
  var squeezer: ActorRef = null
  var blocker: ActorRef = null
}

object Labels {
  implicit def toString(label: Label): String = label.toString
  implicit def toLabel(string: String): Label = string match {
    case "positive" => Positive()
    case "negative" => Negative()
  }
  sealed trait Label {
  }
  case class Positive extends Label {
    override def toString = "positive"
  }
  case class Negative extends Label {
    override def toString = "negative"
  }
}
import Labels._

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
class Retweeter extends Actor with ActorLogging with PersistentMap {
  import Actors._
  import Bot._
  import context.dispatcher
  // The Option is used to say that it's a global model, so the
  // retweets don't go to a specific person.
  type ModelsClass = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]
  // Topics first, then User.
  val models: ModelsClass = scala.collection.mutable.Map[Set[String], scala.collection.mutable.Map[Option[String], FeaturizedClassifier[String, String]]]()
  val config = Settings(context.system)
  val username = new TwitterStreamFactory().getInstance.getScreenName
  implicit val timeout = Timeout(1 minute)

  // Global here. Somewhat ugly.
  lazy val streamer = new Streamer(context.self)
  def receive = {
    case status: Status => {
      val text = status.getText
      val bot = context.parent
      val tagged = POSTagger(text)
      if (!text.contains("@" + username) && status.getUser.getScreenName != username) {
        log.info(s"Got: $text")
        // This intersects tokens with the keywords for the models. If
        // all of the keywords are found, the tweet is interesting for
        // the model.
        val interestingFor = models.keySet.filter(_.forall(text.toLowerCase.contains(_)))
        interestingFor.foreach({ keywords =>
          models(keywords).foreach ({
            case (userOption, model) =>
              log.info(s"Evaluating $text for $keywords")
              if(relevant(status, model)) {
                val key = (userOption, keywords)
                userOption match {
                  case Some(user) => bot ! Bot.UpdateStatus(new StatusUpdate(s"@$user $text" take 140).inReplyToStatusId(status.getId))
                  case None =>
                    val response = (if (text.startsWith("RT")) {text} else {"RT " + text}) take 140
                    val newStatus = (bot ? Bot.UpdateStatus(new StatusUpdate(response).inReplyToStatusId(status.getId))).mapTo[Status]
                    newStatus.foreach(stat => ds ! SaveTweet(stat.getId, key, status))
                }
              }
          })
        })
      }
    }
    // Used for RetweetTester
    case Relevant(key, status) => {
      ds ! SaveTweet(status.getId, key, status)
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
      user.foreach({ u => bot ! Bot.UpdateStatus(new StatusUpdate(s"@$u Ready to tweet about $topics."))})
    }
  }

  def relevant(status: Status, model: FeaturizedClassifier[String, String]): Boolean = {
    val result = model.evalRaw(status.getText)
    val positive = result(model.indexOfLabel("positive"))
    val negative = result(model.indexOfLabel("negative"))
    log.info(s"positive: $positive, negative: $negative")
    positive > 0.6
  }
}

case class AddModel(key: ModelKey, model: FeaturizedClassifier[String, String])
case class RT(ref: ActorRef)
case class UpdateModel(key: ModelKey, tweets: List[Status], label: Label)
case class Ping()
case class Pong()
case class SqueezeRateLimit
case class Filter(about: Set[String], from: Set[String], by: String)
case class ImproveUpon(tweetId: Long, label: Label)
case class CreateModel(key: ModelKey, pos: List[Status], neg: List[Status])

// Create new models and passes them on to the retweet actor.
class ModelFactory extends Actor with ActorLogging with PersistentMap {
  import nak.liblinear.LiblinearConfig
  import tshrdlu.data.Grab._
  import scala.collection.JavaConverters._
  val negativeExampleFactor = 20
  implicit val timeout = Timeout(1 minute)
  val rateLimitWindow = 15 minutes
  import context._
  import Actors._
  var squeezer = system.scheduler.schedule(10 minutes, 15 minutes, self, SqueezeRateLimit)
  val actors = scala.collection.mutable.Map[ModelKey, ActorRef]()

  override def preStart {
    loadActors
  }

  def receive = {
    // Create a new model based on a filter and pass it on to the retweeter.
    case Filter(about, from, by) => {
      // TODO create new model actor, or update existing one
    }
    // A certain tweet with id long has been responded to with positive/negative.
    case ImproveUpon(long: Long, label: Label) => {
      log.info(s"Improving upon $long")
      (ds ? LoadTweet(long)).mapTo[Tuple2[Status, Set[ModelKey]]].foreach({
        case (tweet, forModels) => forModels.foreach(key => actors(key) ! ImproveTweet(tweet, label))
      })
    }
    // To wait for models to be trained. Send this message and wait
    // for it to return, you will then know the message queue has been
    // worked through so far. TODO Doesn't work anymore with workers.
    case Ping => sender ! Pong
    case UpdateModel(key, tweets, label) => actorFor(key) ! UpdateModel(key, tweets, label)
    case am: AddModel => rt ! am
    case CreateModel(key, pos, neg) =>
      val actor = actorFor(key)
      actor ! CreateModel(key, pos, neg)
  }

  def rescheduleSqueezer(seconds: Int) {
    squeezer.cancel()
    squeezer = system.scheduler.schedule(seconds seconds, 15 minutes, self, SqueezeRateLimit)
  }

  // Creates if neccesary and returns the actor corresponding to the
  // key.
  def actorFor(key: ModelKey): ActorRef = {
    actors.getOrElseUpdate(key, context.actorOf(Props(new Model(key)), name = key._1.toString + "$" + key._2.mkString("+")))
  }

  // Uses directory reflection to create the actors for the models.
  // The pattern is data/Option[String]/Set[String]. Since the
  // usernames/topics are restricted to \w, I use toString on the
  // Option[String] and regex it back in. The Set is serialized with
  // .mkString("+") and parsed back in by .split("+")
  def loadActors() {
    log.info("loading models...")
    val some = """Some\((\w+)\)""".r
    Option(dataPath.list).foreach({path =>
      val userDirs = path.map(new File(dataPath, _)).filter(_.isDirectory)
      for {
        userPath <- userDirs
      } yield {
        val user = userPath.getName match {
          case "None" => None
          case some(x) => Some(x)
        }
        val modelDirs = userPath.list.map(new File(userPath, _)).filter(_.isDirectory)
        modelDirs.foreach({modelPath =>
          val model = Set() ++ modelPath.getName.split('+').toList
          val key: ModelKey = (user, model)
          log.info(s"loading model $key")
          actors.getOrElseUpdate(key, context.actorOf(Props(new Model(key, true)), name = key._1.toString + "$" + key._2.mkString("+")))
        })
      }
    })
  }
}

sealed trait Considered
case class Accepted extends Considered
case class Rejected extends Considered
case class Unchecked extends Considered

case class CreateMoreFetches

case class Train

case class ImproveTweet(tweet: Status, label: Label)

// Each model gets its own actor that knows about the model. In case
// of updates, it notifies the parent so it can update the cache.
class Model(key: ModelKey, loadFromDisk: Boolean = false) extends Actor with ActorLogging with PersistentMap {
  implicit val timeout = Timeout(1 hour)
  import Actors._
  import context.dispatcher
  import Bot._
  import scala.concurrent._
  import scala.collection._

  val config = Settings(context.system)
  val pos = mutable.Buffer[Status]()
  val neg = mutable.Buffer[Status]()
  var model: FeaturizedClassifier[String, String] = _
  var trainingSchedule: Option[Cancellable] = None
  val consideredUsers = mutable.Map[Tuple2[Label, Considered], Future[List[Long]]]().withDefaultValue(Future(List[Long]()))
  val modelFile = new File(keyToPath(key), "model").getPath
  val userFile = new File(keyToPath(key), "users").getPath
  val posFile = new File(keyToPath(key), "pos").getPath
  val negFile = new File(keyToPath(key), "neg").getPath

  override def preStart {
    if (loadFromDisk) {
      loadBuffer(pos, posFile)
      loadBuffer(neg, negFile)
      val userTmp = mutable.Map[Tuple2[Label, Considered], List[Long]]()
      load(userTmp, userFile)
      deserializeConsidered(userTmp)
      loadModel(modelFile) match {
        case Some(classifier) => model = classifier; notifyTrained
        case None => self ! Train
      }
    }
  }

  override def postStop {
    save(pos, posFile)
    save(neg, negFile)
    save(model, modelFile)
    save(serializeConsidered, userFile)
  }

  def serializeConsidered(): mutable.Map[Tuple2[Label, Considered], List[Long]] = {
    val result = mutable.Map[Tuple2[Label, Considered], List[Long]]()
    result ++= consideredUsers.mapValues(Await.result(_, 20 seconds))
    result
  }

  def deserializeConsidered(serialized: mutable.Map[Tuple2[Label, Considered], List[Long]]) {
    serialized.foreach({
      case(key, value) =>
        consideredUsers += (key -> Future(value))
    })
  }

  def receive = {
    // Create a new model based on a filter and pass it on to the retweeter.
    case Filter(about, users, by) => {
      consideredUsers += ((Positive(), Accepted()) -> (blocker ? FetchIds(users.toList)).mapTo[List[Long]])
      val positiveTweets = positive()
      val negativeUsers = negativeUserIDs(about, users)
      val negativeTweets = negativeUsers.flatMap(users =>
        Future.sequence(users.map(fetchBlocking(_, 1)))
      )
      val labeledUsers = for {
        users <- negativeUsers
        tweets <- negativeTweets
      } yield {
        val counts = tweets.map(_.count({ tweet =>
          val text = tweet.getText.toLowerCase()
          about.forall(text.toLowerCase.contains(_))
        }))
        val over = counts.filter(_ > 1).size match {
          case size if size > 10 => 1
          case _ => 0
        }
        val labels = counts.map(_ match {
          case x if x > over => Accepted
          case _ => Rejected
        })
        neg ++= tweets.zip(labels).filter({case (_, label) => label == Accepted}).map(_._1).flatten
        users.zip(labels)
      }
      List(Accepted(), Rejected(), Unchecked()).foreach(label =>
        consideredUsers += ((Negative(), label) -> labeledUsers.map(_.filter(_._2 == label).map(_._1)))
      )
      for {positive <- positiveTweets} yield {pos ++= positive}
    }

    case UpdateModel(_, tweets, label) => {
      label match {
        case Positive() => pos ++= tweets
        case Negative() => neg ++= tweets
      }
      trainSoon
    }
    case ImproveTweet(tweet, label) => self ! UpdateModel(key, List.fill(config.multiplyImproveBy)(tweet), label)
    case Train => train
    case Ping => sender ! Pong
    case CreateMoreFetches => createMoreFetches()
    case CreateModel(_, positive, negative) =>    // Doesn't got the usual train path.
      log.info(s"training a bot model on $key")
      pos ++= positive
      neg ++= negative
      model = ScalaModel.train(key._2, pos.map(_.getText), neg.map(_.getText))
      notifyTrained
  }

  def trainSoon() {
    trainingSchedule.foreach(_.cancel)
    trainingSchedule = Some(context.system.scheduler.scheduleOnce(30 seconds, self, Train))
  }

  def train() {
    log.info(s"training model on $key")
    trainingSchedule = None
    model = ScalaModel.train(key._2, pos.map(_.getText), neg.map(_.getText))
    notifyTrained
  }

  def positive(): Future[List[Status]] = {
    consideredUsers((Positive(), Accepted())).flatMap(ids =>
      Future.sequence(ids.map(fetchBlocking(_, 10)).toList).map(_.flatten)
    )
  }

  def negativeUserIDs(about: Iterable[String], users: Iterable[String]): Future[List[Long]] = {
    val friendIds = Future.sequence(users.map(user => (blocker ? FetchFriends(user)).mapTo[List[Long]])).map(_.flatten)
    val connected = Future.sequence(List(friendIds, consideredUsers((Positive(), Accepted())))).map(_.reduce(_ ++ _).toList)
    val found = (blocker ? FetchViaQuery(about)).mapTo[List[Status]]
    for {
      f <- found
      c <- connected
    } yield (f.map(_.getUser.getId).toSet -- c.toSet).toList
  }

  def createMoreFetches() {
    ???
  }

  def notifyTrained() {
    context.parent ! AddModel(key, model)
  }

  def fetchBlocking(userId: Long, amount: Int, minId: Long = 1l, maxId: Long = Long.MaxValue): Future[List[Status]] = {
    (blocker ? FetchTweets(userId, amount, minId, maxId)).mapTo[List[Status]]
  }
}

case class SaveTweet(retweetId: Long, key: ModelKey, savedTweet: Status)
case class LoadTweet(id: Long)
// Not used. Twitter usually takes care of that.
case class AlreadyTweeted(key: ModelKey, text: String)
// Stores tweets so the bot knows which tweet came from which model.
class DataStore extends Actor with ActorLogging with PersistentMap {
  import scala.collection._
  // This one I keep for improving so it doesn't need to refetch the status.
  val retweeted = mutable.Map[Long, Tuple2[Status, mutable.Set[ModelKey]]]()
  val tweetedText = mutable.Map[ModelKey, Set[String]]()

  def receive = {
    case SaveTweet(retweetId, key, savedTweet) =>
      val mapValue = retweeted.getOrElseUpdate(retweetId, ((savedTweet, mutable.Set())))
      mapValue._2 += key
      val newSet: Set[String] = tweetedText.getOrElse(key, Set[String]()) + savedTweet.getText()
      tweetedText += (key -> newSet)
    case LoadTweet(id) =>
      val tuple = retweeted(id)
      sender ! (tuple._1, tuple._2.toSet)
    case AlreadyTweeted(key, text) => sender ! tweetedText(key)(text)
  }

  val tweetFile = "tweets.dump"
  val retweetedText = "retweeted.dump"

  override def preStart {
    load(retweeted, tweetFile)
    retweeted.foreach({
      case (_, (status, setOfKeys)) =>
        setOfKeys.foreach(key =>
          tweetedText += (key -> {tweetedText.getOrElse(key, Set[String]()) + status.getText()})
        )
    })
  }

  override def postStop {
    save(retweeted, tweetFile)
  }
}

// Handles the feature stuff in the models. Splits a tweet into
// tokens, then features. Currently ignores the keyword.
class FeatureCollector(about: Set[String]) extends Featurizer[String, String] {
  def apply(raw: String) = {
    val parsed = POSTagger(raw).filterNot(item => about(item.token.toLowerCase))
    val features = parsed.map(token => "token=" + token.token.toLowerCase) ++
    parsed.map(token => "tag=" + token.tag) ++
    parsed.map(token => "token+tag=" + token.token.toLowerCase + "+" + token.tag) ++
    parsed.sliding(2).map(list => list.map(_.tag).mkString("bigramTags=", "+", "")) ++
    parsed.sliding(2).map(list => list.map(_.token.toLowerCase).mkString("bigramTokens=", "+", ""))
    features.map(FeatureObservation(_))
  }
}

import spray.json._
import DefaultJsonProtocol._

// Contains the logic to train the bot model for scala.
object ScalaModel {
  import nak.liblinear.LiblinearConfig
  val numberOfTweets = 10000            // Increase heap space if you want more.
  lazy val neg: List[Status] = fakeStatuses(io.Source.fromURL(getClass.getResource("/retweet/scala-lang-neg")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).take(10000))
  lazy val pos: List[Status] = fakeStatuses(io.Source.fromURL(getClass.getResource("/retweet/scala-lang")).getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).take(neg.size))

  def fakeStatuses(iter: Iterator[Tuple5[Long, String, Long, String, String]]): List[Status] = {
    import tshrdlu.repl._
    iter.map({tweet =>
      val tweetId = tweet._1
      val screenName = tweet._2
      val time = tweet._3
      val retweet = tweet._4 == "true"
      val text = tweet._5
      new FakeStatus(tweetId, text, new FakeUser(0, null, screenName))
    }).toList
  }

  def train(about: Set[String], pos: Iterable[String], neg: Iterable[String]): FeaturizedClassifier[String, String]  = {
    val train = List((pos, "positive"), (neg, "negative")).flatMap({
      case (collection, label) =>
        collection.map(item => Example(label, item))
    })
    val config = LiblinearConfig(cost=0.7)
    trainClassifier(config, featurizer(about), train)
  }

  def featurizer(about: Iterable[String]): Featurizer[String, String] = {
    new FeatureCollector(about.toSet)
  }

  def main(args: Array[String]) {
    // Add the bot model for scala
    val system = RetweetTester.setup("commandLine")
    Thread.sleep(5000)                 // Ugly, but works.
    Actors.mf ! CreateModel((None, Set("scala")), pos.toList, neg.toList)
  }
}

trait PersistentMap {
  import scala.collection.mutable._
  import java.io._
  val dataPath = new File("data")
  def save[T](map: T, to: String) {
    val toPath = new File(dataPath, to)
    new File(toPath.getParent).mkdirs
	val oos = new ObjectOutputStream(new FileOutputStream(toPath))
    oos.writeObject(map)
    oos.close()
  }

  def load[T, U](map: Map[T, U], from: String) {
    val fromPath = new File(dataPath, from)
    if(fromPath.exists) {
      val ois = new ObjectInputStream(new FileInputStream(fromPath))
      ois.readObject match {
        case mappy: Map[T, U] => map ++= mappy
        case null => // ignore
      }
    }
  }

  def loadBuffer[T](buffer: Buffer[T], from: String) {
    val fromPath = new File(dataPath, from)
    if(fromPath.exists) {
      val ois = new ObjectInputStream(new FileInputStream(fromPath))
      ois.readObject match {
        case buff: Buffer[T] => buffer ++= buff
        case null => // ignore
      }
    }
  }

  def keyToPath(key: ModelKey): String = {
    new File(key._1.toString, key._2.mkString("-")).getPath
  }

  def mkdir(key: ModelKey) {
    new File(dataPath, key._1.toString).mkdirs
  }

  def loadModel[T, U](from: String): Option[FeaturizedClassifier[String, String]] = {
    val fromPath = new File(dataPath, from)
    if(fromPath.exists) {
      val ois = new ObjectInputStream(new FileInputStream(fromPath))
      ois.readObject match {
        case classifier: FeaturizedClassifier[String, String] => Some(classifier)
        case _ => None
      }
    } else {None}
  }
}
