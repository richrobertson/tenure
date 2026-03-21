package com.richrobertson.tenure.testkit

import cats.effect.Ref
import cats.effect.kernel.Temporal
import cats.syntax.all.*
import com.richrobertson.tenure.observability.{LogEvent, Observability}
import scala.concurrent.duration.*

final case class FailurePoint(name: String) derives CanEqual
object FailurePoint:
  val PersistenceAppend = FailurePoint("persistence_append")
  val PersistenceOverwrite = FailurePoint("persistence_overwrite")
  val PersistenceSnapshotSave = FailurePoint("persistence_snapshot_save")
  val PersistenceMetadataSave = FailurePoint("persistence_metadata_save")

trait FailureInjector[F[_]]:
  def inject(point: FailurePoint, nodeId: String): F[Unit]

object FailureInjector:
  def noop[F[_]: Temporal]: FailureInjector[F] = new FailureInjector[F]:
    override def inject(point: FailurePoint, nodeId: String): F[Unit] = Temporal[F].unit

  def controlled[F[_]: Temporal](observability: Observability[F], clockMillis: F[Long]): F[ControlledFailureInjector[F]] =
    Ref.of[F, Map[FailurePoint, Long]](Map.empty).map(new ControlledFailureInjector[F](_, observability, clockMillis))

final class ControlledFailureInjector[F[_]: Temporal](
    delays: Ref[F, Map[FailurePoint, Long]],
    observability: Observability[F],
    clockMillis: F[Long]
) extends FailureInjector[F]:
  def setDelay(point: FailurePoint, millis: Long): F[Unit] = delays.update(_.updated(point, millis))
  def clearDelay(point: FailurePoint): F[Unit] = delays.update(_ - point)

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
