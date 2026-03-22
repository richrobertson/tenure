package com.richrobertson.tenure.runtime

import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.comcast.ip4s.{Ipv4Address, Ipv6Address}
import com.richrobertson.tenure.raft.{ClusterConfig, PeerNode}

import java.nio.file.{Files, InvalidPathException, Path, Paths}

object StartupValidation:
  def validateClusteredConfig[F[_]: Sync](config: ClusterConfig): F[ClusterConfig] =
    for
      _ <- Sync[F].fromEither(validateConfig(config))
      _ <- validateDataDir(config.dataDir, context = s"dataDir for node ${config.nodeId}")
    yield config

  def validateDataDir[F[_]: Sync](rawPath: String, context: String): F[Path] =
    Sync[F].blocking {
      val trimmed = rawPath.trim
      if containsInvalidPathChar(rawPath) then
        throw new IllegalArgumentException(s"$context must be a valid path, found '$rawPath'")
      require(trimmed.nonEmpty, s"$context must be non-empty")
      val path =
        try Paths.get(trimmed)
        catch
          case _: InvalidPathException =>
            throw new IllegalArgumentException(s"$context must be a valid path, found '$rawPath'")

      if Files.exists(path) && !Files.isDirectory(path) then
        throw new IllegalArgumentException(s"$context must be a directory path, found file: $trimmed")

      Files.createDirectories(path)

      if !Files.isWritable(path) then
        throw new IllegalArgumentException(s"$context must be writable: $trimmed")

      path
    }.adaptError { case error: IllegalArgumentException => error }

  def validateConfig(config: ClusterConfig): Either[IllegalArgumentException, ClusterConfig] =
    for
      _ <- nonEmpty("nodeId", config.nodeId)
      _ <- nonEmpty("dataDir", config.dataDir)
      _ <- ensure(config.peers.nonEmpty, "peers must contain at least one node")
      _ <- config.peers.traverse_(validatePeer)
      localPeers = config.peers.filter(_.nodeId == config.nodeId)
      _ <- ensure(localPeers.size == 1, s"expected exactly one peer entry for local node '${config.nodeId}', found ${localPeers.size}")
      localPeer = localPeers.headOption.getOrElse(config.peers.head)
      _ <- ensure(localPeer.apiHost == config.apiHost, s"config apiHost '${config.apiHost}' must match local peer apiHost '${localPeer.apiHost}'")
      _ <- ensure(localPeer.apiPort == config.apiPort, s"config apiPort '${config.apiPort}' must match local peer apiPort '${localPeer.apiPort}'")
      _ <- ensureUnique(config.peers.map(_.nodeId), "peer node ids")
      _ <- ensureUnique(config.peers.map(_.endpoint), "peer raft endpoints")
      _ <- ensureUnique(config.peers.map(_.apiEndpoint), "peer API endpoints")
      _ <- config.peers.traverse_ { peer =>
        ensure(peer.port != peer.apiPort, s"peer '${peer.nodeId}' must use different raft and API ports because v1 does not multiplex transport")
      }
    yield config

  private def validatePeer(peer: PeerNode): Either[IllegalArgumentException, Unit] =
    for
      _ <- nonEmpty("peer.nodeId", peer.nodeId)
      _ <- ensure(isExplicitHost(peer.host), s"peer '${peer.nodeId}' raft host '${peer.host}' must be an explicit IP or localhost; DNS names are not supported in v1")
      _ <- ensure(isExplicitHost(peer.apiHost), s"peer '${peer.nodeId}' apiHost '${peer.apiHost}' must be an explicit IP or localhost; DNS names are not supported in v1")
      _ <- validPort(peer.port, s"peer '${peer.nodeId}' raft port")
      _ <- validPort(peer.apiPort, s"peer '${peer.nodeId}' API port")
    yield ()

  private def nonEmpty(label: String, value: String): Either[IllegalArgumentException, Unit] =
    ensure(value.trim.nonEmpty, s"$label must be non-empty")

  private def validPort(port: Int, label: String): Either[IllegalArgumentException, Unit] =
    ensure(port >= 1 && port <= 65535, s"$label must be between 1 and 65535, found $port")

  private def ensureUnique(values: List[String], label: String): Either[IllegalArgumentException, Unit] =
    val duplicates = values.groupBy(identity).collect { case (value, entries) if entries.size > 1 => value }.toList.sorted
    ensure(duplicates.isEmpty, s"$label must be unique; duplicates: ${duplicates.mkString(", ")}")

  private def ensure(condition: Boolean, message: => String): Either[IllegalArgumentException, Unit] =
    Either.cond(condition, (), new IllegalArgumentException(message))

  private def isExplicitHost(raw: String): Boolean =
    val normalized = raw.trim.toLowerCase
    normalized == "localhost" ||
    (normalized != "0.0.0.0" && Ipv4Address.fromString(normalized).isDefined) ||
    (normalized != "::" && Ipv6Address.fromString(normalized).isDefined)

  private def containsInvalidPathChar(raw: String): Boolean =
    raw.exists(_ == '\u0000')
