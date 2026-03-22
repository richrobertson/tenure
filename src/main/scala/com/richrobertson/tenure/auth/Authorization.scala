/**
 * Tenant-scoped authorization helpers.
 *
 * v1 keeps authorization intentionally simple: callers present a principal and requests are allowed only
 * within that principal's tenant boundary.
 */
package com.richrobertson.tenure.auth

import com.richrobertson.tenure.model.TenantId

/** Authenticated caller identity carried through service requests. */
final case class Principal(id: String, tenantId: TenantId)

/** Authorization boundary for tenant-scoped access checks. */
trait Authorization:
  /**
   * Authorizes a principal to act on behalf of `tenantId`.
   *
   * Returns `Right(())` on success or a structured denial on failure.
   */
  def authorize(principal: Principal, tenantId: TenantId): Either[Authorization.Decision, Unit]

/** Built-in authorization decisions and policies. */
object Authorization:
  /** Structured authorization failures suitable for service-level mapping. */
  enum Decision derives CanEqual:
    case Unauthorized(message: String)
    case Forbidden(message: String)

  /**
   * Default v1 policy: principals may only act within their own tenant.
   *
   * It also rejects blank principal identifiers up front so later layers can assume basic caller identity
   * invariants already hold.
   */
  val perTenant: Authorization = new Authorization:
    override def authorize(principal: Principal, tenantId: TenantId): Either[Decision, Unit] =
      if principal.id.trim.isEmpty then Left(Decision.Unauthorized("principal_id must be non-empty"))
      else if principal.tenantId.value.trim.isEmpty then Left(Decision.Unauthorized("principal_tenant_id must be non-empty"))
      else if principal.tenantId == tenantId then Right(())
      else Left(Decision.Forbidden(s"principal ${principal.id} cannot access tenant ${tenantId.value}"))
