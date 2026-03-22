/**
 * Static cluster configuration models for the Raft runtime.
 *
 * v1 intentionally uses explicit node IDs and concrete endpoints rather than dynamic discovery. These types
 * are the configuration surface shared by startup validation, the daemon entrypoint, and the Raft node.
 */
package com.richrobertson.tenure.raft

import io.circe.Codec
import io.circe.generic.semiauto.*

/** One statically configured cluster peer. */
final case class PeerNode(nodeId: String, host: String, port: Int, apiHost: String, apiPort: Int) derives CanEqual:
  private def formatEndpoint(host: String, port: Int): String =
    if host.contains(":") && !host.startsWith("[") && !host.endsWith("]") then s"[$host]:$port"
    else s"$host:$port"

  /** `host:port` endpoint for Raft traffic. */
  def endpoint: String = formatEndpoint(host, port)
  /** `apiHost:apiPort` endpoint exposed to clients. */
  def apiEndpoint: String = formatEndpoint(apiHost, apiPort)

/**
 * Complete static config for one node.
 *
 * The same value carries both the cluster membership list and the local node identity.
 */
final case class ClusterConfig(nodeId: String, apiHost: String, apiPort: Int, peers: List[PeerNode], dataDir: String) derives CanEqual:
  /** Peer list sorted into a stable node-ID order. */
  def raftPeers: List[PeerNode] = peers.sortBy(_.nodeId)
  /** The peer entry representing the current node. */
  def localPeer: PeerNode = raftPeers.find(_.nodeId == nodeId).getOrElse(throw new IllegalArgumentException(s"missing local node $nodeId in peers"))
  /** Majority quorum size for this configured peer set. */
  def majority: Int = (raftPeers.size / 2) + 1
  /** API endpoint hint for a known leader ID, if present in config. */
  def leaderHintEndpoint(leaderId: String): Option[String] = raftPeers.find(_.nodeId == leaderId).map(_.apiEndpoint)

/** Circe codecs for [[PeerNode]] and [[ClusterConfig]]. */
object ClusterConfig:
  given Codec[PeerNode] = deriveCodec
  given Codec[ClusterConfig] = deriveCodec
