# Device Reachability Status: one operation, one scope. Sourced by auth-test.sh.
SPEC=device-reachability-status.yaml
url="$API_ROOT/device-reachability-status/v1/retrieve"
scope=device-reachability-status:read
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

device='{"device":{"phoneNumber":"'"$PHONE_NUMBER"'"}}'
check 'No token' POST "$url" '' "$device" 401 UNAUTHENTICATED
check 'Not a JWT' POST "$url" not-a-jwt "$device" 401 UNAUTHENTICATED
check 'Two-legged, without the scope' POST "$url" "$no_scope" "$device" 403 PERMISSION_DENIED \
    "$(granted_unasked "$no_scope" "$scope")"
check 'Two-legged, device in body' POST "$url" "$two_legged" "$device" "$past_auth"
check 'Two-legged, no device' POST "$url" "$two_legged" '{}' 422 MISSING_IDENTIFIER
if three_legged; then
    check 'Three-legged, no device' POST "$url" "$three_legged" '{}' "$past_auth"
    check 'Three-legged, device in body' POST "$url" "$three_legged" "$device" 422 UNNECESSARY_IDENTIFIER
fi
