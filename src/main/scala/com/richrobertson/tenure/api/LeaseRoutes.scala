package com.richrobertson.tenure.api

import cats.effect.kernel.Concurrent
import com.richrobertson.tenure.model.{LeaseStatus, LeaseView, ResourceId, TenantId}
import com.richrobertson.tenure.service.*
import io.circe.Codec
import io.circe.generic.semiauto.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

final case class ErrorResponse(code: String, message: String)
final case class LeaseResponse(
    leaseId: String,
    tenantId: String,
    resourceId: String,
    holderId: String,
    status: String,
    acquiredAt: String,
    expiresAt: String,
    releasedAt: Option[String],
    lastRenewedAt: Option[String]
)

object LeaseRoutes:
  given Codec[AcquireRequest] = deriveCodec
  given Codec[RenewRequest] = deriveCodec
  given Codec[ReleaseRequest] = deriveCodec
  given Codec[ErrorResponse] = deriveCodec
  given Codec[LeaseResponse] = deriveCodec

  def routes[F[_]: Concurrent](service: LeaseService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case req @ POST -> Root / "v1" / "leases" / "acquire" =>
        req.as[AcquireRequest].flatMap { request =>
          service.acquire(request).flatMap(resultToHttp(_)(using dsl))
        }

      case req @ POST -> Root / "v1" / "leases" / "renew" =>
        req.as[RenewRequest].flatMap { request =>
          service.renew(request).flatMap(resultToHttp(_)(using dsl))
        }

      case req @ POST -> Root / "v1" / "leases" / "release" =>
        req.as[ReleaseRequest].flatMap { request =>
          service.release(request).flatMap(resultToHttp(_)(using dsl))
        }

      case GET -> Root / "v1" / "leases" / tenantId / resourceId =>
        service.getLease(TenantId(tenantId), ResourceId(resourceId)).flatMap {
          case Some(lease) => Ok(toLeaseResponse(lease))
          case None        => NotFound(ErrorResponse("NOT_FOUND", s"no lease found for tenant=$tenantId resource=$resourceId"))
        }
    }

  private def resultToHttp[F[_]](result: Either[ServiceError, LeaseView])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    result match
      case Right(view) => Ok(toLeaseResponse(view))
      case Left(ServiceError.InvalidRequest(message)) => BadRequest(ErrorResponse("INVALID_ARGUMENT", message))
      case Left(ServiceError.AlreadyHeld(message))    => Conflict(ErrorResponse("ALREADY_HELD", message))
      case Left(ServiceError.LeaseExpired(message))   => Conflict(ErrorResponse("LEASE_EXPIRED", message))
      case Left(ServiceError.LeaseMismatch(message))  => Conflict(ErrorResponse("LEASE_MISMATCH", message))
      case Left(ServiceError.NotFound(message))       => NotFound(ErrorResponse("NOT_FOUND", message))

  private def toLeaseResponse(view: LeaseView): LeaseResponse =
    LeaseResponse(
      leaseId = view.leaseId.value.toString,
      tenantId = view.tenantId.value,
      resourceId = view.resourceId.value,
      holderId = view.holderId.value,
      status = renderStatus(view.status),
      acquiredAt = view.acquiredAt.toString,
      expiresAt = view.expiresAt.toString,
      releasedAt = view.releasedAt.map(_.toString),
      lastRenewedAt = view.lastRenewedAt.map(_.toString)
    )

  private def renderStatus(status: LeaseStatus): String = status.toString.toUpperCase
