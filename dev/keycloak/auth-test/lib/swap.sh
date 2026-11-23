# SIM Swap and Device Swap, which differ only in their name: two operations, each opened by the API-wide scope
# <api> or by its own, <api>:retrieve-date or <api>:check. Sourced by cases/simswap.sh and cases/deviceswap.sh,
# which set api (sim-swap or device-swap) and version (the API's major version in its paths, e.g. v2).
SPEC="$api.yaml"
retrieve_date="$API_ROOT/$api/$version/retrieve-date"
check_url="$API_ROOT/$api/$version/check"
scope="$api"
retrieve_date_scope="$api:retrieve-date"
check_scope="$api:check"
# Past auth: the request reached the API logic; 200/404 need TEP, 503 is TEP being absent. check answers 422
# when TEP has no change date, so it counts too.
past_auth='200 404 503'
past_auth_check='200 404 422 503'

two_legged=$(two_legged_token "$scope")
no_scope=$(two_legged_token)
retrieve_date_only=$(two_legged_token "$retrieve_date_scope")
check_only=$(two_legged_token "$check_scope")
show_issuer "$two_legged"
show_claims 'two-legged' "$two_legged"
show_claims 'two-legged, no scope' "$no_scope"
show_claims 'two-legged, retrieve-date only' "$retrieve_date_only"
show_claims 'two-legged, check only' "$check_only"
if three_legged; then
    three_legged=$(three_legged_token "$scope")
    show_claims "three-legged ($SUBSCRIBER)" "$three_legged"
fi
echo

phone='{"phoneNumber":"'"$PHONE_NUMBER"'"}'
check 'No token, retrieve-date' POST "$retrieve_date" '' "$phone" 401 UNAUTHENTICATED
check 'No token, check' POST "$check_url" '' "$phone" 401 UNAUTHENTICATED
check 'Not a JWT' POST "$retrieve_date" not-a-jwt "$phone" 401 UNAUTHENTICATED
check 'Two-legged, without a scope' POST "$retrieve_date" "$no_scope" "$phone" 403 PERMISSION_DENIED \
    "$(granted_unasked "$no_scope" "$scope" "$retrieve_date_scope" "$check_scope")"
check "Two-legged $scope, retrieve-date" POST "$retrieve_date" "$two_legged" "$phone" "$past_auth"
check "Two-legged $scope, check" POST "$check_url" "$two_legged" "$phone" "$past_auth_check"
check 'retrieve-date scope, retrieve-date' POST "$retrieve_date" "$retrieve_date_only" "$phone" "$past_auth"
# A token for one operation that also carries the API-wide scope (or the other's) opens the other operation too
check 'retrieve-date scope, check' POST "$check_url" "$retrieve_date_only" "$phone" 403 PERMISSION_DENIED \
    "$(granted_unasked "$retrieve_date_only" "$scope" "$check_scope")"
check 'check scope, check' POST "$check_url" "$check_only" "$phone" "$past_auth_check"
check 'check scope, retrieve-date' POST "$retrieve_date" "$check_only" "$phone" 403 PERMISSION_DENIED \
    "$(granted_unasked "$check_only" "$scope" "$retrieve_date_scope")"
check 'Two-legged, no phone number' POST "$retrieve_date" "$two_legged" '{}' 422 MISSING_IDENTIFIER
if three_legged; then
    check 'Three-legged, no phone number' POST "$retrieve_date" "$three_legged" '{}' "$past_auth"
    check 'Three-legged, phone number in body' POST "$retrieve_date" "$three_legged" "$phone" 422 \
        UNNECESSARY_IDENTIFIER
fi
