/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.kafka

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import akka.projection.Projection
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.scaladsl.SourceProvider
import akka.projection.slick.SlickHandler
import akka.projection.slick.SlickProjection
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import slick.basic.DatabaseConfig
import slick.dbio.DBIO
import slick.jdbc.H2Profile

//#imports
import akka.projection.kafka.KafkaSourceProvider
import akka.projection.MergeableOffset
import akka.kafka.ConsumerSettings
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer

//#imports

object KafkaDocExample {

  //#handler
  type Word = String
  type Count = Int

  class WordCountHandler(projectionId: ProjectionId) extends SlickHandler[ConsumerRecord[String, String]] {
    private val logger = LoggerFactory.getLogger(getClass)
    private var state: Map[Word, Count] = Map.empty

    override def process(envelope: ConsumerRecord[String, String]): DBIO[Done] = {
      val word = envelope.value
      val newCount = state.getOrElse(word, 0) + 1
      logger.info(
        "{} consumed from topic/partition {}/{}. Word count for {} is {}",
        projectionId,
        envelope.topic,
        envelope.partition,
        word,
        newCount)
      state = state.updated(word, newCount)
      DBIO.successful(Done)
    }
  }
  //#handler

  val config: Config = ConfigFactory.parseString("""
    akka.projection.slick = {

      profile = "slick.jdbc.H2Profile$"

      db = {
       url = "jdbc:h2:mem:test1"
       driver = org.h2.Driver
       connectionPool = disabled
       keepAliveConnection = true
      }
    }
    """)

  implicit lazy val system = ActorSystem[Guardian.Command](Guardian(), "Example", config)

  object IllustrateSourceProvider {

    //#sourceProvider
    val bootstrapServers = "localhost:9092"
    val groupId = "group-wordcount"
    val topicName = "words"
    val consumerSettings =
      ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
        .withBootstrapServers(bootstrapServers)
        .withGroupId(groupId)
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val sourceProvider: SourceProvider[MergeableOffset[Long], ConsumerRecord[String, String]] =
      KafkaSourceProvider(system, consumerSettings, Set(topicName))
    //#sourceProvider
  }

  object IllustrateExactlyOnce {
    import IllustrateSourceProvider._

    //#exactlyOnce
    val dbConfig: DatabaseConfig[H2Profile] = DatabaseConfig.forConfig("akka.projection.slick", system.settings.config)
    val projectionId = ProjectionId("WordCount", "wordcount-1")
    val projection =
      SlickProjection.exactlyOnce(projectionId, sourceProvider, dbConfig, handler = new WordCountHandler(projectionId))
    //#exactlyOnce

    projection.createOffsetTableIfNotExists()
  }

  def projection(n: Int): Projection[ConsumerRecord[String, String]] = {
    import IllustrateSourceProvider.sourceProvider
    import IllustrateExactlyOnce.dbConfig

    val projectionId = ProjectionId("WordCount", s"wordcount-$n")
    SlickProjection.exactlyOnce(projectionId, sourceProvider, dbConfig, handler = new WordCountHandler(projectionId))
  }

  object Guardian {
    sealed trait Command
    def apply(): Behavior[Command] = {
      Behaviors.setup { context =>
        context.spawn(ProjectionBehavior(projection(1)), "wordcount-1")
        context.spawn(ProjectionBehavior(projection(2)), "wordcount-2")
        context.spawn(ProjectionBehavior(projection(3)), "wordcount-3")

        Behaviors.empty
      }

    }
  }

  /**
   * {{{
   * bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --replication-factor 1 --partitions 3 --topic words
   * bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic words
   * bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic words --from-beginning
   *
   * sbt "examples/test:runMain docs.kafka.KafkaDocExample"
   *
   * bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic words
   *
   * }}}
   */
  def main(args: Array[String]): Unit = {
    system
  }

}
