/**
 * Time abstractions used by Tenure.
 *
 * The codebase keeps wall-clock access behind this package so expiration logic can stay deterministic in
 * tests and explicit in production code.
 */
package com.richrobertson.tenure.time

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.functor.*
import java.time.Instant

/** Effectful source of the current instant. */
trait Clock[F[_]]:
  /** Returns the current time according to this clock. */
  def now: F[Instant]

/** Standard [[Clock]] constructors. */
object Clock:
  /** Real wall-clock implementation backed by `Instant.now()`. */
  def system[F[_]: Sync]: Clock[F] = new Clock[F]:
    override def now: F[Instant] = Sync[F].delay(Instant.now())

/**
 * Deterministic mutable test clock.
 *
 * Newcomers: this is the easiest way to test expiry and renewal behavior without sleeping.
 */
final class TestClock[F[_]: Sync] private (ref: Ref[F, Instant]) extends Clock[F]:
  override def now: F[Instant] = ref.get

  /** Sets the clock to an exact instant. */
  def set(instant: Instant): F[Unit] = ref.set(instant)

  /** Advances the clock by whole seconds. */
  def advanceSeconds(seconds: Long): F[Unit] = ref.update(_.plusSeconds(seconds))

/** Factory methods for [[TestClock]]. */
object TestClock:
  /** Creates a mutable test clock starting at `initial`. */
  def create[F[_]: Sync](initial: Instant): F[TestClock[F]] =
    Ref.of[F, Instant](initial).map(new TestClock[F](_))
