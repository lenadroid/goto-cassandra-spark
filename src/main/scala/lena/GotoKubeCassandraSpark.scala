package lena

import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.SparkContext._
import org.apache.spark.sql.functions._

import org.apache.commons.io._
import scala.collection.mutable.WrappedArray
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer, RegexTokenizer, CountVectorizer, StopWordsRemover}

import com.datastax.driver.core._
import collection.JavaConverters._

object GotoKubeCassandraSpark {
    def main(args: Array[String]) {
      val keyword = args(0)

      val cassandraAddress = "cassandra"
      val keyspace= "showrecommender"
      val table="recommendations"

      val conf = new SparkConf().setAppName("episoderecommender")
      // Create a Scala Spark Context.
      val sc = new SparkContext(conf)

      println("Cassandra data using Spark on k8s!")

      val cluster = new Cluster
          .Builder()
          .addContactPoints(cassandraAddress)
          .withPort(9042)
          .build()
      val session = cluster.connect()

      val query = session.prepare("SELECT * FROM showrecommender.recommendations WHERE keyword = ? ORDER BY rating ASC")
      val episodesInDb = session.execute(query.bind(keyword)).all()

      println ("=== RECOMMENDED EPISODES TO WATCH WITH '" + keyword + "' ===")

      if (episodesInDb.size() == 0) {
        // If the recommendation information is not present in the database
        // start a Spark job to calculate frequency of mentions for the keyword

        // RUN SPARK JOB

        println("Rating Game of Thrones episodes by '" + args(0) + "'")

        val inputFiles = "file:///opt/spark/data/moviescript/*"

        // For `toDF` function to work, we need to import implicits
        val sqlContext= new org.apache.spark.sql.SQLContext(sc)
        import sqlContext.implicits._

        // Reading scripts from the input directory
        val sentenceData = sc.wholeTextFiles(inputFiles).toDF("label", "sentence")
        // Splitting text into words and forming an list of them
        val regexTokenizer = new RegexTokenizer().setInputCol("sentence").setOutputCol("words").setPattern("[^\\w']+")
        val wordsData = regexTokenizer.transform(sentenceData)

        val stopWordsRemover = new StopWordsRemover().setInputCol("words").setOutputCol("cleanWords")
        val cleanWords = stopWordsRemover.transform(wordsData)

        // fit a CountVectorizerModel from the corpus.
        val cv = new CountVectorizer().setInputCol("cleanWords").setOutputCol("rawFeatures").setVocabSize(10000)
        val cv_model = cv.fit(cleanWords)
        val featurizedData = cv_model.transform(cleanWords)


        val rankValues = featurizedData.select("rawFeatures").rdd.map { case x : org.apache.spark.sql.Row => x(0).asInstanceOf[SparseVector] }.map(x => x.values)
        val rankIndices = featurizedData.select("rawFeatures").rdd.map { case x : org.apache.spark.sql.Row => x(0).asInstanceOf[SparseVector] }.map(x => x.indices)
        val indicesAndValues = rankIndices.zip(rankValues)
        val episodes = featurizedData.select("label").rdd.map { case x : org.apache.spark.sql.Row => x(0).asInstanceOf[String]}
        val episodesAndRanks = indicesAndValues.zip(episodes)

        // Getting the index of the target word in the vocabulary
        val indexOfTargetWord = cv_model.vocabulary.indexOf(keyword)

        // Checks if array of indicies contains index of target word
        // If yes - finds the corresponding word frequency score accross all episodes
        // Returns the episode name where the word appears most
        val targetEpisodes =
          episodesAndRanks
            .map{ case x : Tuple2[Tuple2[Array[Int],Array[Double]],String] => (x._1._1, x._1._2, x._2)}
            .filter{ case (indices, values, title) => indices.contains(indexOfTargetWord)}
            .sortBy({ case (indices, values, title) => values(indices.indexOf(indexOfTargetWord))}, false)
            .map{ case (indices, values, title) => title}
            .collect()

        // IF ABSENT -> WRITE THAT IT IS NOT PRESENT
        if (targetEpisodes.size == 0) {
          println("NONE")
        } else {
          // IF PRESENT -> WRITE DATA INTO DATABASE AND DISPLAY
          val prepared = session.prepare("insert into showrecommender.recommendations (keyword, rating, episode) values (?, ?, ?)")

          targetEpisodes.zipWithIndex.foreach {
              case(episode, rank) =>
                session.execute(prepared.bind(keyword, new java.lang.Integer(rank), episode));
                println ((rank + 1) + " -> " + episode)
          }
        }
      } else {
        // Displaying recommendation information from the database
        val output = episodesInDb.asScala.map(r => (r.getInt("rating") + 1) + " -> " + r.getString("episode"))
        output.foreach{ println }
      }

      session.close()
      cluster.close()
      sc.stop()
    }
}
