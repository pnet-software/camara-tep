# deviceswap

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **Device Swap** API, **v1.1.0** (Commonalities 0.9). It tells an API consumer when a phone number's SIM was last put in a different device (IMEI), or whether that happened within a given period, based on the subscriber's records in the 5G core as reported by the **TEP** (Telco Exposure & Programmability) API.

Every call needs an OAuth2 access token with the scope `device-swap`, or the called operation's own scope, as the spec declares. See [Security](#security).

## How it works

```
API consumer ──POST /device-swap/v1/retrieve-date──▶ deviceswap ──POST /subscriber/swaps──▶ TEP ──▶ 5G core
             ──POST /device-swap/v1/check─────────▶
             Authorization: Bearer <token>
```

1. The phone number is identified in one of two ways, depending on the token (see [Security](#security)):
   - **two-legged token:** by `phoneNumber` in the body (E.164, e.g. `+306912345678`)
   - **three-legged token:** by the subscriber the token was issued for; the body must not contain `phoneNumber`
2. The service calls TEP `POST /subscriber/swaps` with the MSISDN unchanged, including the `+`, in the body as a `TepDevice` (`{"phoneNumber": "+30…"}`), so it never appears in a URL.
3. The latest device swap is TEP's `latestDeviceChange`, the time the IMEISV last changed for that MSISDN. It is passed on with the offset TEP gave it.
4. The operations answer from it:
   - `retrieve-date`: `latestDeviceChange` is TEP's `latestDeviceChange`. When TEP gives none, `latestDeviceChange` is `null`, the spec's answer for a date the operator can't tell. `monitoredPeriod` is never sent, since TEP doesn't say how long it keeps records.
   - `check`: `swapped` is `true` when `latestDeviceChange` is within the last `maxAge` **hours** (1 to 2400, 240 if left out). When TEP gives no `latestDeviceChange`, the spec's answer is 422 `SERVICE_NOT_APPLICABLE`.

### Example

```bash
curl -s -X POST http://localhost:9091/device-swap/v1/retrieve-date \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+306912345678"}'
# {"latestDeviceChange":"2026-10-20T10:00:00+03:00"}
# date unknown: {"latestDeviceChange":null}

curl -s -X POST http://localhost:9091/device-swap/v1/check \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+306912345678","maxAge":120}'
# {"swapped":true}
```

See [Running locally](#running-locally) for how to get a `$TOKEN`.

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON; phone number not in E.164 format; `maxAge` outside 1–2400; invalid `x-correlator` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token with neither `device-swap` nor the operation's own scope; with a valid token, any other path under `/device-swap/` |
| 404 | `IDENTIFIER_NOT_FOUND` | TEP doesn't know the subscriber, or has no swap event for it |
| 404 | `NOT_FOUND` | Unknown path outside `/device-swap/` |
| 422 | `MISSING_IDENTIFIER` | Two-legged token, and no `phoneNumber` in the body |
| 422 | `UNNECESSARY_IDENTIFIER` | Three-legged token, and the body also contains a `phoneNumber` (even the same one) |
| 422 | `SERVICE_NOT_APPLICABLE` | `check` only: TEP knows the subscriber but gives no `latestDeviceChange` |
| 503 | `UNAVAILABLE` | TEP is unreachable, times out, or returns any other error. Also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

A valid `x-correlator` is echoed on every response, 401 and 403 included, and forwarded to TEP. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**, set up like reachability's; see the [reachability README](../reachability/README.md#security) for the details. The difference is that the spec gives each operation a choice of two scopes:

| Operation | Accepted scopes |
|---|---|
| `POST /device-swap/v1/retrieve-date` | `device-swap` or `device-swap:retrieve-date` |
| `POST /device-swap/v1/check` | `device-swap` or `device-swap:check` |

1. **Token check.** A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** A token with neither of the operation's scopes gets 403 `PERMISSION_DENIED`, so a `device-swap:check` token can't call `retrieve-date`. Any other path under `/device-swap/` gets 403 even with a valid token.
3. **Who the request is about.** Reachability's rules, with `phoneNumber` in place of `device`: a two-legged token needs `phoneNumber` in the body, and a three-legged token names the subscriber itself (the `AUTH_PHONE_NUMBER_CLAIM` claim), so a `phoneNumber` in the body is rejected with `UNNECESSARY_IDENTIFIER`.

Swagger UI (`/`), the spec file and the protected resource metadata (`/.well-known/oauth-protected-resource/device-swap`, which 401s link to) need no token. Actuator runs on its own management port.

`AUTH_ENABLED=false` turns auth off, for labs without an authorization server. The `local` profile defaults to it.

**On a shared Keycloak,** follow reachability's [Using an existing Keycloak](../reachability/README.md#using-an-existing-keycloak), creating the client scopes `device-swap`, `device-swap:retrieve-date` and `device-swap:check`, and adding them to each consumer's client as **Optional**.

## Configuration

The same environment variables as reachability (see its [Configuration](../reachability/README.md#configuration)): `TEP_*`, `SERVER_PORT` (default `9091`), `MANAGEMENT_PORT`, `LOG_FORMAT`, `SPRINGDOC_ENABLED`, `SHUTDOWN_TIMEOUT` and `AUTH_*`. The spec file is served at `/device-swap.yaml`.

Every service defaults to ports `9091` and `8081`. To run two at once locally, set `SERVER_PORT` and `MANAGEMENT_PORT` on one of them.

### Metrics and logs

On the management port's `/actuator/prometheus`:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri=…}`**: latency and outcome of TEP calls to `/tep-api/v1/subscriber/swaps`. Phone numbers are in the request body, so they never appear in the `uri` tag.

Every request and TEP call is logged; see [Logging](../../README.md#logging).

## Running locally

From the repository root:

```bash
./mvnw -DskipTests install                                                       # once, and again after changing libs/
./mvnw -pl services/deviceswap spring-boot:run -Dspring-boot.run.profiles=local   # auth off
```

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Dspring-boot.run.profiles=local'`.

**With auth,** start the [dev/keycloak](../../dev/keycloak) Keycloak. Its realm includes the three scopes, optional on `acme-backend` and `subscriber-app`. Then run the service with `AUTH_ENABLED=true` and `AUTH_ISSUER_URI=http://localhost:8180/realms/camara`, and its auth tests, as [dev/keycloak/README.md](../../dev/keycloak/README.md#auth-tests) describes. The commands are the same in bash and PowerShell:

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait
# the service with auth on, in another terminal; then:
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test deviceswap
```

The tests check 401, 403, which scope opens which operation, the two- and three-legged rules (`MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`) and the open endpoints.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=device-swap | jq -r .access_token)
curl -s -X POST http://localhost:9091/device-swap/v1/check \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"+306912345678","maxAge":120}'
# without TEP: {"status":503,"code":"UNAVAILABLE","message":"Service Unavailable."}
```

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token. It offers only this API's scopes, not every scope of the realm, and only the flows it can log in with: client credentials, authorization code and password.

## Tests

```bash
./mvnw -pl services/deviceswap -am verify
```

- **Unit tests:** the delegate: phone number resolution, `maxAge` in hours and its default, a missing `latestDeviceChange` for each operation, TEP errors, and the metric.
- **Web slice tests:** the real generated controllers and the real security rules, including 401, 403, 503 when the issuer is unusable, which scope opens which operation, the open paths, every error code, the correlator, malformed input, `maxAge`'s range and its 240-hour default, that a `null` `latestDeviceChange` is written out but a `null` `monitoredPeriod` left out, a wrong method getting 405, and auth turned off.

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**.

## Container image, chart and releases

These work as for reachability (see its [Container image and Helm chart](../reachability/README.md#container-image-and-helm-chart) and [CI and releases](../reachability/README.md#ci-and-releases)), with `deviceswap` in place of `reachability`:

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/deviceswap:<tag>` |
| Chart | `oci://registry-1.docker.io/pnetrepo/deviceswap-chart` ([charts/deviceswap](../../charts/deviceswap)) |
| Release tag | `deviceswap-vX.Y.Z` |
| CI | [ci.yml](../../.github/workflows/ci.yml) (test, helm-lint), [release.yml](../../.github/workflows/release.yml) (image, chart, GitHub Release) |

## Known limitations

- **No `monitoredPeriod`, no 400 `OUT_OF_RANGE`.** TEP doesn't say how long it keeps swap records, so the service can't tell a consumer, or reject a `maxAge` longer than that.
- **The device swap is TEP's `latestDeviceChange`.** A new IMEISV from a software update on the same device counts as a swap if TEP reports it as a change.
- **Request body strictness isn't enforced.** Properties the spec doesn't declare are ignored, not rejected with 400 `INVALID_ARGUMENT`.
