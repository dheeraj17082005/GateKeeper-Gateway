#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

management=http://127.0.0.1:8081
gateway=http://127.0.0.1:8080
suffix="$(openssl rand -hex 5)"
slug="demo-$suffix"
tenant_user="tenant-$suffix"
tenant_password="$(openssl rand -hex 24)"
admin_password="${GATEKEEPER_ADMIN_PASSWORD:-$(sed -n 's/^GATEKEEPER_ADMIN_PASSWORD=//p' .env)}"
: "${admin_password:?Set GATEKEEPER_ADMIN_PASSWORD}"

login() {
    jq -n --arg username "$1" --arg password "$2" '{username:$username,password:$password}' |
        curl -fsS --max-time 5 -H 'Content-Type: application/json' --data-binary @- \
            "$management/api/auth/login" | jq -er .token
}

admin_token=""
for attempt in {1..60}; do
    if admin_token="$(login platform-admin "$admin_password" 2>/dev/null)"; then
        break
    fi
    sleep 2
done
: "${admin_token:?Platform login failed. Check docker compose logs api-management}"

management_request() {
    local method="$1" path="$2" token="$3" json="$4"
    curl -fsS --max-time 10 -X "$method" "$management$path" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$json"
}

organization_id="$(management_request POST /api/platform/organizations "$admin_token" \
    "$(jq -n --arg slug "$slug" '{name:("Gatekeeper smoke " + $slug),slug:$slug}')" | jq -er .id)"
management_request PATCH "/api/platform/organizations/$organization_id/status" "$admin_token" \
    '{"status":"ACTIVE"}' >/dev/null
management_request POST "/api/platform/organizations/$organization_id/tenant-admin" "$admin_token" \
    "$(jq -n --arg username "$tenant_user" --arg password "$tenant_password" \
        '{username:$username,password:$password,enabled:true}')" >/dev/null
tenant_token="$(login "$tenant_user" "$tenant_password")"

assert_status() {
    local expected="$1"
    shift
    local actual
    actual="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$@")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'Expected HTTP %s, received %s\n' "$expected" "$actual" >&2
        exit 1
    fi
}

assert_status 401 "$management/api/platform/organizations"
assert_status 401 "$management/api/auth/login" -H 'Content-Type: application/json' \
    --data '{"username":"platform-admin","password":"invalid-smoke-password"}'
assert_status 403 "$management/api/platform/organizations" -H "Authorization: Bearer $tenant_token"
printf '%s\n' 'PASS authentication and tenant role isolation'
management_request POST /api/tenant/routes "$tenant_token" \
    "$(jq -n --arg path "/$slug/catalog" '{
        routeId:"demo-catalog", name:"Gatekeeper Demo Catalog", pathPattern:$path,
        targetBaseUrl:"http://demo-backend", targetPathPrefix:"/catalog.json",
        responseTimeoutMs:3000, requestsPerMinute:30, allowedMethods:["GET"], active:true,
        headerRules:[{direction:"RESPONSE", action:"ADD", headerName:"X-Gatekeeper-Demo", headerValue:"catalog"}]
    }')" >/dev/null

response="$(mktemp)"
trap 'rm -f "$response"' EXIT
correlation_id="gatekeeper-smoke-$suffix"
for attempt in {1..25}; do
    if curl -fsS --max-time 5 -D - -o "$response" -H "X-Correlation-ID: $correlation_id" \
        "$gateway/$slug/catalog?source=smoke" 2>/dev/null |
        tr -d '\r' | awk -v correlation="$correlation_id" '
            tolower($0) == "x-gatekeeper-demo: catalog" { rule=1 }
            tolower($0) == "x-correlation-id: " correlation { id=1 }
            END { exit !(rule && id) }' &&
        jq -e '.service == "gatekeeper-demo-catalog" and .items[0].id == "starter"' "$response" >/dev/null; then
        printf 'PASS GET /%s/catalog through Gatekeeper\n' "$slug"
        cat "$response"
        exit 0
    fi
    sleep 2
done
printf '%s\n' 'FAIL: Did not receive the demo backend payload and gateway headers.' >&2
exit 1
