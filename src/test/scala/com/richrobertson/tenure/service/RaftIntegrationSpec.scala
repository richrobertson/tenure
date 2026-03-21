package com.richrobertson.tenure.service

import cats.effect.IO
import com.richrobertson.tenure.model.{ClientId, LeaseId, LeaseStatus, RequestId, ResourceId, ResourceKey, TenantId}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{PersistedMetadata, RaftLogEntry}
import munit.CatsEffectSuite
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class RaftIntegrationSpec extends CatsEffectSuite:
  private val appliedAt = Instant.parse("2026-03-21T12:00:00Z")

  test("persisted committed log reload reconstructs the authoritative materialized lease view") {
    val tenantId = TenantId("tenant-a")
    val resourceId = ResourceId("resource-1")
    val resourceKey = ResourceKey(tenantId, resourceId)
    val leaseId = LeaseId(UUID.fromString("00000000-0000-0000-0000-000000000321"))
    val acquire = AcquireCommand(
      RequestContext(tenantId, RequestId("req-1"), resourceKey),
      ClientId("holder-1"),
      ttlSeconds = 15,
      leaseId = leaseId,
      appliedAt = appliedAt
    )
    val renew = RenewCommand(
      RequestContext(tenantId, RequestId("req-2"), resourceKey),
      leaseId = leaseId,
      holderId = ClientId("holder-1"),
      ttlSeconds = 30,
      appliedAt = appliedAt.plusSeconds(5)
    )

    for
      root <- IO.blocking(Files.createTempDirectory("tenure-raft-replay-spec"))
      persistence <- RaftPersistence.fileBacked[IO](root.toString)
      _ <- persistence.appendEntry(RaftLogEntry(1L, 3L, acquire))
      _ <- persistence.appendEntry(RaftLogEntry(2L, 3L, renew))
      _ <- persistence.saveMetadata(PersistedMetadata(currentTerm = 3L, votedFor = Some("node-1"), commitIndex = 2L))
      loaded <- persistence.load
      replayed = loaded.entries.filter(_.index <= loaded.metadata.commitIndex).sortBy(_.index).foldLeft(ServiceState.empty) { case (state, entry) =>
        LeaseMaterializer.applyCommand(state, entry.command)
      }
      leaseView = replayed.leaseState.viewAt(resourceKey, appliedAt.plusSeconds(10))
    yield
      assertEquals(loaded.metadata.commitIndex, 2L)
      assertEquals(leaseView.status, LeaseStatus.Active)
      assertEquals(leaseView.leaseId, Some(leaseId))
      assertEquals(leaseView.holderId.map(_.value), Some("holder-1"))
      assertEquals(leaseView.expiresAt, Some(appliedAt.plusSeconds(35)))
      assert(replayed.responses.contains((tenantId, RequestId("req-1"))))
      assert(replayed.responses.contains((tenantId, RequestId("req-2"))))
  }
