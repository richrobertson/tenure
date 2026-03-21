package com.richrobertson.tenure.api

import cats.Applicative
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import com.richrobertson.tenure.auth.Principal
import com.richrobertson.tenure.model.{LeaseStatus, LeaseView, ResourceId, TenantId}
import com.richrobertson.tenure.service.*
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.DecodeFailure
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

final case class AcquireRequestBody(tenantId: String, resourceId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class RenewRequestBody(tenantId: String, resourceId: String, leaseId: String, holderId: String, ttlSeconds: Long, requestId: String)
final case class ReleaseRequestBody(tenantId: String, resourceId: String, leaseId: String, holderId: String, requestId: String)
final case class ErrorResponse(code: String, message: String, leaderHint: Option[String] = None)
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
final case class ListLeasesResponse(leases: List[LeaseResponse])

object LeaseRoutes:
  private val principalIdHeader = ci"X-Tenure-Principal-Id"
  private val principalTenantHeader = ci"X-Tenure-Principal-Tenant"

  given Codec[AcquireRequestBody] = Codec.from(
    Decoder.forProduct5("tenant_id", "resource_id", "holder_id", "ttl_seconds", "request_id")(AcquireRequestBody.apply),
    Encoder.forProduct5("tenant_id", "resource_id", "holder_id", "ttl_seconds", "request_id")(body =>
      (body.tenantId, body.resourceId, body.holderId, body.ttlSeconds, body.requestId)
    )
  )

  given Codec[RenewRequestBody] = Codec.from(
    Decoder.forProduct6("tenant_id", "resource_id", "lease_id", "holder_id", "ttl_seconds", "request_id")(RenewRequestBody.apply),
    Encoder.forProduct6("tenant_id", "resource_id", "lease_id", "holder_id", "ttl_seconds", "request_id")(body =>
      (body.tenantId, body.resourceId, body.leaseId, body.holderId, body.ttlSeconds, body.requestId)
    )
  )

  given Codec[ReleaseRequestBody] = Codec.from(
    Decoder.forProduct5("tenant_id", "resource_id", "lease_id", "holder_id", "request_id")(ReleaseRequestBody.apply),
    Encoder.forProduct5("tenant_id", "resource_id", "lease_id", "holder_id", "request_id")(body =>
      (body.tenantId, body.resourceId, body.leaseId, body.holderId, body.requestId)
    )
  )

  given Codec[ErrorResponse] = Codec.from(
    Decoder.forProduct3("code", "message", "leader_hint")(ErrorResponse.apply),
    Encoder.forProduct3("code", "message", "leader_hint")(response =>
      (response.code, response.message, response.leaderHint)
    )
  )

  given Codec[LeaseResponse] = Codec.from(
    Decoder.forProduct8("tenant_id", "resource_id", "lease_id", "holder_id", "status", "expiry_time", "fencing_token", "version")(LeaseResponse.apply),
    Encoder.forProduct8("tenant_id", "resource_id", "lease_id", "holder_id", "status", "expiry_time", "fencing_token", "version")(response =>
      (response.tenantId, response.resourceId, response.leaseId, response.holderId, response.status, response.expiryTime, response.fencingToken, response.version)
    )
  )

  given Codec[AcquireResponse] = Codec.from(
    Decoder.forProduct4("lease_id", "expiry_time", "fencing_token", "created")(AcquireResponse.apply),
    Encoder.forProduct4("lease_id", "expiry_time", "fencing_token", "created")(response =>
      (response.leaseId, response.expiryTime, response.fencingToken, response.created)
    )
  )

  given Codec[RenewResponse] = Codec.from(
    Decoder.forProduct4("lease_id", "expiry_time", "fencing_token", "renewed")(RenewResponse.apply),
    Encoder.forProduct4("lease_id", "expiry_time", "fencing_token", "renewed")(response =>
      (response.leaseId, response.expiryTime, response.fencingToken, response.renewed)
    )
  )

  given Codec[ReleaseResponse] = Codec.from(
    Decoder.forProduct4("lease_id", "expiry_time", "fencing_token", "released")(ReleaseResponse.apply),
    Encoder.forProduct4("lease_id", "expiry_time", "fencing_token", "released")(response =>
      (response.leaseId, response.expiryTime, response.fencingToken, response.released)
    )
  )

  given Codec[GetLeaseResponse] = Codec.from(
    Decoder.forProduct2("found", "lease")(GetLeaseResponse.apply),
    Encoder.forProduct2("found", "lease")(response => (response.found, response.lease))
  )

  given Codec[ListLeasesResponse] = Codec.from(
    Decoder.forProduct1("leases")(ListLeasesResponse.apply),
    Encoder.forProduct1("leases")(_.leases)
  )
  given Encoder[LeaseStatus] = Encoder.encodeString.contramap(_.toString.toUpperCase)
  given Decoder[LeaseStatus] = Decoder.decodeString.emap(_ => Left("LeaseStatus decoding is not supported for API requests"))

  def routes[F[_]: Concurrent](service: LeaseService[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    given Http4sDsl[F] = dsl
    import dsl.*

    HttpRoutes.of[F] {
      case req @ POST -> Root / "v1" / "leases" / "acquire" =>
        withPrincipal(req) { principal =>
          decodeBody[F, AcquireRequestBody](req).flatMap {
            case Right(body) =>
              service.acquire(AcquireRequest(principal, body.tenantId, body.resourceId, body.holderId, body.ttlSeconds, body.requestId)).flatMap(resultToHttpAcquire(_))
            case Left(response) => response.pure[F]
          }
        }

      case req @ POST -> Root / "v1" / "leases" / "renew" =>
        withPrincipal(req) { principal =>
          decodeBody[F, RenewRequestBody](req).flatMap {
            case Right(body) =>
              service.renew(RenewRequest(principal, body.tenantId, body.resourceId, body.leaseId, body.holderId, body.ttlSeconds, body.requestId)).flatMap(resultToHttpRenew(_))
            case Left(response) => response.pure[F]
          }
        }

      case req @ POST -> Root / "v1" / "leases" / "release" =>
        withPrincipal(req) { principal =>
          decodeBody[F, ReleaseRequestBody](req).flatMap {
            case Right(body) =>
              service.release(ReleaseRequest(principal, body.tenantId, body.resourceId, body.leaseId, body.holderId, body.requestId)).flatMap(resultToHttpRelease(_))
            case Left(response) => response.pure[F]
          }
        }

      case req @ GET -> Root / "v1" / "leases" / tenantId / resourceId =>
        withPrincipal(req) { principal =>
          service.getLease(GetLeaseRequest(principal, tenantId, resourceId)).flatMap {
            case Right(result) => Ok(GetLeaseResponse(result.found, toLeaseResponse(result.lease)))
            case Left(error)   => errorToHttp(error)
          }
        }

      case req @ GET -> Root / "v1" / "tenants" / tenantId / "leases" =>
        withPrincipal(req) { principal =>
          service.listLeases(ListLeasesRequest(principal, tenantId)).flatMap {
            case Right(result) => Ok(ListLeasesResponse(result.leases.map(toLeaseResponse)))
            case Left(error)   => errorToHttp(error)
          }
        }
    }

  private def withPrincipal[F[_]: Applicative](req: org.http4s.Request[F])(use: Principal => F[org.http4s.Response[F]])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    val principal = for
      id <- req.headers.get(principalIdHeader).map(_.head.value.trim).filter(_.nonEmpty)
      tenant <- req.headers.get(principalTenantHeader).map(_.head.value.trim).filter(_.nonEmpty)
    yield Principal(id, TenantId(tenant))

    principal match
      case Some(principalValue) => use(principalValue)
      case None => Response[F](status = Status.Unauthorized).withEntity(ErrorResponse("UNAUTHORIZED", s"headers ${principalIdHeader.toString} and ${principalTenantHeader.toString} are required")).pure[F]

  private def decodeBody[F[_]: Concurrent, A: Decoder](req: org.http4s.Request[F])(using dsl: Http4sDsl[F]): F[Either[org.http4s.Response[F], A]] =
    import dsl.*
    req.attemptAs[A].value.flatMap {
      case Right(value) => Right(value).pure[F]
      case Left(failure) =>
        BadRequest(ErrorResponse("INVALID_ARGUMENT", renderDecodeFailure(failure))).map(_.asLeft[A])
    }

  private def renderDecodeFailure(failure: DecodeFailure): String =
    Option(failure.message).map(_.trim).filter(_.nonEmpty).getOrElse("request body could not be decoded")

  private def resultToHttpAcquire[F[_]: Applicative](result: Either[ServiceError, AcquireResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
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

  private def resultToHttpRenew[F[_]: Applicative](result: Either[ServiceError, RenewResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
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

  private def resultToHttpRelease[F[_]: Applicative](result: Either[ServiceError, ReleaseResult])(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
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

  private def errorToHttp[F[_]: Applicative](error: ServiceError)(using dsl: Http4sDsl[F]): F[org.http4s.Response[F]] =
    import dsl.*
    error match
      case ServiceError.InvalidRequest(message) => BadRequest(ErrorResponse("INVALID_ARGUMENT", message))
      case ServiceError.Unauthorized(message)   => Response[F](status = Status.Unauthorized).withEntity(ErrorResponse("UNAUTHORIZED", message)).pure[F]
      case ServiceError.Forbidden(message)      => Forbidden(ErrorResponse("FORBIDDEN", message))
      case ServiceError.AlreadyHeld(message)    => Conflict(ErrorResponse("ALREADY_HELD", message))
      case ServiceError.LeaseExpired(message)   => Conflict(ErrorResponse("LEASE_EXPIRED", message))
      case ServiceError.LeaseMismatch(message)  => Conflict(ErrorResponse("LEASE_MISMATCH", message))
      case ServiceError.NotFound(message)       => NotFound(ErrorResponse("NOT_FOUND", message))
      case ServiceError.QuotaExceeded(message)  => Conflict(ErrorResponse("QUOTA_EXCEEDED", message))
      case ServiceError.NotLeader(message, leaderHint) => Conflict(ErrorResponse("NOT_LEADER", message, leaderHint))

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
