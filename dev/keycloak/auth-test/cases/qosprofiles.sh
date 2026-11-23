# QoS Profiles: two operations, one scope. Sourced by auth-test.sh.
SPEC=qos-profiles.yaml
retrieve="$API_ROOT/qos-profiles/v1/retrieve-qos-profiles"
profile="$API_ROOT/qos-profiles/v1/qos-profiles/${PROFILE_NAME:-voice}"
scope=qos-profiles:read
# Past auth: the request reached the API logic; 200/404 need TEP, 503 is TEP being absent
past_auth='200 404 503'

two_legged=$(two_legged_token "$scope")
no_scope=$(two_legged_token)
show_issuer "$two_legged"
show_claims 'two-legged' "$two_legged"
show_claims 'two-legged, no scope' "$no_scope"
if three_legged; then
    three_legged=$(three_legged_token "$scope")
    show_claims "three-legged ($SUBSCRIBER)" "$three_legged"
fi
echo

no_scope_skip=$(granted_unasked "$no_scope" "$scope")
device='{"device":{"phoneNumber":"'"$PHONE_NUMBER"'"}}'
check 'No token, retrieve' POST "$retrieve" '' '{}' 401 UNAUTHENTICATED
check 'No token, get by name' GET "$profile" '' '' 401 UNAUTHENTICATED
check 'Not a JWT' POST "$retrieve" not-a-jwt '{}' 401 UNAUTHENTICATED
check 'Two-legged, without the scope, retrieve' POST "$retrieve" "$no_scope" '{}' 403 PERMISSION_DENIED "$no_scope_skip"
check 'Two-legged, without the scope, get' GET "$profile" "$no_scope" '' 403 PERMISSION_DENIED "$no_scope_skip"
check 'Two-legged, no filters' POST "$retrieve" "$two_legged" '{}' "$past_auth"
check 'Two-legged, device in body' POST "$retrieve" "$two_legged" "$device" "$past_auth"
check 'Two-legged, get by name' GET "$profile" "$two_legged" '' "$past_auth"
if three_legged; then
    check 'Three-legged, no device' POST "$retrieve" "$three_legged" '{}' "$past_auth"
    check 'Three-legged, device in body' POST "$retrieve" "$three_legged" "$device" 422 UNNECESSARY_IDENTIFIER
    check 'Three-legged, get by name' GET "$profile" "$three_legged" '' "$past_auth"
fi
