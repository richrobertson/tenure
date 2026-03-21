package com.richrobertson.tenure.observability

import cats.effect.kernel.Concurrent
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl

object ObservabilityRoutes:
  def routes[F[_]: Concurrent](snapshot: F[ObservabilitySnapshot]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] { case GET -> Root / "debug" / "observability" =>
      snapshot.flatMap(value => Ok(value.asJson))
    }
