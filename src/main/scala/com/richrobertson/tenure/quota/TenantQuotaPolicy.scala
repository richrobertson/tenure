package com.richrobertson.tenure.quota

import com.richrobertson.tenure.model.TenantId
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class TenantQuotaPolicy(maxActiveLeases: Int, maxTtlSeconds: Long) derives CanEqual
object TenantQuotaPolicy:
  given Codec[TenantQuotaPolicy] = deriveCodec


final case class TenantQuotaRegistry(defaultPolicy: TenantQuotaPolicy, tenantOverrides: Map[TenantId, TenantQuotaPolicy]) derives CanEqual:
  def policyFor(tenantId: TenantId): TenantQuotaPolicy = tenantOverrides.getOrElse(tenantId, defaultPolicy)

  def validateTtl(policy: TenantQuotaPolicy, tenantId: TenantId, ttlSeconds: Long): Either[String, Unit] =
    Either.cond(ttlSeconds <= policy.maxTtlSeconds, (), s"tenant ${tenantId.value} requested ttl_seconds=$ttlSeconds but max_ttl_seconds=${policy.maxTtlSeconds}")

  def validateActiveLeases(policy: TenantQuotaPolicy, tenantId: TenantId, activeLeases: Int): Either[String, Unit] =
    Either.cond(activeLeases < policy.maxActiveLeases, (), s"tenant ${tenantId.value} already has $activeLeases active leases and max_active_leases=${policy.maxActiveLeases}")

object TenantQuotaRegistry:
  val default: TenantQuotaRegistry = TenantQuotaRegistry(
    defaultPolicy = TenantQuotaPolicy(maxActiveLeases = 1000, maxTtlSeconds = 300),
    tenantOverrides = Map.empty
  )

object QuotaDecision:
  def unapply(value: Either[String, Unit]): Option[String] = value.swap.toOption
