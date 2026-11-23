# qualityondemand

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **Quality-On-Demand** API, **v1.1.0** (Commonalities 0.6). It lets an API consumer ask the network for a QoS profile (stable latency or throughput) on the traffic between a device and an application server, for a set time, and extend, read and end that session. Each session is a 3GPP **AS session with QoS** (TS 29.122) in the **TEP** (Telco Exposure & Programmability) API, which TEP applies in the 5G core. Sessions are kept in **Redis**, shared by every replica.

Every call needs an OAuth2 access token with the called operation's own scope, as the spec declares. See [Security](#security).

## How it works

```
API consumer ──POST   /quality-on-demand/v1/sessions──────────────────▶ qualityondemand ──▶ TEP ──▶ 5G core
             ──GET    /quality-on-demand/v1/sessions/{sessionId}──────▶       │   ▲
             ──DELETE /quality-on-demand/v1/sessions/{sessionId}──────▶       │   └── POST /tep-notifications/notifications
             ──POST   /quality-on-demand/v1/sessions/{sessionId}/extend▶      ▼        (TEP: the network ended a session)
             ──POST   /quality-on-demand/v1/retrieve-sessions─────────▶     Redis
             Authorization: Bearer <token>
API consumer's sink ◀── qos-status-changed CloudEvents (AVAILABLE, UNAVAILABLE)
```

`sessions` (create) takes:
- `applicationServer` (required): an `ipv4Address`, a single address or a subnet (`198.51.100.0/24`). Only IPv4 is supported, since TEP identifies the device by its IPv4 address.
- `qosProfile` (required): the name of a TEP quality profile, as listed by the QoS Profiles API.
- `duration` (required): seconds, within the profile's `minDuration` and `maxDuration` when TEP sets them.
- `device`: by `phoneNumber`. Required with a two-legged token, not allowed with a three-legged one, which names the device itself.
- `devicePorts`, `applicationServerPorts` (optional): `ports` and/or `ranges` (`from`, `to`), each 0–65535. Without them, the profile applies to every flow between the device and the server.
- `sink` and `sinkCredential` (optional): an `https://` URL to post `qos-status-changed` events to, and the bearer access token to post them with (`credentialType` `ACCESSTOKEN`).

Creating a session, in this order:
1. The device is the three-legged token's subscriber, or `device.phoneNumber` with a two-legged token, as in reachability.
2. TEP's quality profile (`GET /quality-profile/{qosProfile}`) must exist and be `ACTIVE`, and allow the `duration`.
3. The device's address comes from TEP's subscriber status (`POST /subscriber/status`, with the phone number in the body): the `pduAddress` of its first PDU session to a data network other than `ims`. A device that isn't registered or has no such session gets 422 `SERVICE_NOT_APPLICABLE`.
4. **Conflicts.** A session conflicts with another `AVAILABLE` session of the device when their flows overlap: the application-server networks overlap (one contains the other), and so do the device ports and the server ports (no ports meaning all). That is 409 `CONFLICT`, and so is a create while another one for the device is in progress (a per-device lock in Redis).
5. TEP creates the AS session with QoS (`POST /3gpp-as-session-with-qos/v1/{scsAsId}/subscriptions`) with:
   - `ueIpv4Addr`: the device's address
   - `qosReference`: the `qosProfile`
   - `notificationDestination`: this service's `QOD_NOTIFICATION_DESTINATION`
   - `flowInfo`: one flow with two TS 29.214 IP filter rules, both `permit out ip`, downlink then uplink, e.g. `permit out ip from 198.51.100.0/24 443 to 10.45.0.9/32` and `permit out ip from 10.45.0.9/32 to 198.51.100.0/24 443`
6. TEP answers once the core has applied the QoS, so the session is `AVAILABLE` at once (there is no `REQUESTED` phase): stored in Redis, notified to the `sink`, and returned (201, with `Location`).

