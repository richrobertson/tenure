/**
 * HTTP routes for exposing in-process observability state.
 */
package com.richrobertson.tenure.observability

import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl

/** Debug-only HTTP routes for [[ObservabilitySnapshot]] export. */
object ObservabilityRoutes:
  /** Exposes `GET /debug/observability` returning the current snapshot as JSON. */
  def routes[F[_]: Concurrent](snapshot: F[ObservabilitySnapshot]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] { case GET -> Root / "debug" / "observability" =>
      snapshot.flatMap(value => Ok(value.asJson))
    }
