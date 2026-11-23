#!/usr/bin/env bash
# End-to-end auth check of one CAMARA service, with real tokens from a Keycloak.
#
#   auth-test.sh <service> [--two-legged-only]
#
# <service> is a file in cases/: reachability, roaming, locationverification, deviceswap, simswap,
# qosprofiles, qualityondemand. Needs bash, curl and jq; the auth-test image in compose.yaml has them,
# so from any shell (PowerShell included):
#
#   docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
#
# Needs a Keycloak (dev/keycloak's, or any other with the API's scopes and a client-credentials client) and the
# service running with AUTH_ENABLED=true and AUTH_ISSUER_URI set to that Keycloak's realm. TEP isn't needed:
# without it, calls that get past authentication end in 503 UNAVAILABLE, which counts as a pass. This checks
# the authentication, not the API.
#
# Settings, from the environment (defaults: dev/keycloak and a service on its default ports):
#   KEYCLOAK_URL        http://localhost:8180
#   REALM               camara
#   CLIENT_ID           acme-backend          two-legged consumer, client credentials
#   CLIENT_SECRET       acme-backend-secret
#   USER_CLIENT_ID      subscriber-app        three-legged consumer
#   SUBSCRIBER          alice                 the subscriber who logs in (password grant)
#   SUBSCRIBER_PASSWORD alice
#   API_ROOT            http://localhost:9091
#   MANAGEMENT_URL      http://localhost:8081
#   PHONE_NUMBER        +306912345678
#   TWO_LEGGED_ONLY     false                 or --two-legged-only: for a Keycloak with no three-legged client
#
# Exits non-zero when a check fails.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    echo "Usage: $0 <service> [--two-legged-only]" >&2
    echo "Services: $(cd "$HERE/cases" && ls *.sh | sed 's/\.sh$//' | tr '\n' ' ')" >&2
    exit 2
}

SERVICE="${1:-}"
[[ -n "$SERVICE" && -f "$HERE/cases/$SERVICE.sh" ]] || usage
shift
TWO_LEGGED_ONLY="${TWO_LEGGED_ONLY:-false}"
for arg in "$@"; do
    case "$arg" in
        --two-legged-only) TWO_LEGGED_ONLY=true ;;
        *) usage ;;
    esac
done

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="${REALM:-camara}"
CLIENT_ID="${CLIENT_ID:-acme-backend}"
CLIENT_SECRET="${CLIENT_SECRET:-acme-backend-secret}"
USER_CLIENT_ID="${USER_CLIENT_ID:-subscriber-app}"
SUBSCRIBER="${SUBSCRIBER:-alice}"
SUBSCRIBER_PASSWORD="${SUBSCRIBER_PASSWORD:-alice}"
API_ROOT="${API_ROOT:-http://localhost:9091}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://localhost:8081}"
PHONE_NUMBER="${PHONE_NUMBER:-+306912345678}"
TOKEN_URL="$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token"

if [[ -t 1 ]]; then
    GREEN=$'\e[32m' RED=$'\e[31m' YELLOW=$'\e[33m' RESET=$'\e[0m'
else
    GREEN='' RED='' YELLOW='' RESET=''
fi

die() {
    echo "${RED}$*${RESET}" >&2
    exit 1
}

for tool in curl jq base64; do
    command -v "$tool" >/dev/null || die "$tool is needed; or run this through docker compose (see the top of $0)"
done

three_legged() {
    [[ "$TWO_LEGGED_ONLY" != true ]]
}

# token field=value...: an access token for that form, or a stop with Keycloak's own answer, which says what to fix
# (invalid_client, invalid_scope, HTTPS required)
token() {
    local args=() field response status
    for field in "$@"; do
        args+=(--data-urlencode "$field")
    done
    response=$(curl -sS -w '\n%{http_code}' "${args[@]}" "$TOKEN_URL") || die "Could not reach $TOKEN_URL"
    status="${response##*$'\n'}"
    [[ "$status" == 200 ]] || die "Could not get a token from $TOKEN_URL: ${response%$'\n'*}"
    jq -r .access_token <<<"${response%$'\n'*}"
}