The other operations:
- `sessions/{sessionId}`: `GET` returns the session; `DELETE` ends the AS session in TEP first (if that fails, the session is kept and the answer is 503), notifies `UNAVAILABLE` + `DELETE_REQUESTED` if it was `AVAILABLE`, and forgets it (204).
- `sessions/{sessionId}/extend`: adds `requestedAdditionalDuration` seconds to an `AVAILABLE` session, capped at the profile's `maxDuration`; any other state is 409 `QUALITY_ON_DEMAND.SESSION_EXTENSION_NOT_ALLOWED`. Only `expiresAt` changes; TEP isn't called.
- `retrieve-sessions`: the caller's sessions of a `device`, possibly none. The `device` rules are as for create.

A session is only visible to the API client that created it (the token's `azp`, or `client_id`), and with a three-legged token, to its subscriber; anyone else gets 404 `NOT_FOUND`, as for a session that doesn't exist. With auth off there is no such check. `sinkCredential` is never returned.

**How a session ends:**
- **Duration over.** Every replica looks for expired sessions every `QOD_EXPIRY_CHECK_INTERVAL`; each is claimed by exactly one (removed from a Redis sorted set). It ends the AS session in TEP, marks the session `UNAVAILABLE` + `DURATION_EXPIRED` and notifies the `sink`. If TEP fails, it is tried again after `QOD_EXPIRY_RETRY_DELAY`.
- **The network ended it.** TEP posts 3GPP `UserPlaneNotificationData` to `{notificationDestination}/notifications`, i.e. `POST /tep-notifications/notifications`. On `SESSION_TERMINATION` or `FAILED_RESOURCES_ALLOCATION` the session becomes `UNAVAILABLE` + `NETWORK_TERMINATED`, and the `sink` is notified. Other events are acknowledged and ignored. This endpoint takes no token (TEP has none) and is outside `/quality-on-demand`, so the ingress never exposes it.
- **Deleted**, as above.

An ended session is still answered, `UNAVAILABLE` with its `statusInfo`, `expiresAt` set to when it ended and `duration` to how long it lasted, for `QOD_TERMINATED_RETENTION` (360 seconds, as the spec asks); then it is gone (404).

**Notifications** to a `sink` are CloudEvents 1.0 (`application/cloudevents+json`) of type `org.camaraproject.quality-on-demand.v1.qos-status-changed`, with `source` `{QOD_API_ROOT}/quality-on-demand/v1/sessions/{sessionId}` and `data` `{sessionId, qosStatus, statusInfo}`, sent with `Authorization: Bearer <accessToken>` when given and the request's `x-correlator`. Each is sent once, on a background thread; a failure is logged (with the sink's host only) and counted, not retried.

### Example

