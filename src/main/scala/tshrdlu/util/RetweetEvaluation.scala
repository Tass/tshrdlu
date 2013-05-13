package tshrdlu.util
import spray.json._
import DefaultJsonProtocol._
import tshrdlu.twitter.retweet._
import nak.core._
import nak.data._
import nak.util._

object RetweetEvaluation {
  val jason = readJSON(io.Source.fromFile("jason-data"))
  val etorreborre = readJSON(io.Source.fromURL(getClass.getResource("/retweet/scala-lang")))
  val negative = readJSON(io.Source.fromURL(getClass.getResource("/retweet/scala-lang-neg")))
  // Simulates "tweets about scala like jasonbaldridge"
  val jasonOriginal = jason.filter(_._1 == "Jason Baldridge").head._2
  val neg = negative.take(5).flatMap(_._2.take(jasonOriginal.size/5))
  def main(args: Array[String]) {
    val wrong = basicEval()
    val tenth = wrong.grouped(10).map(_.head).map(List.fill(20)(_)).flatten.map(_.features)
    println("Adding tweets: " + tenth.size)
    val negAddition: Iterable[String] = negative.map(_._2).map(_.drop(200).take(100)).filter(_.size == 100).transpose.flatten.take(tenth.size).map(_._5)
    println(negAddition.size)
    val improvedModel =
      ScalaModel.train(Set("scala"),
                       jasonOriginal.map(_._5) ++ tenth,
                       neg.map(_._5) ++ negAddition)
    val improvedEval = etorreborre.take(20).flatMap(_._2.drop(100).take(100)).map(ex => Example("positive", ex._5)) ++
    negative.drop(5).take(20).flatMap(_._2.drop(100).take(100)).map(ex => Example("negative", ex._5))
    val improvedPredicts = predictions(improvedModel, improvedEval)
    println(ConfusionMatrix(improvedEval.map(_.label).toSeq, improvedPredicts.toSeq, improvedEval.map(_.features).toSeq))
  }

  def basicEval(): Iterable[Example[String, String]] = {
    val model = ScalaModel.train(Set("scala"), jasonOriginal.map(_._5), neg.map(_._5))
    val eval = etorreborre.take(20).flatMap(_._2.take(100)).map(ex => Example("positive", ex._5)) ++
    negative.drop(5).take(20).flatMap(_._2.take(100)).map(ex => Example("negative", ex._5))
    val predicts = predictions(model, eval)
    println(ConfusionMatrix(eval.map(_.label).toSeq, predicts.toSeq, eval.map(_.features).toSeq))
    eval.zip(predicts).filter({
      case (eval, pred) =>
        eval.label != pred && eval.label == "positive"                           
    }).map(_._1)
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
}
