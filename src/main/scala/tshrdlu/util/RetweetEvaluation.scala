package tshrdlu.util
import spray.json._
import DefaultJsonProtocol._
import tshrdlu.twitter.retweet._
import nak.core._
import nak.data._
import nak.util._

object RetweetEvaluation {
  lazy val jason = readJSON(io.Source.fromFile("jason-data"))
  lazy val etorreborre = readJSON(io.Source.fromURL(getClass.getResource("/retweet/scala-lang")))
  lazy val negative = readJSON(io.Source.fromURL(getClass.getResource("/retweet/scala-lang-neg")))
  // Simulates "tweets about scala like jasonbaldridge"
  lazy val jasonOriginal = jason.filter(_._1 == "Jason Baldridge").head._2
  lazy val basicModel =
    ScalaModel.train(Set("scala"),
                     etorreborre("etorreborre").map(_._5),
                     negative.mapValues(_.take(200)).values.flatten.take(etorreborre("etorreborre").size).map(_._5))
  def main(args: Array[String]) {
    println("Running baseline")
    baseline
    // println("Running improvement")
    // improvement
  }

  def baseline() {
    testData.foreach(data =>
      iteration(basicModel, data)
    )
  }

  def improvement() {
    var sets = testData
    for {
      x <- (1 to 10)
    } yield {
      println(s"run #$x")
      var model: FeaturizedClassifier[String, String] = null
      var posPool = etorreborre("etorreborre").map(_._5).toIterator
      var pos = posPool.take(4000).toList      // 6k are available.
      var neg = negative.mapValues(_.take(200)).values.flatten.map(_._5).take(etorreborre("etorreborre").size).toList
      sets.foreach({set =>
        model = ScalaModel.train(Set("scala"), pos, neg)
        val wrong = iteration(model, set)
        pos ++= posPool.take(wrong.size)
        neg ++= wrong
      })
      sets = sets.last +: sets.dropRight(1)
    }
  }

  def readJSON(file: io.Source) = {
    file.getLines.map(_.asJson.convertTo[Tuple5[Long, String, Long, String, String]]).toList.groupBy(_._2)
  }

  def predictions(model: FeaturizedClassifier[String, String], eval: Iterable[Example[String, String]]) = {
    eval.map({example =>
      model.evalRaw(example.features)(model.indexOfLabel("positive")) > 0.6
    }).map(_ match {
      case true => "positive"
      case false => "negative"
    })
  }

  def iteration(model: FeaturizedClassifier[String, String], eval: Iterable[Example[String, String]]) = {
    val preds = predictions(model, eval)
    val wrong = eval.zip(preds).filter({
      case (eval, pred) =>
        eval.label != pred && eval.label == "negative"
    }).map(_._1)
    println(ConfusionMatrix(eval.map(_.label).toSeq, preds.toSeq, eval.map(_.features).toSeq).detailedOutput)
    wrong.grouped(20).map(_.head).map(List.fill(20)(_)).flatten.map(_.features)
  }

  val testData = io.Source.fromFile("twitter-data").getLines.map(_.asJson.convertTo[Tuple6[Long, String, Long, String, String, String]]).toList
    .map(x => (x._5, x._6 match { case "n" => "negative" case "y" => "positive" } ))
    .groupBy(_._2)
    .mapValues({list => (1 to 10).map(x => list.slice((x-1)*(list.size/10), x*(list.size/10))) .toSeq})
    .map({case (label, bucket) => bucket.map(_.map({case (string, label) => Example(label, string)}))}).transpose.reduce(_++_)
    .grouped(2).map(_.flatten).toList
}
