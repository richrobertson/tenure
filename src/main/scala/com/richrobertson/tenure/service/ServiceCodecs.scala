package com.richrobertson.tenure.service

import cats.syntax.all.*
import com.richrobertson.tenure.statemachine.LeaseState
import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

object ServiceCodecs:
  given Codec[RequestOperation] = Codec.from(
    Decoder.decodeString.map(RequestOperation.valueOf),
    Encoder.encodeString.contramap(_.toString)
  )

  given Codec[RequestFingerprint] = deriveCodec
  given Codec[StoredResponse] = deriveCodec
  given Codec[RequestContext] = deriveCodec
  given Codec[AcquireCommand] = deriveCodec
  given Codec[RenewCommand] = deriveCodec
  given Codec[ReleaseCommand] = deriveCodec
  given Codec[AcquireResult] = deriveCodec
  given Codec[RenewResult] = deriveCodec
  given Codec[ReleaseResult] = deriveCodec
  given Codec[StoredResult] = Codec.from(storedResultDecoder, storedResultEncoder)
  given Codec[ServiceError] = Codec.from(serviceErrorDecoder, serviceErrorEncoder)
  given Codec[ReplicatedCommand] = Codec.from(commandDecoder, commandEncoder)
  given Codec[LeaseState] = Codec.from(leaseStateDecoder, leaseStateEncoder)
  given Codec[ServiceState] = Codec.from(serviceStateDecoder, serviceStateEncoder)

  private final case class LeaseEntry(resourceKey: com.richrobertson.tenure.model.ResourceKey, record: com.richrobertson.tenure.model.LeaseRecord)
  private final case class ResponseEntry(tenantId: com.richrobertson.tenure.model.TenantId, requestId: com.richrobertson.tenure.model.RequestId, response: StoredResponse)
  private final case class LeaseStatePayload(leases: Vector[LeaseEntry])
  private final case class ServiceStatePayload(leaseState: LeaseStatePayload, responses: Vector[ResponseEntry])

  private given Codec[LeaseEntry] = deriveCodec
  private given Codec[ResponseEntry] = deriveCodec
  private given Codec[LeaseStatePayload] = deriveCodec
  private given Codec[ServiceStatePayload] = deriveCodec

  private def eitherDecoder[A: Decoder]: Decoder[Either[ServiceError, A]] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "left"  => cursor.get[ServiceError]("value").map(Left.apply)
      case "right" => cursor.get[A]("value").map(Right.apply)
      case other   => Left(io.circe.DecodingFailure(s"unknown either type $other", cursor.history))
    }
  }

  private def eitherEncoder[A: Encoder]: Encoder[Either[ServiceError, A]] = Encoder.instance {
    case Left(error)   => Json.obj("type" -> Json.fromString("left"), "value" -> error.asJson)
    case Right(result) => Json.obj("type" -> Json.fromString("right"), "value" -> result.asJson)
  }

  private val serviceErrorDecoder: Decoder[ServiceError] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "invalid_request" => cursor.get[String]("message").map(ServiceError.InvalidRequest.apply)
      case "unauthorized"    => cursor.get[String]("message").map(ServiceError.Unauthorized.apply)
      case "forbidden"       => cursor.get[String]("message").map(ServiceError.Forbidden.apply)
      case "already_held"    => cursor.get[String]("message").map(ServiceError.AlreadyHeld.apply)
      case "lease_expired"   => cursor.get[String]("message").map(ServiceError.LeaseExpired.apply)
      case "lease_mismatch"  => cursor.get[String]("message").map(ServiceError.LeaseMismatch.apply)
      case "not_found"       => cursor.get[String]("message").map(ServiceError.NotFound.apply)
      case "quota_exceeded"  => cursor.get[String]("message").map(ServiceError.QuotaExceeded.apply)
      case "not_leader"      => (cursor.get[String]("message"), cursor.get[Option[String]]("leader_hint")).mapN(ServiceError.NotLeader.apply)
      case other             => Left(io.circe.DecodingFailure(s"unknown service error $other", cursor.history))
    }
  }

  private val serviceErrorEncoder: Encoder[ServiceError] = Encoder.instance {
    case ServiceError.InvalidRequest(message)        => Json.obj("type" -> Json.fromString("invalid_request"), "message" -> Json.fromString(message))
    case ServiceError.Unauthorized(message)          => Json.obj("type" -> Json.fromString("unauthorized"), "message" -> Json.fromString(message))
    case ServiceError.Forbidden(message)             => Json.obj("type" -> Json.fromString("forbidden"), "message" -> Json.fromString(message))
    case ServiceError.AlreadyHeld(message)           => Json.obj("type" -> Json.fromString("already_held"), "message" -> Json.fromString(message))
    case ServiceError.LeaseExpired(message)          => Json.obj("type" -> Json.fromString("lease_expired"), "message" -> Json.fromString(message))
    case ServiceError.LeaseMismatch(message)         => Json.obj("type" -> Json.fromString("lease_mismatch"), "message" -> Json.fromString(message))
    case ServiceError.NotFound(message)              => Json.obj("type" -> Json.fromString("not_found"), "message" -> Json.fromString(message))
    case ServiceError.QuotaExceeded(message)         => Json.obj("type" -> Json.fromString("quota_exceeded"), "message" -> Json.fromString(message))
    case ServiceError.NotLeader(message, leaderHint) => Json.obj("type" -> Json.fromString("not_leader"), "message" -> Json.fromString(message), "leader_hint" -> leaderHint.asJson)
  }

  private val commandDecoder: Decoder[ReplicatedCommand] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "acquire" => cursor.get[AcquireCommand]("payload")
      case "renew"   => cursor.get[RenewCommand]("payload")
      case "release" => cursor.get[ReleaseCommand]("payload")
      case other     => Left(io.circe.DecodingFailure(s"unknown command $other", cursor.history))
    }
  }

  private val commandEncoder: Encoder[ReplicatedCommand] = Encoder.instance {
    case payload: AcquireCommand => Json.obj("type" -> Json.fromString("acquire"), "payload" -> payload.asJson)
    case payload: RenewCommand   => Json.obj("type" -> Json.fromString("renew"), "payload" -> payload.asJson)
    case payload: ReleaseCommand => Json.obj("type" -> Json.fromString("release"), "payload" -> payload.asJson)
  }

  private val storedResultDecoder: Decoder[StoredResult] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "acquire" => cursor.downField("payload").as(eitherDecoder[AcquireResult]).map(StoredResult.Acquire.apply)
      case "renew"   => cursor.downField("payload").as(eitherDecoder[RenewResult]).map(StoredResult.Renew.apply)
      case "release" => cursor.downField("payload").as(eitherDecoder[ReleaseResult]).map(StoredResult.Release.apply)
      case other     => Left(io.circe.DecodingFailure(s"unknown stored result $other", cursor.history))
    }
  }

  private val storedResultEncoder: Encoder[StoredResult] = Encoder.instance {
    case payload: StoredResult.Acquire => Json.obj("type" -> Json.fromString("acquire"), "payload" -> eitherEncoder[AcquireResult].apply(payload.value))
    case payload: StoredResult.Renew   => Json.obj("type" -> Json.fromString("renew"), "payload" -> eitherEncoder[RenewResult].apply(payload.value))
    case payload: StoredResult.Release => Json.obj("type" -> Json.fromString("release"), "payload" -> eitherEncoder[ReleaseResult].apply(payload.value))
  }

  private val leaseStateDecoder: Decoder[LeaseState] =
    Decoder[LeaseStatePayload].map(payload => LeaseState(payload.leases.map(entry => entry.resourceKey -> entry.record).toMap))

  private val leaseStateEncoder: Encoder[LeaseState] = Encoder.instance { leaseState =>
    LeaseStatePayload(
      leaseState.leases.toVector
        .sortBy { case (resourceKey, _) => (resourceKey.tenantId.value, resourceKey.resourceId.value) }
        .map { case (resourceKey, record) => LeaseEntry(resourceKey, record) }
    ).asJson
  }

  private val serviceStateDecoder: Decoder[ServiceState] =
    Decoder[ServiceStatePayload].map(payload =>
      ServiceState(
        leaseState = LeaseState(payload.leaseState.leases.map(entry => entry.resourceKey -> entry.record).toMap),
        responses = payload.responses.map(entry => (entry.tenantId, entry.requestId) -> entry.response).toMap
      )
    )

  private val serviceStateEncoder: Encoder[ServiceState] = Encoder.instance { serviceState =>
    ServiceStatePayload(
      leaseState = LeaseStatePayload(
        serviceState.leaseState.leases.toVector
          .sortBy { case (resourceKey, _) => (resourceKey.tenantId.value, resourceKey.resourceId.value) }
          .map { case (resourceKey, record) => LeaseEntry(resourceKey, record) }
      ),
      responses = serviceState.responses.toVector
        .sortBy { case ((tenantId, requestId), _) => (tenantId.value, requestId.value) }
        .map { case ((tenantId, requestId), response) => ResponseEntry(tenantId, requestId, response) }
    ).asJson
  }
