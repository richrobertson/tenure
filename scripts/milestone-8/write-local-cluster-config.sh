#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: scripts/milestone-8/write-local-cluster-config.sh <root-dir> [raft-port-base] [api-port-base]" >&2
  echo "example: scripts/milestone-8/write-local-cluster-config.sh /tmp/tenure-m8 9400 9500" >&2
  exit 1
fi

ROOT_DIR=$1
RAFT_BASE=${2:-9400}
API_BASE=${3:-9500}

CONFIG_DIR="${ROOT_DIR}/config"
DATA_DIR="${ROOT_DIR}/data"

mkdir -p "${CONFIG_DIR}" "${DATA_DIR}"

peer_json() {
  local node_id=$1
  local raft_port=$2
  local api_port=$3
  printf '    {"nodeId":"%s","host":"127.0.0.1","port":%s,"apiHost":"127.0.0.1","apiPort":%s}' "${node_id}" "${raft_port}" "${api_port}"
}

for idx in 1 2 3; do
  node_id="node-${idx}"
  mkdir -p "${DATA_DIR}/${node_id}"
done

peers=(
  "$(peer_json node-1 $((RAFT_BASE + 1)) $((API_BASE + 1)))"
  "$(peer_json node-2 $((RAFT_BASE + 2)) $((API_BASE + 2)))"
  "$(peer_json node-3 $((RAFT_BASE + 3)) $((API_BASE + 3)))"
)

peers_json=$(printf '%s,\n' "${peers[@]}")
peers_json=${peers_json%,\\n}
peers_json=${peers_json%,}

for idx in 1 2 3; do
  node_id="node-${idx}"
  api_port=$((API_BASE + idx))
  cat > "${CONFIG_DIR}/${node_id}.json" <<EOF
{
  "nodeId": "${node_id}",
  "apiHost": "127.0.0.1",
  "apiPort": ${api_port},
  "peers": [
${peers_json}
  ],
  "dataDir": "${DATA_DIR}/${node_id}"
}
EOF
done

echo "wrote cluster configs under ${CONFIG_DIR}"
echo
echo "start the three nodes in separate terminals:"
for idx in 1 2 3; do
  echo "  sbt \"run -- ${CONFIG_DIR}/node-${idx}.json\""
done
echo
echo "api endpoints:"
for idx in 1 2 3; do
  echo "  node-${idx}: http://127.0.0.1:$((API_BASE + idx))"
done
