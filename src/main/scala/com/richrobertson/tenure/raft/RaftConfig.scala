package com.richrobertson.tenure.raft

import io.circe.Codec
import io.circe.generic.semiauto.*

final case class PeerNode(nodeId: String, host: String, port: Int) derives CanEqual:
  def endpoint: String = s"$host:$port"

final case class ClusterConfig(nodeId: String, apiHost: String, apiPort: Int, peers: List[PeerNode], dataDir: String) derives CanEqual:
  def raftPeers: List[PeerNode] = peers.sortBy(_.nodeId)
  def localPeer: PeerNode = raftPeers.find(_.nodeId == nodeId).getOrElse(throw new IllegalArgumentException(s"missing local node $nodeId in peers"))
  def majority: Int = (raftPeers.size / 2) + 1
  def leaderHintEndpoint(leaderId: String): Option[String] = raftPeers.find(_.nodeId == leaderId).map(_.endpoint)

object ClusterConfig:
  given Codec[PeerNode] = deriveCodec
  given Codec[ClusterConfig] = deriveCodec
