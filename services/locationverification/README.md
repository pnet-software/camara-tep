# locationverification

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **Device Location Verification** API, **v3.0.0** (Commonalities 0.6). It tells an API consumer whether a device is within an area they give, based on the cell the device is in, as the 5G core sees it through the **TEP** (Telco Exposure & Programmability) API.

Every call needs an OAuth2 access token with the scope `location-verification:verify`, as the spec declares. See [Security](#security).

## How it works

```
API consumer ──POST /location-verification/v3/verify──▶ locationverification ──POST /subscriber/location──▶ TEP ──▶ 5G core
             Authorization: Bearer <token>
```

The request carries:
- `area` (required): a circle, `{"areaType":"CIRCLE","center":{"latitude":…,"longitude":…},"radius":…}`, with the radius in metres (at least 1). `CIRCLE` is the only area type in v3.
- `device`: the device to locate. Required with a two-legged token, not allowed with a three-legged one.
- `maxAge` (optional): the oldest location, in seconds, the consumer accepts.

1. The device is identified in one of two ways, depending on the token (see [Security](#security)):
   - **two-legged token:** by `device.phoneNumber` in the body (E.164, e.g. `+306912345678`)
   - **three-legged token:** by the subscriber the token was issued for; the body must not contain `device`
2. The service calls TEP `POST /subscriber/location` with the MSISDN unchanged, including the `+`, in the body as a `TepDevice` (`{"phoneNumber": "+30…"}`), so it never appears in a URL. TEP answers with the cell the UE last reported from (`cellId`) and, if the cell is registered with TEP, its coverage area: `center` (`lat`, `lon`) and `radius` in metres.
3. The cell's coverage circle is the network's estimate of where the device is. It is compared with the requested circle, using the great-circle distance between the centres:

   | Cell coverage vs. requested area | `verificationResult` |
   |---|---|
   | Entirely inside the area | `TRUE` |
   | Doesn't overlap it | `FALSE` |
   | Overlaps it partly, or contains the whole area | `PARTIAL`, with `matchRate` |

   `matchRate` is the share of the cell inside the area, in percent: (area ∩ cell) / cell, as the spec defines it, rounded and kept within 1 to 99. For example, a 2 km area at the centre of a 5 km cell is `PARTIAL` with `matchRate` 16. A cell with radius 0 counts as a point, so it is `TRUE` or `FALSE`.
4. **`lastLocationTime` is TEP's `lastUpdated`,** when the UE last reported its location to the core. With `maxAge`, a location older than `maxAge` seconds, or one TEP gives no time for, is 422 `LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE`, whatever the verification result, as the spec says. `maxAge` 0 asks for a fresh location, which the service can't trigger, so it is only met by a location reported in the same second. Without `maxAge` any age is accepted; if TEP then gives no time, `lastLocationTime` is the time of the request, since the response needs one.
5. TEP answers 404 both for a subscriber it doesn't know and for one it can't place in a cell. The service then asks TEP for the subscriber's status (`POST /subscriber/status`) to tell them apart: unknown is 404 `IDENTIFIER_NOT_FOUND`, known is 422 `LOCATION_VERIFICATION.UNABLE_TO_LOCATE`. A cell without a usable `center` or `radius`, such as one not registered with TEP, is also `UNABLE_TO_LOCATE`.
6. `device` is echoed in the response only when the request contained more than one identifier, as the spec requires.

### Example

```bash
curl -s -X POST http://localhost:9091/location-verification/v3/verify \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'x-correlator: b4333c46-49c0-4f62-80d7-f0ef930f1c46' \
  -d '{"device":{"phoneNumber":"+306912345678"},"area":{"areaType":"CIRCLE","center":{"latitude":37.9838,"longitude":23.7275},"radius":2000}}'
# {"verificationResult":"TRUE","lastLocationTime":"2026-11-04T10:00:00Z"}
# a larger cell: {"verificationResult":"PARTIAL","lastLocationTime":"2026-11-04T10:00:00Z","matchRate":16}
```

See [Running locally](#running-locally) for how to get a `$TOKEN`.

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON; no `area`, an `areaType` other than `CIRCLE`, a radius below 1 or coordinates out of range; phone number not in E.164 format; invalid `x-correlator` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token without the `location-verification:verify` scope |
| 404 | `IDENTIFIER_NOT_FOUND` | TEP doesn't know the subscriber |
| 404 | `NOT_FOUND` | Unknown path |
| 422 | `MISSING_IDENTIFIER` | Two-legged token, and no `device` or a `device` with no identifiers |
| 422 | `UNNECESSARY_IDENTIFIER` | Three-legged token, and the body also contains a `device` (even the same one) |
| 422 | `UNSUPPORTED_IDENTIFIER` | Only `ipv4Address`, `ipv6Address` or `networkAccessIdentifier` given (only `phoneNumber` is supported) |
| 422 | `LOCATION_VERIFICATION.UNABLE_TO_LOCATE` | TEP knows the subscriber but can't place it in a cell, or the cell has no usable centre or radius (e.g. it isn't registered with TEP) |
| 422 | `LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE` | The location is older than `maxAge` seconds, TEP gives no time for it, or `maxAge` is negative |
| 503 | `UNAVAILABLE` | TEP is unreachable, times out, or returns any other error. Also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

The spec's `LOCATION_VERIFICATION.AREA_NOT_COVERED` and `LOCATION_VERIFICATION.INVALID_AREA` are never returned: the service has no coverage map and sets no minimum radius beyond the spec's 1 metre.

A valid `x-correlator` is echoed on every response, 401 and 403 included, and forwarded to TEP. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**, set up exactly like reachability's; see the [reachability README](../reachability/README.md#security) for the details. Here, the scope is `location-verification:verify`:

1. **Token check.** A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** A token without `location-verification:verify` gets 403 `PERMISSION_DENIED`.
3. **Who the request is about.** The same rules as reachability: a two-legged token needs `device.phoneNumber` in the body, and a three-legged token names the subscriber itself (the `AUTH_PHONE_NUMBER_CLAIM` claim), so a `device` in the body is rejected with `UNNECESSARY_IDENTIFIER`.

Swagger UI (`/`), the spec file and the protected resource metadata (`/.well-known/oauth-protected-resource/location-verification`, which 401s link to) need no token. Actuator runs on its own management port.

`AUTH_ENABLED=false` turns auth off, for labs without an authorization server. The `local` profile defaults to it.

**On a shared Keycloak,** follow reachability's [Using an existing Keycloak](../reachability/README.md#using-an-existing-keycloak), creating the client scope `location-verification:verify` and adding it to each consumer's client as **Optional**.

## Configuration

The same environment variables as reachability (see its [Configuration](../reachability/README.md#configuration)): `TEP_*`, `SERVER_PORT` (default `9091`), `MANAGEMENT_PORT`, `LOG_FORMAT`, `SPRINGDOC_ENABLED`, `SHUTDOWN_TIMEOUT` and `AUTH_*`. The spec file is served at `/location-verification.yaml`.

reachability, roaming and this service all default to ports `9091` and `8081`. To run two at once locally, set `SERVER_PORT` and `MANAGEMENT_PORT` on one of them.

### Metrics and logs

On the management port's `/actuator/prometheus`:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri=…}`**: latency and outcome of TEP calls to `/tep-api/v1/subscriber/location` (and `/tep-api/v1/subscriber/status` for the 404 check). Phone numbers are in the request body, so they never appear in the `uri` tag.

Every request and TEP call is logged; see [Logging](../../README.md#logging).

## Running locally

From the repository root:

```bash
./mvnw -DskipTests install                                                                 # once, and again after changing libs/
./mvnw -pl services/locationverification spring-boot:run -Dspring-boot.run.profiles=local   # auth off
```

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Dspring-boot.run.profiles=local'`.

**With auth,** start the [dev/keycloak](../../dev/keycloak) Keycloak. Its realm includes the `location-verification:verify` scope, optional on `acme-backend` and `subscriber-app`. Then run the service with `AUTH_ENABLED=true` and `AUTH_ISSUER_URI=http://localhost:8180/realms/camara`, and its auth tests, as [dev/keycloak/README.md](../../dev/keycloak/README.md#auth-tests) describes. The commands are the same in bash and PowerShell:

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait
# the service with auth on, in another terminal; then:
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test locationverification
```

The tests check 401, 403, the two- and three-legged rules (`MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`) and the open endpoints. Every request body carries an `area`, which the spec requires, and a 422 from a subscriber TEP can't place counts as past auth.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=location-verification:verify | jq -r .access_token)
curl -s -X POST http://localhost:9091/location-verification/v3/verify \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"device":{"phoneNumber":"+306912345678"},"area":{"areaType":"CIRCLE","center":{"latitude":37.9838,"longitude":23.7275},"radius":2000}}'
# without TEP: {"status":503,"code":"UNAVAILABLE","message":"Service Unavailable."}
```

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token. It offers only this API's scopes, not every scope of the realm, and only the flows it can log in with: client credentials, authorization code and password.

## Tests

```bash
./mvnw -pl services/locationverification -am verify
```

- **Unit tests:** the geometry (`LocationMapperTest`: the cell's coverage, unregistered and unusable cells, the distance, `TRUE`/`FALSE`/`PARTIAL`, the match rate and its 1 to 99 bounds, a zero-radius cell) and the delegate: device resolution, `lastLocationTime` and `maxAge` against TEP's `lastUpdated`, telling an unknown subscriber from one TEP can't place, TEP errors, and the metric.
- **Web slice tests:** the real generated controller and the real security rules, including 401, 403 (also for the `location-verification:openId` scope the first copy asked for), 503 when the issuer is unusable, the open paths, every error code including the spec's `LOCATION_VERIFICATION.*` ones, the correlator, malformed input, the `area` rules, that a circle reaches the delegate as a `Circle`, that a `TRUE` result has no `matchRate`, and auth turned off.

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**.

## Container image, chart and releases

These work as for reachability (see its [Container image and Helm chart](../reachability/README.md#container-image-and-helm-chart) and [CI and releases](../reachability/README.md#ci-and-releases)), with `locationverification` in place of `reachability`:

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/locationverification:<tag>` |
| Chart | `oci://registry-1.docker.io/pnetrepo/locationverification-chart` ([charts/locationverification](../../charts/locationverification)) |
| Release tag | `locationverification-vX.Y.Z` |
| CI | [ci.yml](../../.github/workflows/ci.yml) (test, helm-lint), [release.yml](../../.github/workflows/release.yml) (image, chart, GitHub Release) |

## Known limitations

- **Cell-level accuracy.** The location is the serving cell's coverage circle, so an area smaller than the cell can at best be `PARTIAL`.
- **Circles on a flat plane.** The distance between the centres follows the Earth's surface, but the overlap is computed as for flat circles, which is close enough at cell sizes.
- **No fresh location on demand.** `maxAge` is checked against the last time the UE reported its location; the service can't make it report again, so a small `maxAge` (0 especially) can't be met for a UE that hasn't moved or reported recently.
- **A 404 from TEP costs a second call** (`/status`), to tell an unknown subscriber from one TEP can't place.
- **No `AREA_NOT_COVERED` or `INVALID_AREA`:** there is no coverage map and no minimum radius.
- **Only `phoneNumber` is supported** as a device identifier.
