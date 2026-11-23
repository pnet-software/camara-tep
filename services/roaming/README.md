# roaming

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **Device Roaming Status** API, **v1.1.0** (Commonalities 0.6). It tells an API consumer whether a device is roaming and, if it is, the visited country, based on the device's live state in the 5G core as reported by the **TEP** (Telco Exposure & Programmability) API.

Every call needs an OAuth2 access token with the scope `device-roaming-status:read`, as the spec declares. See [Security](#security).

## How it works

```
API consumer ──POST /device-roaming-status/v1/retrieve──▶ roaming ──POST /subscriber/status──▶ TEP ──▶ 5G core
             Authorization: Bearer <token>
```

1. The device is identified in one of two ways, depending on the token (see [Security](#security)):
   - **two-legged token:** by `device.phoneNumber` in the body (E.164, e.g. `+306912345678`)
   - **three-legged token:** by the subscriber the token was issued for; the body must not contain `device`
2. The service calls TEP `POST /subscriber/status` with the MSISDN unchanged, including the `+`, in the body as a `TepDevice` (`{"phoneNumber": "+30…"}`), so it never appears in a URL.
3. Whether the device is roaming is TEP's `roaming` in the subscriber status: TEP compares the network serving the UE (`mcc` + `mnc`) with the home PLMNs its deployment is configured with (open5gs-em's `tep.home-plmns`). If TEP gives no `roaming` (the UE isn't registered, or TEP has no home PLMNs) or no valid serving `mcc`, the status can't be determined: 422 `SERVICE_NOT_APPLICABLE`.
4. When roaming, the response carries `countryCode`, the serving MCC, and `countryName`, the ISO 3166-1 alpha-2 codes of the countries that MCC is assigned to (e.g. 262 → `["DE"]`, 340 → `["BL","GF","GP","MF","MQ"]`, 901 → `[]`). The table is [mcc-countries.properties](src/main/resources/mcc-countries.properties); its header names the source. When not roaming, both fields are left out.
5. `lastStatusTime` is the time of the request, because TEP exposes no status timestamp.
6. `device` is echoed in the response only when the request contained more than one identifier, as the spec requires.

### Example

```bash
curl -s -X POST http://localhost:9091/device-roaming-status/v1/retrieve \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'x-correlator: b4333c46-49c0-4f62-80d7-f0ef930f1c46' \
  -d '{"device":{"phoneNumber":"+306912345678"}}'
# {"lastStatusTime":"2026-10-22T12:00:00Z","roaming":true,"countryCode":262,"countryName":["DE"]}
# not roaming: {"lastStatusTime":"2026-10-22T12:00:00Z","roaming":false}
```

See [Running locally](#running-locally) for how to get a `$TOKEN`.

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON, phone number not in E.164 format, invalid `x-correlator` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token without the `device-roaming-status:read` scope |
| 404 | `IDENTIFIER_NOT_FOUND` | TEP doesn't know the subscriber |
| 404 | `NOT_FOUND` | Unknown path |
| 422 | `MISSING_IDENTIFIER` | Two-legged token, and no `device` or a `device` with no identifiers |
| 422 | `UNNECESSARY_IDENTIFIER` | Three-legged token, and the body also contains a `device` (even the same one) |
| 422 | `UNSUPPORTED_IDENTIFIER` | Only `ipv4Address`, `ipv6Address` or `networkAccessIdentifier` given (only `phoneNumber` is supported) |
| 422 | `SERVICE_NOT_APPLICABLE` | TEP knows the subscriber but reports no serving `mcc`/`mnc` (the UE isn't registered) |
| 503 | `UNAVAILABLE` | TEP is unreachable, times out, or returns any other error. Also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

A valid `x-correlator` is echoed on every response, 401 and 403 included, and forwarded to TEP. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**, set up exactly like reachability's; see the [reachability README](../reachability/README.md#security) for the details. Here, the scope is `device-roaming-status:read`:

1. **Token check.** A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** A token without `device-roaming-status:read` gets 403 `PERMISSION_DENIED`.
3. **Who the request is about.** The same rules as reachability: a two-legged token needs `device.phoneNumber` in the body, and a three-legged token names the subscriber itself (the `AUTH_PHONE_NUMBER_CLAIM` claim), so a `device` in the body is rejected with `UNNECESSARY_IDENTIFIER`.

Swagger UI (`/`), the spec file and the protected resource metadata (`/.well-known/oauth-protected-resource/device-roaming-status`, which 401s link to) need no token. Actuator runs on its own management port.

`AUTH_ENABLED=false` turns auth off, for labs without an authorization server. The `local` profile defaults to it.

**On a shared Keycloak,** follow reachability's [Using an existing Keycloak](../reachability/README.md#using-an-existing-keycloak), creating the client scope `device-roaming-status:read` and adding it to each consumer's client as **Optional**.

## Configuration

The same environment variables as reachability (see its [Configuration](../reachability/README.md#configuration)): `TEP_*`, `SERVER_PORT` (default `9091`), `MANAGEMENT_PORT`, `LOG_FORMAT`, `SPRINGDOC_ENABLED`, `SHUTDOWN_TIMEOUT` and `AUTH_*`. The spec file is served at `/device-roaming-status.yaml`.

The home PLMNs aren't configured here: TEP's deployment has them (open5gs-em's `tep.home-plmns`, a list).

### Metrics and logs

On the management port's `/actuator/prometheus`:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri=…}`**: latency and outcome of TEP calls to `/tep-api/v1/subscriber/status`. Phone numbers are in the request body, so they never appear in the `uri` tag.

Every request and TEP call is logged; see [Logging](../../README.md#logging).

## Running locally

From the repository root:

```bash
./mvnw -DskipTests install                                                    # once, and again after changing libs/
./mvnw -pl services/roaming spring-boot:run -Dspring-boot.run.profiles=local   # auth off
```

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Dspring-boot.run.profiles=local'`.

**With auth,** start the [dev/keycloak](../../dev/keycloak) Keycloak. Its realm includes the `device-roaming-status:read` scope, optional on `acme-backend` and `subscriber-app`. Then run the service with `AUTH_ENABLED=true` and `AUTH_ISSUER_URI=http://localhost:8180/realms/camara`, and its auth tests, as [dev/keycloak/README.md](../../dev/keycloak/README.md#auth-tests) describes. The commands are the same in bash and PowerShell:

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait
# the service with auth on, in another terminal; then:
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test roaming
```

The tests check 401, 403, the two- and three-legged rules (`MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`) and the open endpoints.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=device-roaming-status:read | jq -r .access_token)
curl -s -X POST http://localhost:9091/device-roaming-status/v1/retrieve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"device":{"phoneNumber":"+306912345678"}}'
# without TEP: {"status":503,"code":"UNAVAILABLE","message":"Service Unavailable."}
```

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token. It offers only this API's scopes, not every scope of the realm, and only the flows it can log in with: client credentials, authorization code and password.

## Tests

```bash
./mvnw -pl services/roaming -am verify
```

- **Unit tests:** the mapping of TEP's status (`RoamingMapperTest`: TEP's `roaming`, and unusable TEP data), the MCC → country table (`MccCountriesTest`, against the spec's examples), and the delegate: device resolution, TEP errors, country fields only when roaming, and the metric.
- **Web slice tests:** the real generated controller and the real security rules, including 401, 403, 503 when the issuer is unusable, the open paths, every error code, the correlator, malformed input, auth turned off, and that a not-roaming response has no country fields.

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**.

## Container image, chart and releases

These work as for reachability (see its [Container image and Helm chart](../reachability/README.md#container-image-and-helm-chart) and [CI and releases](../reachability/README.md#ci-and-releases)), with `roaming` in place of `reachability`:

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/roaming:<tag>` |
| Chart | `oci://registry-1.docker.io/pnetrepo/roaming-chart` ([charts/roaming](../../charts/roaming)) |
| Release tag | `roaming-vX.Y.Z` |
| CI | [ci.yml](../../.github/workflows/ci.yml) (test, helm-lint), [release.yml](../../.github/workflows/release.yml) (image, chart, GitHub Release) |

## Known limitations

- **TEP's home PLMNs are one list for every subscriber.** Subscribers with different home networks (e.g. several MVNOs) can't be told apart, and any PLMN not in the list counts as roaming, including national roaming in the home country (`countryCode` is then the home MCC).
- **Only `phoneNumber` is supported** as a device identifier.
- **`lastStatusTime` is the time of the request,** not the time the network last observed the status.
- **The MCC → country table is bundled,** so new ITU assignments need a release.