```bash
curl -s -X POST http://localhost:9091/quality-on-demand/v1/sessions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"device":{"phoneNumber":"+306912345678"},"applicationServer":{"ipv4Address":"198.51.100.0/24"},"qosProfile":"QOS_E","duration":3600}'
# {"applicationServer":{"ipv4Address":"198.51.100.0/24"},"qosProfile":"QOS_E","sessionId":"…","duration":3600,
#  "startedAt":"2026-11-06T10:00:00Z","expiresAt":"2026-11-06T11:00:00Z","qosStatus":"AVAILABLE"}
```

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON; a required field missing; `duration` or `requestedAdditionalDuration` below 1; a `qosProfile` of the wrong length or with other characters, or one TEP doesn't have; an `applicationServer` without `ipv4Address`, or not an IPv4 address or network; a port outside 0–65535; a `sink` that isn't `https://`; an unknown `credentialType`; phone number not in E.164 format; a `sessionId` that isn't a UUID; invalid `x-correlator` |
| 400 | `OUT_OF_RANGE` | A port range whose `from` is above its `to` |
| 400 | `QUALITY_ON_DEMAND.DURATION_OUT_OF_RANGE` | `duration` outside the profile's `minDuration`–`maxDuration` |
| 400 | `INVALID_CREDENTIAL`, `INVALID_TOKEN` | A `sinkCredential` that isn't `ACCESSTOKEN`, or whose `accessTokenType` isn't `bearer` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token without the operation's own scope; with a valid token, any other path under `/quality-on-demand/` |
| 404 | `NOT_FOUND` | An unknown session, or one another client (or subscriber) created; unknown path outside `/quality-on-demand/` |
| 404 | `IDENTIFIER_NOT_FOUND` | TEP doesn't know the device |
| 405 | `METHOD_NOT_ALLOWED` | A method the path doesn't take, e.g. `GET /sessions` |
| 409 | `CONFLICT` | An `AVAILABLE` session of the device with an overlapping flow, or another create for the device in progress |
| 409 | `QUALITY_ON_DEMAND.SESSION_EXTENSION_NOT_ALLOWED` | Extending a session that isn't `AVAILABLE` |
| 422 | `QUALITY_ON_DEMAND.QOS_PROFILE_NOT_APPLICABLE` | The profile exists but isn't `ACTIVE` |
| 422 | `SERVICE_NOT_APPLICABLE` | The device isn't registered or has no data session (no address), or TEP refused the AS session (a 4xx) |
| 422 | `MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`, `UNSUPPORTED_IDENTIFIER` | As in reachability: no device with a two-legged token, a device with a three-legged one, or a device without `phoneNumber` |
| 503 | `UNAVAILABLE` | TEP or Redis is unreachable, times out, or returns an error; also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

A valid `x-correlator` is echoed on every response, 401 and 403 included. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**, set up like reachability's; see the [reachability README](../reachability/README.md#security) for the details. The difference is that the spec gives each operation its own scope, and no API-wide one:

| Operation | Scope |
|---|---|
| `POST /quality-on-demand/v1/sessions` | `quality-on-demand:sessions:create` |
| `GET /quality-on-demand/v1/sessions/{sessionId}` | `quality-on-demand:sessions:read` |
| `DELETE /quality-on-demand/v1/sessions/{sessionId}` | `quality-on-demand:sessions:delete` |
| `POST /quality-on-demand/v1/sessions/{sessionId}/extend` | `quality-on-demand:sessions:update` |
| `POST /quality-on-demand/v1/retrieve-sessions` | `quality-on-demand:sessions:retrieve-by-device` |

1. **Token check.** A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** A token without the operation's scope gets 403 `PERMISSION_DENIED`, so a `read` token can't delete a session. `HEAD` on a session, which MVC answers as `GET`, needs `read` too. Any other path under `/quality-on-demand/` gets 403 even with a valid token.
3. **Who the request is about.** For create and `retrieve-sessions`, reachability's rules (`ReachabilityStatusDelegate.resolveDevice`): a two-legged token needs `device` in the body, and a three-legged token names the subscriber itself, so a `device` in the body is rejected with `UNNECESSARY_IDENTIFIER`. For the operations on one session, the session must have been created by the same client (`azp`), and with a three-legged token, for the same subscriber; otherwise 404.

Swagger UI (`/`), the spec file, the protected resource metadata (`/.well-known/oauth-protected-resource/quality-on-demand`, which 401s link to) and TEP's `/tep-notifications/notifications` need no token. Actuator runs on its own management port.

`AUTH_ENABLED=false` turns auth off, for labs without an authorization server. The `local` profile defaults to it.

**On a shared Keycloak,** follow reachability's [Using an existing Keycloak](../reachability/README.md#using-an-existing-keycloak), creating the five client scopes above and adding them to each consumer's client as **Optional**.

## Configuration

