package com.richrobertson.tenure.api

import cats.effect.kernel.Concurrent
import com.richrobertson.tenure.model.{LeaseStatus, LeaseView, ResourceId, TenantId}
import com.richrobertson.tenure.service.*
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

final case class AcquireRequestBody(tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class RenewRequestBody(tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class ReleaseRequestBody(tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)
final case class ErrorResponse(code: String, message: String)
final case class LeaseResponse(
    tenantId: String,
    resourceId: String,
    leaseId: String,
    holderId: String,
    status: String,
    expiryTime: String,
    fencingToken: Long,
    version: Long
)
final case class AcquireResponse(leaseId: String, expiryTime: String, fencingToken: Long, created: Boolean)
final case class RenewResponse(leaseId: String, expiryTime: String, fencingToken: Long, renewed: Boolean)
final case class ReleaseResponse(leaseId: String, expiryTime: String, fencingToken: Long, released: Boolean)
final case class GetLeaseResponse(found: Boolean, lease: LeaseResponse)

object LeaseRoutes:
  given Configuration = Configuration.default.withSnakeCaseMemberNames
  given Codec[AcquireRequestBody] = deriveConfiguredCodec
  given Codec[RenewRequestBody] = deriveConfiguredCodec
  given Codec[ReleaseRequestBody] = deriveConfiguredCodec
  given Codec[ErrorResponse] = deriveConfiguredCodec
  given Codec[LeaseResponse] = deriveConfiguredCodec
  given Codec[AcquireResponse] = deriveConfiguredCodec
  given Codec[RenewResponse] = deriveConfiguredCodec
  given Codec[ReleaseResponse] = deriveConfiguredCodec
  given Codec[GetLeaseResponse] = deriveConfiguredCodec
  given Encoder[LeaseStatus] = Encoder.encodeString.contramap(_.toString.toUpperCase)
  given Decoder[LeaseStatus] = Decoder.decodeString.emap(_ => Left("LeaseStatus decoding is not supported for API requests"))

  def routes[F[_]: Concurrent](service: LeaseService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case req @ POST -> Root / "v1" / "leases" / "acquire" =>
        req.as[AcquireRequestBody].flatMap { body =>
          service.acquire(AcquireRequest(body.tenantId, body.resourceId, body.holderId, body.ttlSeconds, body.requestId)).flatMap(resultToHttpAcquire(_)(using dsl))
        }

      case req @ POST -> Root / "v1" / "leases" / "renew" =>
        req.as[RenewRequestBody].flatMap { body =>
          service.renew(RenewRequest(body.tenantId, body.resourceId, body.leaseId, body.holderId, body.ttlSeconds, body.requestId)).flatMap(resultToHttpRenew(_)(using dsl))
        }

      case req @ POST -> Root / "v1" / "leases" / "release" =>
        req.as[ReleaseRequestBody].flatMap { body =>
          service.release(ReleaseRequest(body.tenantId, body.resourceId, body.leaseId, body.holderId, body.requestId)).flatMap(resultToHttpRelease(_)(using dsl))
        }

      case GET -> Root / "v1" / "leases" / tenantId / resourceId =>
        service.getLease(TenantId(tenantId), ResourceId(resourceId)).flatMap { result =>
          Ok(GetLeaseResponse(result.found, toLeaseResponse(result.lease)))
        }
    }

  private def resultToHttpAcquire[F[_]](result: Either[ServiceError, AcquireResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    result match
      case Right(response) =>
        Ok(AcquireResponse(
          leaseId = response.lease.leaseId.map(_.value.toString).getOrElse(""),
          expiryTime = response.lease.expiresAt.map(_.toString).getOrElse(""),
          fencingToken = response.lease.fencingToken,
          created = response.created
        ))
      case Left(error) => errorToHttp(error)

  private def resultToHttpRenew[F[_]](result: Either[ServiceError, RenewResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    result match
      case Right(response) =>
        Ok(RenewResponse(
          leaseId = response.lease.leaseId.map(_.value.toString).getOrElse(""),
          expiryTime = response.lease.expiresAt.map(_.toString).getOrElse(""),
          fencingToken = response.lease.fencingToken,
          renewed = response.renewed
        ))
      case Left(error) => errorToHttp(error)

  private def resultToHttpRelease[F[_]](result: Either[ServiceError, ReleaseResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    result match
      case Right(response) =>
        Ok(ReleaseResponse(
          leaseId = response.lease.leaseId.map(_.value.toString).getOrElse(""),
          expiryTime = response.lease.expiresAt.map(_.toString).getOrElse(""),
          fencingToken = response.lease.fencingToken,
          released = response.released
        ))
      case Left(error) => errorToHttp(error)

  private def errorToHttp[F[_]](error: ServiceError)(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    error match
      case ServiceError.InvalidRequest(message) => BadRequest(ErrorResponse("INVALID_ARGUMENT", message))
      case ServiceError.AlreadyHeld(message)    => Conflict(ErrorResponse("ALREADY_HELD", message))
      case ServiceError.LeaseExpired(message)   => Conflict(ErrorResponse("LEASE_EXPIRED", message))
      case ServiceError.LeaseMismatch(message)  => Conflict(ErrorResponse("LEASE_MISMATCH", message))
      case ServiceError.NotFound(message)       => NotFound(ErrorResponse("NOT_FOUND", message))

  private def toLeaseResponse(view: LeaseView): LeaseResponse =
    LeaseResponse(
      tenantId = view.tenantId.value,
      resourceId = view.resourceId.value,
      leaseId = view.leaseId.map(_.value.toString).getOrElse(""),
      holderId = view.holderId.map(_.value).getOrElse(""),
      status = view.status.toString.toUpperCase,
      expiryTime = view.expiresAt.map(_.toString).getOrElse(""),
      fencingToken = view.fencingToken,
      version = view.version
    )
