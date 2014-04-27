package com.geeksville.dapi

import com.geeksville.dapi.model.Mission
import com.geeksville.flight.Location
import akka.actor.Actor
import akka.actor.ActorLogging
import com.geeksville.akka.EventStream
import com.geeksville.flight.StatusText
import com.geeksville.flight.MsgModeChanged
import akka.actor.ActorRef
import scala.collection.mutable.HashMap
import akka.actor.Terminated
import org.scalatra.atmosphere._
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import com.geeksville.akka.NamedActorClient
import akka.actor.Props
import com.geeksville.akka.MockAkka
import akka.actor.ActorContext
import akka.actor.ActorRefFactory
import com.geeksville.scalatra.AtmosphereTools
import org.json4s.Extraction
import org.mavlink.messages.MAVLinkMessage
import com.geeksville.mavlink.MsgArmChanged
import com.geeksville.mavlink.MsgSystemStatusChanged
import com.github.aselab.activerecord.dsl._
import com.geeksville.dapi.model.Vehicle
import com.geeksville.dapi.model.MissionSummary
import com.geeksville.flight.MsgRcChannelsChanged
import com.geeksville.flight.MsgServoOutputChanged
import com.geeksville.flight.MsgSysStatusChanged
import com.geeksville.util.Throttled

/**
 * This actor is responsible for keeping a model of current and recent flights in its region of space.
 *
 * Initially I'm only making one instance of this actor, but as usage grows we will create numerous instances - where
 * each instance is responsible for a particular region of space (plus one top level supervisor that provides a planet
 * wide summary.
 *
 * It is important that this class collect enough state so that it can quickly serve up API queries from MDS (or similar)
 * without requiring any database operations.  When the server first boots this cache/model is initially empty (though
 * we could seed it by making a utility that does a query on missions and pushes out a bunch of state updates).
 *
 * LiveVehicleActors send messages to this actor, and the REST atmosphere reader stuff listens for publishes from this actor.
 *
 * FIXME - make different atmosphere endpoints for different regions - pair each endpoint with the SpaceSupervisor
 * responsible for that region.  For the time being I just use one region for the whole planet
 */
class SpaceSupervisor extends Actor with ActorLogging {
  import context._

  private val msgLogThrottle = new Throttled(5000)

  private val eventStream = new EventStream

  private implicit val formats = DefaultFormats

  /**
   * The LiveVehicleActors we are monitoring
   */
  private val actorToMission = HashMap[ActorRef, Mission]()

  protected def publishEvent(a: Any) { eventStream.publish(a) }

  // private def senderMission = actorToMission(sender).id
  private def senderVehicle = actorToMission(sender).vehicleId.get

  private def withMission(preferredSender: Option[ActorRef])(cb: Long => Unit) {
    val s = preferredSender.getOrElse(sender)
    actorToMission.get(s).map { m => cb(m.id) }.getOrElse {
      log.warning(s"Ignoring from $s")
    }
  }

  /**
   * FIXME - not sure if I should be publishing directly to atmosphere in this actor, but for now...
   */
  private def updateAtmosphere(typ: String, o: JValue) {
    val route = "/api/v1/mission/live"
    AtmosphereTools.broadcast(route, typ, o)
  }

  private def publishUpdate(typ: String, p: Product = null, preferredSender: Option[ActorRef] = None) {
    withMission(preferredSender) { senderMission =>

      msgLogThrottle.withIgnoreCount { numIgnored: Int =>
        log.debug(s"Published space $typ, $p (and $numIgnored others)")
      }

      val o = SpaceEnvelope(senderMission, Option(p))
      publishEvent(o) // Tell any interested subscribers
      val v = Extraction.decompose(o)
      updateAtmosphere(typ, v)
    }
  }

  override def receive = {

    //
    // Messages from LiveVehicleActors appear below
    //

    case Terminated(a) =>
      log.error(s"Unexpected death of a LiveVehicle, republishing...")
      actorToMission.remove(a).foreach { m =>
        publishUpdate("stop", preferredSender = Some(a))
      }

    case MissionStart(mission) =>
      log.debug(s"Received start of $mission from $sender")
      actorToMission(sender) = mission
      watch(sender)
      publishUpdate("start", SpaceSummary(mission.vehicle, mission.summary))

    case MissionStop(mission) =>
      log.debug(s"Received stop of $mission")
      unwatch(sender)
      publishUpdate("stop")
      actorToMission.remove(sender)

    case l: Location =>
      publishUpdate("loc", l)

    case l: StatusText =>
      publishUpdate("text", l)

    case l: MsgArmChanged =>
      publishUpdate("arm", l)

    case l: MsgModeChanged =>
      publishUpdate("mode", l)

    case l: MsgSystemStatusChanged =>
      publishUpdate("sysstat", l)

    case MsgSysStatusChanged =>
    case MsgRcChannelsChanged =>
    case x: MsgServoOutputChanged =>
    // Silently ignore to prevent logspam BIG FIXME - should not even publish this to us...

    case x: Product =>
      publishUpdate("mystery", x)

    case x: MAVLinkMessage =>
    // Silently ignore to prevent logspam BIG FIXME - should not even publish this to us...
  }
}

object SpaceSupervisor {
  private implicit def context: ActorRefFactory = MockAkka.system
  private val actors = new NamedActorClient("space")

  /**
   * Find the supervisor responsible for a region of space
   *
   * FIXME - add grid identifer param
   */
  def find(name: String = "world") = actors.getOrCreate(name, Props(new SpaceSupervisor))
}