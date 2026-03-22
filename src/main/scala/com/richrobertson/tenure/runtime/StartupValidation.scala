/**
 * Startup-time validation for clustered daemon configuration.
 *
 * The goal of this package is to reject obviously unsafe or contradictory runtime state before the daemon
 * starts binding sockets or touching persistent state.
 */
package com.richrobertson.tenure.runtime

import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.comcast.ip4s.{Ipv4Address, Ipv6Address}
import com.richrobertson.tenure.raft.{ClusterConfig, PeerNode}

import java.nio.file.{Files, InvalidPathException, Path, Paths}

/** Validation helpers for cluster config, endpoint sanity, and data-directory readiness. */
object StartupValidation:
  private val UnspecifiedIpv4 = Ipv4Address.fromString("0.0.0.0").get
  private val UnspecifiedIpv6 = Ipv6Address.fromString("::").get

  /**
   * Validates a clustered config and normalizes the data directory path.
   *
   * This is the main entry point used during daemon startup.
   */
  def validateClusteredConfig[F[_]: Sync](config: ClusterConfig): F[ClusterConfig] =
    for
      validatedConfig <- Sync[F].fromEither(validateConfig(config))
      validatedPath <- validateDataDir(validatedConfig.dataDir, context = s"dataDir for node ${validatedConfig.nodeId}")
    yield validatedConfig.copy(dataDir = validatedPath.toString)

  /**
   * Validates and prepares a local data directory path.
   *
   * The path must parse, be directory-shaped, be creatable if absent, and be writable.
   */
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
    }

  /**
   * Pure validation for static clustered configuration.
   *
   * This checks host, port, node identity, local-peer invariants, and uniqueness constraints without
   * touching the filesystem.
   */
  def validateConfig(config: ClusterConfig): Either[IllegalArgumentException, ClusterConfig] =
    for
      _ <- nonEmpty("nodeId", config.nodeId)
      _ <- noSurroundingWhitespace("nodeId", config.nodeId)
      _ <- nonEmpty("dataDir", config.dataDir)
      _ <- noSurroundingWhitespace("config apiHost", config.apiHost)
      _ <- ensure(config.peers.nonEmpty, "peers must contain at least one node")
      _ <- config.peers.traverse_(validatePeer)
      localPeers = config.peers.filter(_.nodeId == config.nodeId)
      _ <- ensure(localPeers.size == 1, s"expected exactly one peer entry for local node '${config.nodeId}', found ${localPeers.size}")
      localPeer = localPeers.head
      _ <- ensure(normalizeHost(localPeer.apiHost) == normalizeHost(config.apiHost), s"config apiHost '${config.apiHost}' must match local peer apiHost '${localPeer.apiHost}'")
      _ <- ensure(localPeer.apiPort == config.apiPort, s"config apiPort '${config.apiPort}' must match local peer apiPort '${localPeer.apiPort}'")
      _ <- ensureUnique(config.peers.map(_.nodeId), "peer node ids")
      _ <- ensureUnique(config.peers.map(peer => endpointKey(peer.host, peer.port)), "peer raft endpoints")
      _ <- ensureUnique(config.peers.map(peer => endpointKey(peer.apiHost, peer.apiPort)), "peer API endpoints")
      _ <- config.peers.traverse_ { peer =>
        ensure(peer.port != peer.apiPort, s"peer '${peer.nodeId}' must use different raft and API ports because v1 does not multiplex transport")
      }
    yield config

  /** Validates one peer entry. */
  private def validatePeer(peer: PeerNode): Either[IllegalArgumentException, Unit] =
    for
      _ <- nonEmpty("peer.nodeId", peer.nodeId)
      _ <- noSurroundingWhitespace("peer.nodeId", peer.nodeId)
      _ <- noSurroundingWhitespace(s"peer '${peer.nodeId}' raft host", peer.host)
      _ <- noSurroundingWhitespace(s"peer '${peer.nodeId}' apiHost", peer.apiHost)
      _ <- ensure(isExplicitHost(peer.host), s"peer '${peer.nodeId}' raft host '${peer.host}' must be an explicit IP or localhost; DNS names are not supported in v1")
      _ <- ensure(isExplicitHost(peer.apiHost), s"peer '${peer.nodeId}' apiHost '${peer.apiHost}' must be an explicit IP or localhost; DNS names are not supported in v1")
      _ <- validPort(peer.port, s"peer '${peer.nodeId}' raft port")
      _ <- validPort(peer.apiPort, s"peer '${peer.nodeId}' API port")
    yield ()

  /** Requires a non-empty string after trimming. */
  private def nonEmpty(label: String, value: String): Either[IllegalArgumentException, Unit] =
    ensure(value.trim.nonEmpty, s"$label must be non-empty")

  /** Requires a valid TCP/HTTP port range. */
  private def validPort(port: Int, label: String): Either[IllegalArgumentException, Unit] =
    ensure(port >= 1 && port <= 65535, s"$label must be between 1 and 65535, found $port")

  /** Rejects leading or trailing whitespace for identity-critical fields. */
  private def noSurroundingWhitespace(label: String, value: String): Either[IllegalArgumentException, Unit] =
    ensure(value == value.trim, s"$label must not include leading or trailing whitespace")

  /** Requires all values in a list to be unique. */
  private def ensureUnique(values: List[String], label: String): Either[IllegalArgumentException, Unit] =
    val duplicates = values.groupBy(identity).collect { case (value, entries) if entries.size > 1 => value }.toList.sorted
    ensure(duplicates.isEmpty, s"$label must be unique; duplicates: ${duplicates.mkString(", ")}")

  /** Converts a boolean guard into an [[IllegalArgumentException]]-based validation result. */
  private def ensure(condition: Boolean, message: => String): Either[IllegalArgumentException, Unit] =
    Either.cond(condition, (), new IllegalArgumentException(message))

  /** Returns true for explicit IP literals and `localhost`, but not wildcard or DNS names. */
  private def isExplicitHost(raw: String): Boolean =
    val normalized = raw.trim.toLowerCase
    normalized == "localhost" ||
    Ipv4Address.fromString(normalized).exists(_ != UnspecifiedIpv4) ||
    Ipv6Address.fromString(normalized).exists(_ != UnspecifiedIpv6)

  /** Canonical endpoint key used for uniqueness checks. */
  private def endpointKey(host: String, port: Int): String =
    s"${normalizeHost(host)}:$port"

  /** Normalizes host values for comparison and duplicate detection. */
  private def normalizeHost(raw: String): String =
    val normalized = raw.trim.toLowerCase
    val loopbackNormalized =
      if normalized == "localhost" then "127.0.0.1"
      else normalized
    Ipv4Address.fromString(loopbackNormalized).map(_.toString).orElse(Ipv6Address.fromString(loopbackNormalized).map(_.toString)).getOrElse(loopbackNormalized)

  /** Detects characters that make a path invalid before `Paths.get` is attempted. */
  private def containsInvalidPathChar(raw: String): Boolean =
    raw.exists(_ == '\u0000')
