package com.richrobertson.tenure.observability

import cats.effect.kernel.Sync
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.*

private val MaxTimingSamplesPerMetric = 128
private val MaxEventCount = 512

final case class MetricKey(name: String, labels: Map[String, String]) derives CanEqual
object MetricKey:
  given Ordering[MetricKey] with
    override def compare(left: MetricKey, right: MetricKey): Int =
      val byName = left.name.compareTo(right.name)
      if byName != 0 then byName
      else
        val leftLabels = left.labels.toVector.sortBy(_._1)
        val rightLabels = right.labels.toVector.sortBy(_._1)
        leftLabels
          .zip(rightLabels)
          .collectFirst {
            case ((leftKey, leftValue), (rightKey, rightValue)) if leftKey != rightKey => leftKey.compareTo(rightKey)
            case ((leftKey, leftValue), (rightKey, rightValue)) if leftValue != rightValue => leftValue.compareTo(rightValue)
          }
          .getOrElse(leftLabels.size.compare(rightLabels.size))
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

final case class MetricSample[A](metric: MetricKey, value: A) derives CanEqual
object MetricSample:
  given [A: Encoder: Decoder]: Codec.AsObject[MetricSample[A]] = Codec.AsObject.from(
    Decoder.forProduct2("metric", "value")(MetricSample.apply[A]),
    Encoder.forProduct2("metric", "value")((sample: MetricSample[A]) => (sample.metric, sample.value))
  )

final case class ObservabilitySnapshot(
    counters: Vector[MetricSample[Long]],
    gauges: Vector[MetricSample[Long]],
    timingsMillis: Vector[MetricSample[Vector[Long]]],
    events: Vector[LogEvent]
) derives CanEqual
object ObservabilitySnapshot:
  val empty: ObservabilitySnapshot = ObservabilitySnapshot(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
  given Codec[ObservabilitySnapshot] = Codec.from(
    Decoder.forProduct4("counters", "gauges", "timingsMillis", "events")(ObservabilitySnapshot.apply),
    Encoder.forProduct4("counters", "gauges", "timingsMillis", "events")((snapshot: ObservabilitySnapshot) =>
      (snapshot.counters, snapshot.gauges, snapshot.timingsMillis, snapshot.events)
    )
  )

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
    Ref.of[F, InMemoryObservability.State](InMemoryObservability.State.empty).map(new InMemoryObservability[F](_))

object InMemoryObservability:
  private[observability] final case class State(
      counters: Map[MetricKey, Long],
      gauges: Map[MetricKey, Long],
      timingsMillis: Map[MetricKey, Vector[Long]],
      events: Vector[LogEvent]
  )

  private[observability] object State:
    val empty: State = State(Map.empty, Map.empty, Map.empty, Vector.empty)

final class InMemoryObservability[F[_]: Sync](state: Ref[F, InMemoryObservability.State]) extends Observability[F]:
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
      val samples = (snapshot.timingsMillis.getOrElse(key, Vector.empty) :+ millis).takeRight(MaxTimingSamplesPerMetric)
      snapshot.copy(timingsMillis = snapshot.timingsMillis.updated(key, samples))
    }

  override def log(event: LogEvent): F[Unit] =
    state.update(snapshot => snapshot.copy(events = (snapshot.events :+ event).takeRight(MaxEventCount)))

  def snapshot: F[ObservabilitySnapshot] =
    state.get.map { snapshot =>
      ObservabilitySnapshot(
        snapshot.counters.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.gauges.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.timingsMillis.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.events
      )
    }
