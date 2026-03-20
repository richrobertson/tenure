package com.richrobertson.tenure

import cats.effect.{ExitCode, IO, IOApp}
import com.richrobertson.tenure.api.LeaseRoutes
import com.richrobertson.tenure.service.LeaseService
import com.richrobertson.tenure.time.Clock
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    LeaseService.inMemory[IO](Clock.system[IO]).flatMap { service =>
      EmberServerBuilder
        .default[IO]
        .withHost(com.comcast.ip4s.ipv4"0.0.0.0")
        .withPort(com.comcast.ip4s.port"8080")
        .withHttpApp(LeaseRoutes.routes[IO](service).orNotFound)
        .build
        .use(_ => IO.never)
        .as(ExitCode.Success)
    }
