/**
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.projection.grpc.service

import java.time.Instant

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.NoOffset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.TimestampOffset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.EventsBySliceQuery
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.projection.grpc.proto.Event
import akka.projection.grpc.proto.EventReplicationService
import akka.projection.grpc.proto.InitReq
import akka.projection.grpc.proto.Offset
import akka.projection.grpc.proto.PersistenceIdSeqNr
import akka.projection.grpc.proto.StreamIn
import akka.projection.grpc.proto.StreamOut
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.google.protobuf.ByteString
import com.google.protobuf.any.{ Any => ScalaPbAny }
import com.google.protobuf.timestamp.Timestamp
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scalapb.GeneratedMessage

object EventReplicationServiceImpl {
  val log: Logger =
    LoggerFactory.getLogger(classOf[EventReplicationServiceImpl])
}

class EventReplicationServiceImpl(system: ActorSystem[_])
    extends EventReplicationService {
  import EventReplicationServiceImpl.log

  // FIXME config
  private val readJournalPluginId = R2dbcReadJournal.Identifier

  private val eventsBySlicesQuery =
    PersistenceQuery(system)
      .readJournalFor[EventsBySliceQuery](readJournalPluginId)

  private val serialization = SerializationExtension(system)

  override def replicateEvents(
      in: Source[StreamIn, NotUsed]): Source[StreamOut, NotUsed] = {
    in.prefixAndTail(1).flatMapConcat {
      case (Seq(StreamIn(StreamIn.Message.Init(init), _)), tail) =>
        tail.via(runReplication(init, tail))
      case (Seq(), _) =>
        // if error during recovery in proxy the stream will be completed before init
        log.warn("Event replication stream closed before init.")
        Source.empty[StreamOut]
      case (Seq(StreamIn(other, _)), _) =>
        throw new IllegalArgumentException(
          "Expected init message for event replication stream, " +
          s"but received [${other.getClass.getName}]")
    }
  }

  private def runReplication(init: InitReq, nextReq: Source[StreamIn, NotUsed])
      : Flow[StreamIn, StreamOut, NotUsed] = {
    val entityType = init.entityType
    val offset = init.offset match {
      case None => NoOffset
      case Some(o) =>
        val timestamp =
          o.timestamp.map(_.asJavaInstant).getOrElse(Instant.EPOCH)
        val seen = o.seen.map { case PersistenceIdSeqNr(pid, seqNr, _) =>
          pid -> seqNr
        }.toMap
        TimestampOffset(timestamp, seen)
    }

    log.info(
      "Starting event replication stream [{}], slices [{} - {}], offset [{}]",
      entityType,
      init.sliceMin,
      init.sliceMax,
      offset)

    val events: Source[EventEnvelope[Any], NotUsed] =
      eventsBySlicesQuery
        .eventsBySlices[Any](entityType, init.sliceMin, init.sliceMax, offset)

    val eventsStreamOut =
      events
        .filterNot(
          _.eventOption.isEmpty
        ) // FIXME backtracking events not handled yet
        .map { env =>
          val protoOffset = env.offset match {
            case TimestampOffset(timestamp, _, seen) =>
              val protoTimestamp = Timestamp(timestamp)
              val protoSeen = seen.iterator.map { case (pid, seqNr) =>
                PersistenceIdSeqNr(pid, seqNr)
              }.toSeq
              Offset(Some(protoTimestamp), protoSeen)
            case other =>
              throw new IllegalArgumentException(
                s"Unexpected offset type [$other]")
          }

          // FIXME remove too verbose logging here
          log.debug(
            "Emitting event from [{}] with seqNr [{}], offset [{}]",
            env.persistenceId,
            env.sequenceNr,
            env.offset)

          val event = env.event match {
            case scalaPbAny: ScalaPbAny => scalaPbAny
            case msg: GeneratedMessage =>
              ScalaPbAny(
                "type.googleapis.com/" + msg.companion.scalaDescriptor.fullName,
                msg.toByteString)
            case other =>
              // FIXME this is not final solution for serialization
              val bytes =
                serialization.serialize(other.asInstanceOf[AnyRef]).get
              ScalaPbAny(
                "type.googleapis.com/" + other.getClass.getName,
                ByteString.copyFrom(bytes))
          }

          StreamOut(
            StreamOut.Message.Event(
              Event(
                env.persistenceId,
                env.sequenceNr,
                env.slice,
                Some(protoOffset),
                Some(event))))
        }

    // FIXME nextReq not handled yet
    Flow.fromSinkAndSource(Sink.ignore, eventsStreamOut)
  }

}
