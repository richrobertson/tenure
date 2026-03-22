/**
 * Deterministic failure-injection utilities used by tests and local evaluation flows.
 *
 * v1 uses delay-based injection points rather than a large chaos framework. This keeps the harness easy to
 * understand while still exercising persistence and recovery behavior under controlled adverse conditions.
 */
package com.richrobertson.tenure.testkit

import cats.effect.Ref
import cats.Applicative
import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.richrobertson.tenure.observability.{LogEvent, Observability}
import scala.concurrent.duration.*

/** Named failure-injection hook understood by selected runtime components. */
final case class FailurePoint(name: String) derives CanEqual

/** Well-known [[FailurePoint]] values used in tests and demos. */
object FailurePoint:
  /** Delay appended log-entry persistence. */
  val PersistenceAppend = FailurePoint("persistence_append")
  /** Delay full log overwrite operations. */
  val PersistenceOverwrite = FailurePoint("persistence_overwrite")
  /** Delay snapshot persistence. */
  val PersistenceSnapshotSave = FailurePoint("persistence_snapshot_save")
  /** Delay metadata persistence. */
  val PersistenceMetadataSave = FailurePoint("persistence_metadata_save")

/** Effectful hook for injecting controlled faults. */
trait FailureInjector[F[_]]:
  /** Potentially injects a failure or delay at the given point for `nodeId`. */
  def inject(point: FailurePoint, nodeId: String): F[Unit]

/** Constructors for [[FailureInjector]]. */
object FailureInjector:
  /** No-op injector used by normal production-shaped paths. */
  def noop[F[_]: Applicative]: FailureInjector[F] = new FailureInjector[F]:
    override def inject(point: FailurePoint, nodeId: String): F[Unit] = Applicative[F].unit

  /** Creates a stateful injector whose delays can be changed at runtime. */
  def controlled[F[_]: Temporal](observability: Observability[F], clockMillis: F[Long]): F[ControlledFailureInjector[F]] =
    Ref.of[F, Map[FailurePoint, Long]](Map.empty).map(new ControlledFailureInjector[F](_, observability, clockMillis))

/**
 * Delay-based [[FailureInjector]] backed by mutable per-point settings.
 *
 * Example:
 * {{{
 * injector.setDelay(FailurePoint.PersistenceAppend, 250L)
 * // next persistence append on a node will sleep for ~250ms
 * }}}
 */
final class ControlledFailureInjector[F[_]: Temporal](
    delays: Ref[F, Map[FailurePoint, Long]],
    observability: Observability[F],
    clockMillis: F[Long]
) extends FailureInjector[F]:
  /** Sets the injected delay for one failure point. */
  def setDelay(point: FailurePoint, millis: Long): F[Unit] = delays.update(_.updated(point, millis))
  /** Clears any injected delay for one failure point. */
  def clearDelay(point: FailurePoint): F[Unit] = delays.update(_ - point)

  /** Injects the configured delay, if any, and records observability around it. */
  override def inject(point: FailurePoint, nodeId: String): F[Unit] =
    delays.get.flatMap(_.get(point).fold(Temporal[F].unit) { millis =>
      for
        ts <- clockMillis
        _ <- observability.incrementCounter("failure_injection_total", Map("point" -> point.name, "node_id" -> nodeId))
        _ <- observability.recordTiming("failure_injection_delay_ms", millis, Map("point" -> point.name, "node_id" -> nodeId))
        _ <- observability.log(LogEvent(ts, "WARN", "failure_injection.delay", s"injecting delay at ${point.name}", nodeId = Some(nodeId), fields = Map("delay_ms" -> millis.toString, "point" -> point.name)))
        _ <- Temporal[F].sleep(millis.millis)
      yield ()
    })
