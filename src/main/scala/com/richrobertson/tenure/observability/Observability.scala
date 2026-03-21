package com.richrobertson.tenure.observability

import cats.effect.kernel.Sync
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Codec
import io.circe.generic.semiauto.*

final case class MetricKey(name: String, labels: Map[String, String]) derives CanEqual
object MetricKey:
  given Ordering[MetricKey] = Ordering.by(key => (key.name, key.labels.toList.sortBy(_._1)))
  given Codec[MetricKey] = deriveCodec

final case class LogEvent(
    timestampMillis: Long,
    level: String,
    eventType: String,
    message: String,
    nodeId: Option[String] = None,
    term: Option[Long] = None,
    leaderId: Option[String] = None,
    tenantId: Option[String] = None,
    resourceId: Option[String] = None,
    requestId: Option[String] = None,
    result: Option[String] = None,
    errorCode: Option[String] = None,
    fields: Map[String, String] = Map.empty
) derives CanEqual
object LogEvent:
  given Codec[LogEvent] = deriveCodec

final case class ObservabilitySnapshot(
    counters: Map[MetricKey, Long],
    gauges: Map[MetricKey, Long],
    timingsMillis: Map[MetricKey, Vector[Long]],
    events: Vector[LogEvent]
) derives CanEqual
object ObservabilitySnapshot:
  val empty: ObservabilitySnapshot = ObservabilitySnapshot(Map.empty, Map.empty, Map.empty, Vector.empty)
  given Codec[ObservabilitySnapshot] = deriveCodec

trait Observability[F[_]]:
  def incrementCounter(name: String, labels: Map[String, String] = Map.empty, delta: Long = 1L): F[Unit]
  def setGauge(name: String, value: Long, labels: Map[String, String] = Map.empty): F[Unit]
  def recordTiming(name: String, millis: Long, labels: Map[String, String] = Map.empty): F[Unit]
  def log(event: LogEvent): F[Unit]

object Observability:
  def noop[F[_]: Sync]: Observability[F] = new Observability[F]:
    override def incrementCounter(name: String, labels: Map[String, String], delta: Long): F[Unit] = Sync[F].unit
    override def setGauge(name: String, value: Long, labels: Map[String, String]): F[Unit] = Sync[F].unit
    override def recordTiming(name: String, millis: Long, labels: Map[String, String]): F[Unit] = Sync[F].unit
    override def log(event: LogEvent): F[Unit] = Sync[F].unit

  def inMemory[F[_]: Sync]: F[InMemoryObservability[F]] =
    Ref.of[F, ObservabilitySnapshot](ObservabilitySnapshot.empty).map(new InMemoryObservability[F](_))

final class InMemoryObservability[F[_]: Sync](state: Ref[F, ObservabilitySnapshot]) extends Observability[F]:
  override def incrementCounter(name: String, labels: Map[String, String], delta: Long): F[Unit] =
    state.update { snapshot =>
      val key = MetricKey(name, labels)
      val next = snapshot.counters.updated(key, snapshot.counters.getOrElse(key, 0L) + delta)
      snapshot.copy(counters = next)
    }

  override def setGauge(name: String, value: Long, labels: Map[String, String]): F[Unit] =
    state.update { snapshot =>
      snapshot.copy(gauges = snapshot.gauges.updated(MetricKey(name, labels), value))
    }

  override def recordTiming(name: String, millis: Long, labels: Map[String, String]): F[Unit] =
    state.update { snapshot =>
      val key = MetricKey(name, labels)
      val samples = snapshot.timingsMillis.getOrElse(key, Vector.empty) :+ millis
      snapshot.copy(timingsMillis = snapshot.timingsMillis.updated(key, samples))
    }

  override def log(event: LogEvent): F[Unit] =
    state.update(snapshot => snapshot.copy(events = snapshot.events :+ event))

  def snapshot: F[ObservabilitySnapshot] = state.get
