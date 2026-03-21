package com.richrobertson.tenure.auth

import com.richrobertson.tenure.model.TenantId

final case class Principal(id: String, tenantId: TenantId)

trait Authorization:
  def authorize(principal: Principal, tenantId: TenantId): Either[Authorization.Decision, Unit]

object Authorization:
  enum Decision derives CanEqual:
    case Unauthorized(message: String)
    case Forbidden(message: String)

  val perTenant: Authorization = new Authorization:
    override def authorize(principal: Principal, tenantId: TenantId): Either[Decision, Unit] =
      if principal.id.trim.isEmpty then Left(Decision.Unauthorized("principal_id must be non-empty"))
      else if principal.tenantId.value.trim.isEmpty then Left(Decision.Unauthorized("principal_tenant_id must be non-empty"))
      else if principal.tenantId == tenantId then Right(())
      else Left(Decision.Forbidden(s"principal ${principal.id} cannot access tenant ${tenantId.value}"))
