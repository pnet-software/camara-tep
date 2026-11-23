# Quality-On-Demand: five operations, each with its own scope and no other. Besides 401 and 403, each operation must
# open with its own scope and not with a token carrying the four others. Sourced by auth-test.sh.
#
# With TEP, the create calls ask for real sessions of QOS_DURATION seconds, which end on their own. Whatever TEP
# then answers (a session, an unknown device or profile, a device without a data session) counts as past auth.
SPEC=quality-on-demand.yaml
base="$API_ROOT/quality-on-demand/v1"
# Any UUID: past auth, an unknown session is a 404
session_id="${SESSION_ID:-3fa85f64-5717-4562-b3fc-2c963f66afa6}"
sessions="$base/sessions"
session="$sessions/$session_id"
retrieve="$base/retrieve-sessions"
# Past auth: the request reached the API logic. 200/201/204 and 404 (an unknown session or device) need TEP, 400 is
# a profile TEP doesn't have or a duration it doesn't allow, 409 a session for that device and flow already there
# from an earlier run, 422 a device without a data session or a profile that isn't active, 503 TEP or Redis absent
past_auth='200 201 204 400 404 409 422 503'

device_field='"device":{"phoneNumber":"'"$PHONE_NUMBER"'"}'
session_fields='"applicationServer":{"ipv4Address":"198.51.100.0/24"},"qosProfile":"'"${QOS_PROFILE:-QOS_E}"'","duration":'"${QOS_DURATION:-60}"
create_body="{$device_field,$session_fields}"
create_no_device="{$session_fields}"
retrieve_body="{$device_field}"

# name|scope|method|url|body, one line per operation
operations="create|quality-on-demand:sessions:create|POST|$sessions|$create_body
get|quality-on-demand:sessions:read|GET|$session|
delete|quality-on-demand:sessions:delete|DELETE|$session|
extend|quality-on-demand:sessions:update|POST|$session/extend|{\"requestedAdditionalDuration\":60}
retrieve-sessions|quality-on-demand:sessions:retrieve-by-device|POST|$retrieve|$retrieve_body"
all_scopes=$(cut -d'|' -f2 <<<"$operations" | tr '\n' ' ')

no_scope=$(two_legged_token)
show_issuer "$no_scope"
show_claims 'two-legged, no scope' "$no_scope"
# Per operation, in order: a token with only its scope, one with every other operation's
own=()
others=()
while IFS='|' read -r name op_scope _; do
    own+=("$(two_legged_token "$op_scope")")
    others+=("$(two_legged_token ${all_scopes//$op_scope/})")
    show_claims "two-legged, $name only" "${own[${#own[@]} - 1]}"
done <<<"$operations"
if three_legged; then
    three_legged=$(three_legged_token "$all_scopes")
    show_claims "three-legged ($SUBSCRIBER)" "$three_legged"
fi
echo

while IFS='|' read -r name _ method url body; do
    check "No token, $name" "$method" "$url" '' "$body" 401 UNAUTHENTICATED
done <<<"$operations"
check 'Not a JWT' POST "$sessions" not-a-jwt "$create_body" 401 UNAUTHENTICATED
check 'Two-legged, without a scope' POST "$sessions" "$no_scope" "$create_body" 403 PERMISSION_DENIED \
    "$(granted_unasked "$no_scope" $all_scopes)"
i=0
while IFS='|' read -r name op_scope method url body; do
    check "$name, its own scope" "$method" "$url" "${own[$i]}" "$body" "$past_auth"
    # A client that gets this operation's scope unasked (Default, not Optional) opens it with the others' token too
    check "$name, every other scope" "$method" "$url" "${others[$i]}" "$body" 403 PERMISSION_DENIED \
        "$(granted_unasked "${others[$i]}" "$op_scope")"
    i=$((i + 1))
done <<<"$operations"
# own[0] is create's token, own[4] retrieve-sessions'
check 'Two-legged create, no device' POST "$sessions" "${own[0]}" "$create_no_device" 422 MISSING_IDENTIFIER
check 'Two-legged retrieve, no device' POST "$retrieve" "${own[4]}" '{}' 422 MISSING_IDENTIFIER
if three_legged; then
    check 'Three-legged create, no device' POST "$sessions" "$three_legged" "$create_no_device" "$past_auth"
    check 'Three-legged create, device in body' POST "$sessions" "$three_legged" "$create_body" 422 \
        UNNECESSARY_IDENTIFIER
    check 'Three-legged retrieve, no device' POST "$retrieve" "$three_legged" '{}' "$past_auth"
    check 'Three-legged retrieve, device in body' POST "$retrieve" "$three_legged" "$retrieve_body" 422 \
        UNNECESSARY_IDENTIFIER
fi
