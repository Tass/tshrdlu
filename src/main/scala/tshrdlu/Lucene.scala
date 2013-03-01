package tshrdlu

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TopScoreDocCollector
import org.apache.lucene.store.Directory
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import twitter4j.Status
import scala.collection.JavaConversions._

object Lucene {
  val index = new RAMDirectory()
  val analyzer = new StandardAnalyzer(Version.LUCENE_40)
  val config = new IndexWriterConfig(Version.LUCENE_40, analyzer)
  val writer = new IndexWriter(index, config)
  val parser = new QueryParser(Version.LUCENE_40, "text", analyzer)

  var database = Map[String, String]()

  def write(tweets: Iterable[Status]) {
    val pairs = tweets.map((tweet => (tweet.getId.toString(), tweet.getText)))
    database = database ++ pairs.toMap
    val documents = asJavaIterable(pairs.map({pair =>
      val doc = new Document()
      doc.add(new StringField("id", pair._1, Field.Store.YES))
      doc.add(new TextField("text", pair._2, Field.Store.NO))
      doc
    }))
    writer.addDocuments(documents)
    writer.commit()
  }

  def read(query: String): String = {
    val reader = DirectoryReader.open(index)
    val searcher = new IndexSearcher(reader)
    val collector = TopScoreDocCollector.create(1, true)
    searcher.search(parser.parse(query), collector)
    database(searcher.doc(collector.topDocs().scoreDocs(0).doc).get("id"))
  }
}