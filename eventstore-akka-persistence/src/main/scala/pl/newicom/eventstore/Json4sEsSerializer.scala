package pl.newicom.eventstore

import java.nio.ByteBuffer
import java.nio.charset.Charset

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.persistence.eventstore.EventStoreSerializer
import akka.persistence.eventstore.snapshot.EventStoreSnapshotStore.SnapshotEvent
import akka.persistence.eventstore.snapshot.EventStoreSnapshotStore.SnapshotEvent.Snapshot
import akka.persistence.{PersistentRepr, SnapshotMetadata}
import akka.serialization.{Serialization, SerializationExtension}
import akka.util.ByteString
import eventstore.{Content, ContentType, Event, EventData}
import org.joda.time.DateTime
import org.json4s.Extraction.decompose
import org.json4s.JsonAST.{JField, JObject, JString}
import org.json4s._
import org.json4s.ext.{JodaTimeSerializers, UUIDSerializer}
import org.json4s.native.Serialization.{read, write}
import org.json4s.reflect.TypeInfo
import pl.newicom.dddd.aggregate.EntityId
import pl.newicom.dddd.delivery.protocol.Processed
import pl.newicom.dddd.delivery.protocol.alod.{Processed => AlodProcessed}
import pl.newicom.dddd.messaging.MetaData
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.eventstore.Json4sEsSerializer._

class Json4sEsSerializer(system: ExtendedActorSystem) extends EventStoreSerializer {

  def identifier = Identifier

  val defaultFormats: Formats = DefaultFormats + ActorRefSerializer + new SnapshotSerializer(system) ++ JodaTimeSerializers.all + UUIDSerializer +
    new FullTypeHints(List(
      classOf[MetaData],
      classOf[Processed],
      classOf[AlodProcessed],
      classOf[PersistentRepr]))

  implicit val formats: Formats = defaultFormats

  def includeManifest = true

  override def fromBinary(bytes: Array[Byte], manifestOpt: Option[Class[_]]): AnyRef = {
    implicit val manifest = manifestOpt match {
      case Some(x) => Manifest.classType(x)
      case None    => Manifest.AnyRef
    }
    try {
      read(new String(bytes, UTF8))
    } catch {
      case th: Throwable =>
        th.printStackTrace()
        throw th;
    }
  }

  override def toBinary(o: AnyRef) = {
    write(o).getBytes(UTF8)
  }


  override def toPayloadAndMetadata(e: AnyRef) = e match {
    case em: EventMessage => (em.event,
      em.withMetaData(Map("id" -> em.id, "timestamp" -> em.timestamp)).metadata)
    case _ => super.toPayloadAndMetadata(e)
  }

  override def fromPayloadAndMetadata(payload: AnyRef, maybeMetadata: Option[AnyRef]): AnyRef = {
    if (maybeMetadata.isDefined) {
      val metadata = maybeMetadata.get.asInstanceOf[MetaData]
      val id: EntityId = metadata.get("id")
      val timestamp = DateTime.parse(metadata.get("timestamp"))
      new EventMessage(payload, id, timestamp).withMetaData(Some(metadata))
    } else {
      new EventMessage(payload)
    }
  }

  override def toEvent(x: AnyRef) = x match {
    case x: SnapshotEvent => EventData(
      eventType = x.getClass.getName,
      data = Content(ByteString(toBinary(x)), ContentType.Json))

    case _ => sys.error(s"Cannot serialize $x, SnapshotEvent expected")
  }

  override def fromEvent(event: Event, manifest: Class[_]) = {
    val clazz = Class.forName(event.data.eventType)
    val result = fromBinary(event.data.data.value.toArray, clazz)
    if (manifest.isInstance(result)) result
    else sys.error(s"Cannot deserialize event as $manifest, event: $event")
  }

  def contentType = ContentType.Json

  object ActorRefSerializer extends CustomSerializer[ActorRef](format => (
    {
      case JString(s) => system.provider.resolveActorRef(s)
    },
    {
      case x: ActorRef => JString(Serialization.serializedActorPath(x))
    }
    ))

}

object Json4sEsSerializer {
  val UTF8 = Charset.forName("UTF-8")
  val Identifier: Int = ByteBuffer.wrap("json4s".getBytes(UTF8)).getInt

  case class SnapshotSerializer(sys: ExtendedActorSystem) extends Serializer[Snapshot] {
    val Clazz = classOf[Snapshot]

    import akka.serialization.{Serialization => SysSerialization}
    lazy val serialization: SysSerialization = SerializationExtension(sys)

    def deserialize(implicit format: Formats) = {
      case (TypeInfo(Clazz, _), JObject(List(JField("dataClass", JString(dataClass)), JField("data", JString(x)), JField("metadata", metadata)))) =>
        import Base64._
        val data = serialization.deserialize(x.toByteArray, Class.forName(dataClass)).get
        val metaData = metadata.extract[SnapshotMetadata]
        Snapshot(data, metaData)
    }

    def serializeAnyRef(data: AnyRef)(implicit format: Formats): String = {
      import Base64._
      serialization.serialize(data).get.toBase64
    }

    def serialize(implicit format: Formats) = {
      case Snapshot(data, metadata) =>
        val dataSerialized: String = data match {
          case data: AnyRef => serializeAnyRef(data)
          case _ => data.toString
        }
        JObject("jsonClass" -> JString(Clazz.getName), "dataClass" -> JString(data.getClass.getName), "data" -> JString(dataSerialized), "metadata" -> decompose(metadata))
    }
  }

}