# two_legged_token [scope...]: client credentials; no scope asks for none
two_legged_token() {
    local scope=("grant_type=client_credentials" "client_id=$CLIENT_ID" "client_secret=$CLIENT_SECRET")
    [[ $# -gt 0 ]] && scope+=("scope=$*")
    token "${scope[@]}"
}

# three_legged_token scope...: the password grant only stands in for the subscriber logging in; real three-legged
# apps use a browser flow
three_legged_token() {
    token "grant_type=password" "client_id=$USER_CLIENT_ID" "username=$SUBSCRIBER" "password=$SUBSCRIBER_PASSWORD" \
        "scope=openid $*"
}

# claims jwt: its payload as JSON
claims() {
    local payload="${1#*.}"
    payload="${payload%%.*}"
    payload=$(tr '_-' '/+' <<<"$payload")
    case $((${#payload} % 4)) in
        2) payload+='==' ;;
        3) payload+='=' ;;
    esac
    base64 -d <<<"$payload" 2>/dev/null
}

show_issuer() {
    echo "  issuer: $(claims "$1" | jq -r '.iss')  (must equal the service's AUTH_ISSUER_URI)"
}

show_claims() {
    local azp scope phone
    IFS='|' read -r azp scope phone < <(claims "$2" | jq -r '[.azp // "", .scope // "", .phone_number // ""] | join("|")')
    printf '  %-36s azp=%s  scope="%s"  phone_number=%s\n' "$1" "$azp" "$scope" "$phone"
}

# granted_unasked jwt scope...: why a 403 can't be tested with this token, if it carries one of these scopes. A
# client that has a scope as Default, not Optional, gets it without asking for it.
granted_unasked() {
    local jwt="$1" scope
    shift
    for scope in "$@"; do
        if claims "$jwt" | jq -e --arg s "$scope" '(.scope // "") | split(" ") | index($s) != null' >/dev/null; then
            echo "client '$CLIENT_ID' gets $scope without asking for it (assigned as Default, not Optional)"
            return
        fi
    done
}

CHECKS=0
FAILED=0
SKIPPED=0

# check name method url token body statuses [code] [skip-reason]
#   statuses: one or more, space-separated; code: the ErrorInfo code expected too; a skip reason skips the check
check() {
    local name="$1" method="$2" url="$3" jwt="$4" body="$5" statuses="$6" code="${7:-}" skip="${8:-}"
    CHECKS=$((CHECKS + 1))
    if [[ -n "$skip" ]]; then
        SKIPPED=$((SKIPPED + 1))
        printf '%sSKIP  %-40s %s%s\n' "$YELLOW" "$name" "$skip" "$RESET"
        return
    fi
    local args=(-s -o "$RESPONSE_FILE" -w '%{http_code}' -X "$method") status got_code
    [[ -n "$jwt" ]] && args+=(-H "Authorization: Bearer $jwt")
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
    status=$(curl "${args[@]}" "$url") || die "Could not reach $url. Is the service running?"
    got_code=$(jq -r '.code // empty' "$RESPONSE_FILE" 2>/dev/null || true)

    local ok=false expected="${statuses// //}"
    if [[ " $statuses " == *" $status "* && (-z "$code" || "$code" == "$got_code") ]]; then
        ok=true
    fi
    [[ -n "$code" ]] && expected="$statuses $code"
    if $ok; then
        printf '%sPASS  %-40s got %s %-24s expected %s%s\n' "$GREEN" "$name" "$status" "$got_code" "$expected" "$RESET"
    else
        FAILED=$((FAILED + 1))
        printf '%sFAIL  %-40s got %s %-24s expected %s%s\n' "$RED" "$name" "$status" "$got_code" "$expected" "$RESET"
    fi
}

RESPONSE_FILE=$(mktemp)
trap 'rm -f "$RESPONSE_FILE"' EXIT

echo "Tokens from $TOKEN_URL"
# The case file sets SPEC (the spec file the service serves) and runs its checks
SPEC=''
# shellcheck source=/dev/null
source "$HERE/cases/$SERVICE.sh"

check 'Health probe needs no token' GET "$MANAGEMENT_URL/actuator/health/readiness" '' '' 200
check 'Spec file needs no token' GET "$API_ROOT/$SPEC" '' '' 200

echo
skip_note=''
[[ $SKIPPED -gt 0 ]] && skip_note=" ($SKIPPED skipped)"
if [[ $FAILED -gt 0 ]]; then
    echo "${RED}$FAILED check(s) failed$skip_note${RESET}"
    exit 1
fi
echo "${GREEN}All $((CHECKS - SKIPPED)) checks passed$skip_note${RESET}"
