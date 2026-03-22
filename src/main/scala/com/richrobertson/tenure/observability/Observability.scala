/**
 * Lightweight in-process observability primitives.
 *
 * Tenure keeps observability intentionally small and dependency-light in v1: counters, gauges, timing
 * samples, and structured log events. This package is used both for diagnostics in the running daemon and
 * for test/demo assertions.
 */
package com.richrobertson.tenure.observability

import cats.effect.kernel.Sync
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.*

private val MaxTimingSamplesPerMetric = 128
private val MaxEventCount = 512

/** Uniquely identifies one metric stream by name plus labels. */
final case class MetricKey(name: String, labels: Map[String, String]) derives CanEqual

/** Ordering and codecs for [[MetricKey]]. */
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

/** Structured event record emitted by the service, Raft, and testkit layers. */
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

/** Circe codecs for [[LogEvent]]. */
object LogEvent:
  given Codec[LogEvent] = deriveCodec

/** Metric sample wrapper used in snapshots and debug output. */
final case class MetricSample[A](metric: MetricKey, value: A) derives CanEqual

/** Circe codecs for [[MetricSample]]. */
object MetricSample:
  given [A: Encoder: Decoder]: Codec.AsObject[MetricSample[A]] = Codec.AsObject.from(
    Decoder.forProduct2("metric", "value")(MetricSample.apply[A]),
    Encoder.forProduct2("metric", "value")((sample: MetricSample[A]) => (sample.metric, sample.value))
  )

/**
 * Complete in-memory observability export.
 *
 * This is the payload returned by the debug route and the value used by tests that want to inspect emitted
 * counters, gauges, timings, and events without an external telemetry backend.
 */
final case class ObservabilitySnapshot(
    counters: Vector[MetricSample[Long]],
    gauges: Vector[MetricSample[Long]],
    timingsMillis: Vector[MetricSample[Vector[Long]]],
    events: Vector[LogEvent]
) derives CanEqual

/** Constructors and codecs for [[ObservabilitySnapshot]]. */
object ObservabilitySnapshot:
  /** Empty observability export. */
  val empty: ObservabilitySnapshot = ObservabilitySnapshot(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
  given Codec[ObservabilitySnapshot] = Codec.from(
    Decoder.forProduct4("counters", "gauges", "timingsMillis", "events")(ObservabilitySnapshot.apply),
    Encoder.forProduct4("counters", "gauges", "timingsMillis", "events")((snapshot: ObservabilitySnapshot) =>
      (snapshot.counters, snapshot.gauges, snapshot.timingsMillis, snapshot.events)
    )
  )

/** Minimal observability surface used throughout the codebase. */
trait Observability[F[_]]:
  /** Adds `delta` to a named counter. */
  def incrementCounter(name: String, labels: Map[String, String] = Map.empty, delta: Long = 1L): F[Unit]

  /** Sets the current value of a gauge. */
  def setGauge(name: String, value: Long, labels: Map[String, String] = Map.empty): F[Unit]

  /** Records one timing sample in milliseconds. */
  def recordTiming(name: String, millis: Long, labels: Map[String, String] = Map.empty): F[Unit]

  /** Emits one structured log event. */
  def log(event: LogEvent): F[Unit]

/** Constructors and helpers for [[Observability]]. */
object Observability:
  /** No-op implementation useful in tests or call sites that do not care about metrics. */
  def noop[F[_]: Sync]: Observability[F] = new Observability[F]:
    override def incrementCounter(name: String, labels: Map[String, String], delta: Long): F[Unit] = Sync[F].unit
    override def setGauge(name: String, value: Long, labels: Map[String, String]): F[Unit] = Sync[F].unit
    override def recordTiming(name: String, millis: Long, labels: Map[String, String]): F[Unit] = Sync[F].unit
    override def log(event: LogEvent): F[Unit] = Sync[F].unit

  /** Creates a bounded in-memory implementation suitable for local diagnostics and specs. */
  def inMemory[F[_]: Sync]: F[InMemoryObservability[F]] =
    Ref.of[F, InMemoryObservability.State](InMemoryObservability.State.empty).map(new InMemoryObservability[F](_))

  /**
   * Adds fixed labels and fields to all downstream metrics and log events.
   *
   * This is used heavily for group-scoped and node-scoped observability so callers do not have to repeat
   * those labels at every emission site.
   */
  def scoped[F[_]](
      underlying: Observability[F],
      metricLabels: Map[String, String] = Map.empty,
      eventFields: Map[String, String] = Map.empty
  ): Observability[F] =
    new Observability[F]:
      override def incrementCounter(name: String, labels: Map[String, String], delta: Long): F[Unit] =
        underlying.incrementCounter(name, labels ++ metricLabels, delta)

      override def setGauge(name: String, value: Long, labels: Map[String, String]): F[Unit] =
        underlying.setGauge(name, value, labels ++ metricLabels)

      override def recordTiming(name: String, millis: Long, labels: Map[String, String]): F[Unit] =
        underlying.recordTiming(name, millis, labels ++ metricLabels)

      override def log(event: LogEvent): F[Unit] =
        underlying.log(event.copy(fields = event.fields ++ eventFields))

/** Internal state model backing [[InMemoryObservability]]. */
object InMemoryObservability:
  private[observability] final case class State(
      counters: Map[MetricKey, Long],
      gauges: Map[MetricKey, Long],
      timingsMillis: Map[MetricKey, Vector[Long]],
      events: Vector[LogEvent]
  )

  private[observability] object State:
    val empty: State = State(Map.empty, Map.empty, Map.empty, Vector.empty)

/** In-memory implementation of [[Observability]] with bounded retained samples. */
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

  /** Returns a stable, sorted [[ObservabilitySnapshot]] of the current in-memory state. */
  def snapshot: F[ObservabilitySnapshot] =
    state.get.map { snapshot =>
      ObservabilitySnapshot(
        snapshot.counters.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.gauges.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.timingsMillis.toVector.sortBy(_._1).map { case (metric, value) => MetricSample(metric, value) },
        snapshot.events
      )
    }
