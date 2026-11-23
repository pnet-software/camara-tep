# Location Verification: one operation, one scope. Sourced by auth-test.sh.
SPEC=location-verification.yaml
url="$API_ROOT/location-verification/v3/verify"
scope=location-verification:verify
# Past auth: the request reached the API logic; 200/404 need TEP, 422 is a device TEP can't locate (or a location
# too old), 503 is TEP being absent
past_auth='200 404 422 503'

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

# area is required, so every body carries one; without it validation answers 400 before any device rule
area='"area":{"areaType":"CIRCLE","center":{"latitude":37.9838,"longitude":23.7275},"radius":2000}'
device='{"device":{"phoneNumber":"'"$PHONE_NUMBER"'"},'"$area"'}'
no_device='{'"$area"'}'
check 'No token' POST "$url" '' "$device" 401 UNAUTHENTICATED
check 'Not a JWT' POST "$url" not-a-jwt "$device" 401 UNAUTHENTICATED
check 'Two-legged, without the scope' POST "$url" "$no_scope" "$device" 403 PERMISSION_DENIED \
    "$(granted_unasked "$no_scope" "$scope")"
check 'Two-legged, device in body' POST "$url" "$two_legged" "$device" "$past_auth"
check 'Two-legged, no device' POST "$url" "$two_legged" "$no_device" 422 MISSING_IDENTIFIER
if three_legged; then
    check 'Three-legged, no device' POST "$url" "$three_legged" "$no_device" "$past_auth"
    check 'Three-legged, device in body' POST "$url" "$three_legged" "$device" 422 UNNECESSARY_IDENTIFIER
fi
