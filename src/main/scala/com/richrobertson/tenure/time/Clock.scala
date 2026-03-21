package com.richrobertson.tenure.time

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.functor.*
import java.time.Instant

trait Clock[F[_]]:
  def now: F[Instant]

object Clock:
  def system[F[_]: Sync]: Clock[F] = new Clock[F]:
    override def now: F[Instant] = Sync[F].delay(Instant.now())

final class TestClock[F[_]: Sync] private (ref: Ref[F, Instant]) extends Clock[F]:
  override def now: F[Instant] = ref.get
  def set(instant: Instant): F[Unit] = ref.set(instant)
  def advanceSeconds(seconds: Long): F[Unit] = ref.update(_.plusSeconds(seconds))

object TestClock:
  def create[F[_]: Sync](initial: Instant): F[TestClock[F]] =
    Ref.of[F, Instant](initial).map(new TestClock[F](_))