The same environment variables as reachability (see its [Configuration](../reachability/README.md#configuration)): `TEP_*`, `SERVER_PORT` (default `9091`), `MANAGEMENT_PORT`, `LOG_FORMAT`, `SPRINGDOC_ENABLED`, `SHUTDOWN_TIMEOUT` and `AUTH_*`. The spec file is served at `/quality-on-demand.yaml`. In addition:

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_TIMEOUT` | `localhost`, `6379`, none, `2s` | The Redis sessions are kept in. Required: there is no in-memory fallback |
| `QOD_SCS_AS_ID` | `camara-qod` | The SCS/AS id the TEP AS sessions are created under |
| `QOD_NOTIFICATION_DESTINATION` | `http://localhost:9091/tep-notifications` | Where TEP notifies this service; TEP appends `/notifications`. Must be reachable from TEP |
| `QOD_API_ROOT` | `http://localhost:9091` | This API's public root, the `source` of the CloudEvents |
| `QOD_EXPIRY_CHECK_INTERVAL`, `QOD_EXPIRY_RETRY_DELAY` | `5s`, `30s` | How often expired sessions are looked for, and when a TEP delete that failed is tried again |
| `QOD_EXPIRY_ENABLED` | `true` | `false` stops this instance from looking for expired sessions |
| `QOD_TERMINATED_RETENTION` | `360s` | How long an ended session is still answered |
| `QOD_SINK_TIMEOUT` | `5s` | Connect and read timeout of a notification to a `sink` |

Every service defaults to ports `9091` and `8081`. To run two at once locally, set `SERVER_PORT` and `MANAGEMENT_PORT` on one of them.

### Metrics and logs

On the management port's `/actuator/prometheus`:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri=…}`**: latency and outcome of TEP calls to the subscriber status, quality profiles and AS session URIs. The `uri` tag holds the URI template, so phone numbers never appear in metrics.

Every request and TEP call is logged, and so are session lifecycle events (created, deleted, extended, expired, ended by the network) and each notification to a `sink`; see [Logging](../../README.md#logging).

## Running locally

From the repository root:

```bash
docker run -d --name qod-redis -p 6379:6379 redis:7.4-alpine                          # Redis is required, even locally
./mvnw -DskipTests install                                                            # once, and again after changing libs/
TEP_BASE_URL=http://<tep>/tep-api/v1 \
  ./mvnw -pl services/qualityondemand spring-boot:run -Dspring-boot.run.profiles=local   # auth off
```

For TEP to notify a local run of sessions the network ends, `QOD_NOTIFICATION_DESTINATION` must be an address TEP can reach, e.g. `http://<your-ip>:9091/tep-notifications`.

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Dspring-boot.run.profiles=local'`.

**With auth,** start the [dev/keycloak](../../dev/keycloak) Keycloak. Its realm includes the five scopes, optional on `acme-backend` and `subscriber-app`. Then run the service with `AUTH_ENABLED=true` and `AUTH_ISSUER_URI=http://localhost:8180/realms/camara`, and its auth tests, as [dev/keycloak/README.md](../../dev/keycloak/README.md#auth-tests) describes. The commands are the same in bash and PowerShell:

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait
# the service with auth on, in another terminal; then:
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test qualityondemand
```

The tests check 401, 403, that each scope opens its own operation and no other, the two- and three-legged rules (`MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`) and the open endpoints. With TEP, the create checks ask for real sessions, which end on their own after 60 seconds.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=quality-on-demand:sessions:create | jq -r .access_token)
curl -s -X POST http://localhost:9091/quality-on-demand/v1/sessions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"device":{"phoneNumber":"+306912345678"},"applicationServer":{"ipv4Address":"198.51.100.0/24"},"qosProfile":"QOS_E","duration":3600}'
# without TEP: {"status":503,"code":"UNAVAILABLE","message":"Service Unavailable."}
```

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token. It offers only this API's scopes, not every scope of the realm, and only the flows it can log in with: client credentials, authorization code and password.

## Tests

```bash
./mvnw -pl services/qualityondemand -am verify
```

- **Web slice tests:** the real generated controller and the real security rules, for all five operations, including 401, 403, 503 when the issuer is unusable, that each operation opens with its own scope and no other, `HEAD` needing the read scope, the open paths, every error code, the correlator, malformed input, invalid create and extend bodies, a `sessionId` that isn't a UUID, that ports, `sink` and `sinkCredential` reach the delegate, that `retrieve-sessions` without a `device` passes validation, a wrong method getting 405, and auth turned off.
- **Unit tests:**
  - `SessionServiceTest`, against an in-memory store and a mocked TEP: every create check and its error, conflicts and the create lock, ownership, delete's order and its notification, extend's cap, expiry (claim, retry, an extend in between) and network termination
  - `FlowRulesTest`: network and port overlaps, and the IP filter rules
  - `TepQosGatewayTest`: TEP's answers and failures, the device's address, and that unset lists aren't sent to TEP
  - `SinkNotifierTest`: the CloudEvent and its headers, against a local HTTP server
  - `RedisSessionStoreTest`: the Redis keys and operations; `QualityOnDemandDelegateTest`: device resolution, the response, the metric
  - `TepNotificationsWebTest`: TEP's notifications need no token, and only terminating events end a session
- **Integration test:** `RedisSessionStoreIT` runs the store against a real Redis in a container (Testcontainers), and is skipped where Docker isn't available, as in CI.

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**.

Two generator settings in `pom.xml` keep create's validation as the spec means it:
- `format: uri` maps to `String`. As `java.net.URI`, `sink`'s `https://` pattern had no validator, and every create failed with 500.
- `containerDefaultToNull`: an omitted optional list stays `null`. Defaulted to `[]`, it failed `minItems: 1`, so a `devicePorts` with `ports` but no `ranges` got a 400.

## Container image, chart and releases

These work as for reachability (see its [Container image and Helm chart](../reachability/README.md#container-image-and-helm-chart) and [CI and releases](../reachability/README.md#ci-and-releases)), with `qualityondemand` in place of `reachability`:

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/qualityondemand:<tag>` |
| Chart | `oci://registry-1.docker.io/pnetrepo/qualityondemand-chart` ([charts/qualityondemand](../../charts/qualityondemand)) |
| Release tag | `qualityondemand-vX.Y.Z` |
| CI | [ci.yml](../../.github/workflows/ci.yml) (test, helm-lint), [release.yml](../../.github/workflows/release.yml) (image, chart, GitHub Release) |

**Redis in the chart.** Each release runs its own Redis (`redis.enabled`, [templates/redis.yaml](../../charts/qualityondemand/templates/redis.yaml)): one pod and a `ClusterIP` Service `<release>-redis`, non-root, read-only root filesystem. `config.extra` points the service at it, and `QOD_NOTIFICATION_DESTINATION` at the release's own Service. Options:
- `redis.persistence.enabled`: keep the data on a PersistentVolumeClaim (`size`, `storageClass`, `existingClaim`). Off by default, so a Redis restart loses the sessions: they still end in TEP at their own time, but this service no longer knows them.
- `redis.auth.enabled`: require a password, from `redis.auth.existingSecret` or a Secret the chart makes from `redis.auth.password`. The service gets it as `REDIS_PASSWORD` on its own (`secretEnv`).
- `redis.enabled: false`: use an existing Redis instead: `redis.external.host` (required), `port`, and `existingSecret` / `existingSecretKey` for its password.

The [camara chart](../../charts/camara/README.md) deploys this service together with others, and passes the same `redis.*` values through as `qualityondemand.redis.*`.

Also set `config.extra.QOD_API_ROOT` to the API's public root.

## Known limitations

- **Only `phoneNumber`** identifies a device, and **only IPv4** application servers are supported: TEP's AS session identifies the device by its IPv4 address, taken from its data session.
- **No `REQUESTED` state.** TEP answers the create once the QoS is applied, so a session is `AVAILABLE` from the start, and the only network events acted on are those that end it.
- **One notification attempt.** A sink that is down misses the event.
- **TEP must reach the service** at `QOD_NOTIFICATION_DESTINATION` to report sessions the network ends.
- **Without Redis persistence**, a Redis restart forgets every session, while their AS sessions in TEP live on until TEP ends them.
