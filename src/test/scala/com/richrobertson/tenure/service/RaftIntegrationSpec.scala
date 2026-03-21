package com.richrobertson.tenure.service

import cats.effect.{IO, Resource, Ref}
import cats.syntax.all.*
import com.richrobertson.tenure.model.{ResourceId, TenantId}
import com.richrobertson.tenure.persistence.RaftPersistence
import com.richrobertson.tenure.raft.{ClusterConfig, NodeRole, PeerNode, RaftNode}
import com.richrobertson.tenure.time.Clock
import munit.CatsEffectSuite
import java.net.ServerSocket
import java.nio.file.Files
import scala.concurrent.duration.*

class RaftIntegrationSpec extends CatsEffectSuite:
  override val munitTimeout: Duration = 45.seconds

  test("follower behind by multiple entries catches up after restart and later commits new entries") {
    clusterResource.use { cluster =>
      for
        leader <- cluster.awaitLeader
        lagging = cluster.otherThan(leader.id).head
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        _ <- lagging.stop
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-2", "holder-2", 30, "req-2"))
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-3", "holder-3", 30, "req-3"))
        _ <- lagging.start
        _ <- cluster.awaitLeaseVisible(lagging, TenantId("tenant-a"), ResourceId("resource-3"))
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-4", "holder-4", 30, "req-4"))
        _ <- cluster.awaitLeaseVisible(lagging, TenantId("tenant-a"), ResourceId("resource-4"))
        check <- lagging.getLease(TenantId("tenant-a"), ResourceId("resource-4"))
      yield assertEquals(check.toOption.map(_.found), Some(true))
    }
  }

  test("leader recovers progress after temporary quorum loss and stale isolated leader refuses leader-only reads") {
    clusterResource.use { cluster =>
      for
        leader <- cluster.awaitLeader
        followers = cluster.otherThan(leader.id)
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        _ <- followers.traverse_(_.stop)
        _ <- IO.sleep(1500.millis)
        staleRead <- leader.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
        staleWrite <- leader.acquire(AcquireRequest("tenant-a", "resource-2", "holder-2", 30, "req-2"))
        _ <- followers.traverse_(_.start)
        newLeader <- cluster.awaitLeader
        postRecovery <- newLeader.acquire(AcquireRequest("tenant-a", "resource-3", "holder-3", 30, "req-3"))
      yield
        assert(staleRead.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        assert(staleWrite.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        assert(postRecovery.isRight)
    }
  }

  test("followers reject reads and writes with NOT_LEADER") {
    clusterResource.use { cluster =>
      for
        leader <- cluster.awaitLeader
        follower = cluster.otherThan(leader.id).head
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        read <- follower.getLease(TenantId("tenant-a"), ResourceId("resource-1"))
        write <- follower.acquire(AcquireRequest("tenant-a", "resource-2", "holder-2", 30, "req-2"))
      yield
        assert(read.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
        assert(write.left.exists(_.isInstanceOf[ServiceError.NotLeader]))
    }
  }

  test("restart replays committed log") {
    clusterResource.use { cluster =>
      for
        leader <- cluster.awaitLeader
        target = cluster.otherThan(leader.id).head
        _ <- leader.acquire(AcquireRequest("tenant-a", "resource-1", "holder-1", 30, "req-1"))
        _ <- cluster.awaitLeaseVisible(target, TenantId("tenant-a"), ResourceId("resource-1"))
        _ <- target.stop
        _ <- target.start
        lease <- cluster.awaitLeaseVisible(target, TenantId("tenant-a"), ResourceId("resource-1"))
      yield assertEquals(lease.found, true)
    }
  }

  private def clusterResource: Resource[IO, TestCluster] =
    for
      root <- Resource.eval(IO.blocking(Files.createTempDirectory("tenure-raft-spec")))
      peerPorts <- Resource.eval(List.fill(3)(freeLocalPort).sequence)
      apiPorts <- Resource.eval(List.fill(3)(freeLocalPort).sequence)
      peers = List(
        PeerNode("node-1", "127.0.0.1", peerPorts(0)),
        PeerNode("node-2", "127.0.0.1", peerPorts(1)),
        PeerNode("node-3", "127.0.0.1", peerPorts(2))
      )
      node1 <- TestNode.resource(ClusterConfig("node-1", "127.0.0.1", apiPorts(0), peers, root.resolve("node-1").toString))
      node2 <- TestNode.resource(ClusterConfig("node-2", "127.0.0.1", apiPorts(1), peers, root.resolve("node-2").toString))
      node3 <- TestNode.resource(ClusterConfig("node-3", "127.0.0.1", apiPorts(2), peers, root.resolve("node-3").toString))
    yield TestCluster(List(node1, node2, node3))


  private def freeLocalPort: IO[Int] =
    IO.blocking {
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private final case class TestCluster(nodes: List[TestNode]):
    def otherThan(nodeId: String): List[TestNode] = nodes.filterNot(_.id == nodeId)

    def awaitLeader: IO[TestNode] =
      poll(nodes.findM(_.role.map(_ == NodeRole.Leader)))

    def awaitLeaseVisible(node: TestNode, tenantId: TenantId, resourceId: ResourceId): IO[GetLeaseResult] =
      pollResult(node.getLease(tenantId, resourceId).map(_.toOption.filter(_.found)))

    private def poll(value: IO[Option[TestNode]]): IO[TestNode] =
      value.flatMap {
        case Some(node) => IO.pure(node)
        case None       => IO.sleep(200.millis) *> poll(value)
      }

    private def pollResult[A](value: IO[Option[A]]): IO[A] =
      value.flatMap {
        case Some(result) => IO.pure(result)
        case None         => IO.sleep(200.millis) *> pollResult(value)
      }

  private object TestNode:
    def resource(config: ClusterConfig): Resource[IO, TestNode] =
      Resource.make {
        for
          currentRef <- Ref.of[IO, Option[(LeaseService[IO], RaftNode[IO], IO[Unit])]](None)
          node = TestNode(config.nodeId, config, currentRef)
          _ <- node.start
        yield node
      }(_.stop)

  private final case class TestNode(id: String, config: ClusterConfig, currentRef: Ref[IO, Option[(LeaseService[IO], RaftNode[IO], IO[Unit])]]):
    def start: IO[Unit] =
      currentRef.get.flatMap {
        case Some(_) => IO.unit
        case None =>
          for
            persistence <- RaftPersistence.fileBacked[IO](config.dataDir)
            allocated <- RaftNode.resource[IO](config, persistence).allocated
            (raft, release) = allocated
            service = LeaseService.replicated[IO](raft, Clock.system[IO])
            _ <- currentRef.set(Some((service, raft, release)))
          yield ()
      }

    def stop: IO[Unit] =
      currentRef.getAndSet(None).flatMap(_.traverse_(_._3))

    def service: IO[LeaseService[IO]] = currentRef.get.flatMap(_.map(_._1).liftTo[IO](new IllegalStateException(s"node $id is stopped")))
    def role: IO[NodeRole] = currentRef.get.flatMap(_.map(_._2.role).getOrElse(IO.pure(NodeRole.Follower)))
    def acquire(request: AcquireRequest): IO[Either[ServiceError, AcquireResult]] = service.flatMap(_.acquire(request))
    def getLease(tenantId: TenantId, resourceId: ResourceId): IO[Either[ServiceError, GetLeaseResult]] = service.flatMap(_.getLease(tenantId, resourceId))
