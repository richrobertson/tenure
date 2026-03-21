# Milestone 2 local clustered demo

Milestone 2 moves the prototype from a single in-memory node to a small static-config cluster with one shared Raft group. The implementation keeps mutating lease commands (`Acquire`, `Renew`, `Release`) behind an explicit Raft boundary, persists local term/vote/log metadata to disk, and uses simple TCP peer RPCs without multiplexing. This demo document describes the local run flow for the now-complete milestone.

## What this demo proves

- static bootstrap uses explicit node IDs and concrete `127.0.0.1:port` endpoints
- followers return `NOT_LEADER` for reads and writes in v1
- the elected leader commits mutating commands through one shared replicated log
- local persisted metadata and log entries replay on restart
- peer communication is direct TCP request/response with no transport multiplexing layer

## Example node configs

Create three config files like the following.

### `node-1.json`

```json
{
  "nodeId": "node-1",
  "apiHost": "127.0.0.1",
  "apiPort": 8081,
  "dataDir": "/tmp/tenure/node-1",
  "peers": [
    { "nodeId": "node-1", "host": "127.0.0.1", "port": 19091, "apiHost": "127.0.0.1", "apiPort": 8081 },
    { "nodeId": "node-2", "host": "127.0.0.1", "port": 19092, "apiHost": "127.0.0.1", "apiPort": 8082 },
    { "nodeId": "node-3", "host": "127.0.0.1", "port": 19093, "apiHost": "127.0.0.1", "apiPort": 8083 }
  ]
}
```

Create `node-2.json` and `node-3.json` by changing `nodeId`, `apiPort`, and `dataDir`. Peer endpoints stay explicit and DNS-free.

## Run three nodes

```bash
sbt "run -- node-1.json"
sbt "run -- node-2.json"
sbt "run -- node-3.json"
```

## Drive the leader

Send a write to each node until one accepts the write and the others return `NOT_LEADER`.

```bash
curl -s localhost:8081/v1/leases/acquire -X POST -H 'content-type: application/json' -d '{"tenant_id":"tenant-a","resource_id":"resource-1","holder_id":"holder-1","ttl_seconds":30,"request_id":"req-1"}'
```

A follower returns a conflict response with `code=NOT_LEADER` and a `leader_hint` when known.

## Restart / replay

Stop a node, then start it again with the same config and `dataDir`. The node reloads `metadata.json` and `log.jsonl`, replays committed log entries, and rebuilds local materialized lease state.
