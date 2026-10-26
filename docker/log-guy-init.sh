#!/bin/sh
# Runs before the server in compose.yml. Creates /etc/log-guy/application.yml on the first start with an admin user
# and an ingest token for the agent of the application mir, then sets up the collection with "log-guy solr init".
# Solr URLs and replication factor come from the environment in compose.yml.
set -eu

config=/etc/log-guy/application.yml
if [ ! -e "$config" ]; then
  password="${LOG_GUY_ADMIN_PASSWORD:-$(head -c 18 /dev/urandom | base64 | tr -d '/+=')}"
  hash=$(printf '%s\n' "$password" | log-guy password hash --name admin | sed -n 's/.*password-hash: //p')
  tokens=$(log-guy token create --kind ingest --name agent-mir --host mir --apps mir)
  token=$(printf '%s\n' "$tokens" | sed -n 's/^Token, shown only now: //p')
  token_hash=$(printf '%s\n' "$tokens" | sed -n 's/.*hash: //p')
  if [ -z "$hash" ] || [ -z "$token" ] || [ -z "$token_hash" ]; then
    echo "creating $config failed" >&2
    exit 1
  fi
  cat > "$config.tmp" <<EOF
log-guy:
  apps:
    mir:
      parser: mycore-log4j
  users:
    - name: admin
      password-hash: $hash
      grants:
        - apps: ["*"]
          rights: [admin]
  tokens:
    - name: agent-mir
      kind: ingest
      host: mir
      apps: [mir]
      hash: $token_hash
EOF
  chmod 644 "$config.tmp"
  mv "$config.tmp" "$config"
  echo "created $config"
  echo "log-guy user admin, password: $password"
  echo "token for the agent of mir (host mir), shown only now: $token"
fi

exec log-guy solr init
