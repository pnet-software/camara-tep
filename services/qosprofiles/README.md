# qosprofiles

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **QoS Profiles** API, **v1.1.0** (Commonalities 0.6). It tells an API consumer which QoS profiles (named sets of latency, throughput and priority targets, used with Quality on Demand) the operator offers, from the quality profiles of the 5G core as reported by the **TEP** (Telco Exposure & Programmability) API.

Every call needs an OAuth2 access token with the scope `qos-profiles:read`, as the spec declares for both operations. See [Security](#security).

## How it works

```
API consumer ──POST /qos-profiles/v1/retrieve-qos-profiles──▶ qosprofiles ──GET /quality-profile?limit&offset──▶ TEP ──▶ 5G core
             ──GET  /qos-profiles/v1/qos-profiles/{name}───▶             ──GET /quality-profile/{name}─────────▶
             Authorization: Bearer <token>
```

`retrieve-qos-profiles` returns a list of profiles. Its body holds filters, all optional, so `{}` asks for every profile:
- `device`: by any of `phoneNumber`, `ipv4Address` (`publicAddress` with `publicPort` or `privateAddress`) and `ipv6Address`. Optional with a two-legged token, not allowed with a three-legged one, which names the device itself. **Every profile is available to every device**, so a device filters nothing out: the answer is the same with or without one.
- `name`: a profile name, 3 to 256 of letters, digits, `_`, `.` and `-`.
- `status`: `ACTIVE`, `INACTIVE` or `DEPRECATED`.

The service reads every TEP profile, a page of 500 at a time (TEP's largest), and keeps those matching `name` and `status`. No match is `[]`, not an error.

`qos-profiles/{name}` returns one profile: TEP `GET /quality-profile/{name}`.

Each profile carries `name` and `status`, plus whichever of the rates, durations, priority, packet delay budget, jitter and loss rate TEP defines for it. The fields map one to one, units included. Fields TEP leaves out are left out of the response, not sent as `null`. TEP's `fiveQi` and pre-emption settings aren't passed on, and CAMARA's `maxUpstreamBurstRate`, `maxDownstreamBurstRate`, `countryAvailability`, `l4sQueueType` and `serviceClass` are never sent, since TEP has no source for them.

### Example

```bash
curl -s http://localhost:9091/qos-profiles/v1/qos-profiles/voice -H "Authorization: Bearer $TOKEN"
# {"name":"voice","status":"ACTIVE","maxDownstreamRate":{"value":10,"unit":"Mbps"},"priority":10,
#  "packetDelayBudget":{"value":50,"unit":"Milliseconds"}}
```

See [Running locally](#running-locally) for how to get a `$TOKEN`.

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON; phone number not in E.164 format; an IPv4 port outside 0–65535; a profile name, in the body or the path, of the wrong length or with other characters; an unknown `status`; invalid `x-correlator` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token without the `qos-profiles:read` scope |
| 404 | `NOT_FOUND` | `qos-profiles/{name}`: TEP has no profile of that name. Also an unknown path |
| 422 | `UNNECESSARY_IDENTIFIER` | Three-legged token, and the body also contains a `device` (even the same one) |
| 503 | `UNAVAILABLE` | TEP is unreachable, times out, or returns any other error. Also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

The spec's other device codes never apply: since a device filters nothing, it is never looked up (no 404 `IDENTIFIER_NOT_FOUND`) and any identifier will do (no 422 `UNSUPPORTED_IDENTIFIER` or `SERVICE_NOT_APPLICABLE`).

A valid `x-correlator` is echoed on every response, 401 and 403 included, and forwarded to TEP. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**, set up exactly like reachability's; see the [reachability README](../reachability/README.md#security) for the details. Here, the scope is `qos-profiles:read`, for both operations:

1. **Token check.** A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** A token without `qos-profiles:read` gets 403 `PERMISSION_DENIED`.
3. **Who the request is about.** Unlike reachability, a device is never required: a two-legged token may send a `device` or not, and a three-legged token names the subscriber itself (the `AUTH_PHONE_NUMBER_CLAIM` claim), so a `device` in the body is rejected with `UNNECESSARY_IDENTIFIER`. Since every profile is available to every device, the answer doesn't otherwise depend on the token's subscriber.

Swagger UI (`/`), the spec file and the protected resource metadata (`/.well-known/oauth-protected-resource/qos-profiles`, which 401s link to) need no token. Actuator runs on its own management port.

`AUTH_ENABLED=false` turns auth off, for labs without an authorization server. The `local` profile defaults to it.

**On a shared Keycloak,** follow reachability's [Using an existing Keycloak](../reachability/README.md#using-an-existing-keycloak), creating the client scope `qos-profiles:read` and adding it to each consumer's client as **Optional**.

## Configuration

The same environment variables as reachability (see its [Configuration](../reachability/README.md#configuration)): `TEP_*`, `SERVER_PORT` (default `9091`), `MANAGEMENT_PORT`, `LOG_FORMAT`, `SPRINGDOC_ENABLED`, `SHUTDOWN_TIMEOUT` and `AUTH_*`. The spec file is served at `/qos-profiles.yaml`.

Every service defaults to ports `9091` and `8081`. To run two at once locally, set `SERVER_PORT` and `MANAGEMENT_PORT` on one of them.

### Metrics and logs

On the management port's `/actuator/prometheus`:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri=…}`**: latency and outcome of TEP calls to `/tep-api/v1/quality-profile…`. The `uri` tag holds the URI template, so phone numbers never appear in metrics.

Every request and TEP call is logged; see [Logging](../../README.md#logging).

## Running locally

From the repository root:

```bash
./mvnw -DskipTests install                                                        # once, and again after changing libs/
./mvnw -pl services/qosprofiles spring-boot:run -Dspring-boot.run.profiles=local   # auth off
```

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Dspring-boot.run.profiles=local'`.

**With auth,** start the [dev/keycloak](../../dev/keycloak) Keycloak. Its realm includes the `qos-profiles:read` scope, optional on `acme-backend` and `subscriber-app`. Then run the service with `AUTH_ENABLED=true` and `AUTH_ISSUER_URI=http://localhost:8180/realms/camara`, and its auth tests, as [dev/keycloak/README.md](../../dev/keycloak/README.md#auth-tests) describes. The commands are the same in bash and PowerShell:

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait
# the service with auth on, in another terminal; then:
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test qosprofiles
```

The tests check 401, 403 on both operations, that a three-legged token can't also name a device (`UNNECESSARY_IDENTIFIER`) and the open endpoints.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=qos-profiles:read | jq -r .access_token)
curl -s -X POST http://localhost:9091/qos-profiles/v1/retrieve-qos-profiles \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"ACTIVE"}'
# without TEP: {"status":503,"code":"UNAVAILABLE","message":"Service Unavailable."}
curl -s http://localhost:9091/qos-profiles/v1/qos-profiles/voice -H "Authorization: Bearer $TOKEN"
```

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token. It offers only this API's scopes, not every scope of the realm, and only the flows it can log in with: client credentials, authorization code and password.

## Tests

```bash
./mvnw -pl services/qosprofiles -am verify
```

- **Unit tests:** the TEP → CAMARA mapping (`QosProfileMapperTest`: every field, and every status and unit TEP can send), and the delegate: paging, the `name` and `status` filters, that a device filters nothing, `UNNECESSARY_IDENTIFIER`, TEP errors, and the metric.
- **Web slice tests:** the real generated controller and the real security rules, for both operations, including 401, 403 (also for the `qos-profiles`, `qos-profiles:retrieve-qos-profiles` and `qos-profiles:check` scopes the first copy asked for), 503 when the issuer is unusable, the open paths, every error code, the correlator, malformed input, invalid filters and profile names, that the filters reach the delegate, that an empty body passes validation, units in the spec's spelling with unset fields left out, a wrong method getting 405, and auth turned off.

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**.

## Container image, chart and releases

These work as for reachability (see its [Container image and Helm chart](../reachability/README.md#container-image-and-helm-chart) and [CI and releases](../reachability/README.md#ci-and-releases)), with `qosprofiles` in place of `reachability`:

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/qosprofiles:<tag>` |
| Chart | `oci://registry-1.docker.io/pnetrepo/qosprofiles-chart` ([charts/qosprofiles](../../charts/qosprofiles)) |
| Release tag | `qosprofiles-vX.Y.Z` |
| CI | [ci.yml](../../.github/workflows/ci.yml) (test, helm-lint), [release.yml](../../.github/workflows/release.yml) (image, chart, GitHub Release) |

## Known limitations

- **Every profile is available to every device.** This deployment makes no profile device-specific, so a `device` filter, or a three-legged token's subscriber, never narrows the list. TEP profiles carry a slice (`sst`, `sd`) that could be matched against the subscriber's slices if that ever changes.
- **No `countryAvailability`, `l4sQueueType` or `serviceClass`.** TEP has no source for them.
- **`retrieve-qos-profiles` reads every TEP profile** on each call and filters them here; TEP has no name or status filter.
- **No 400 `OUT_OF_RANGE`.** No filter combination is rejected.
