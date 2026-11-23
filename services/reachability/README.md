# reachability

> Part of the CAMARA monorepo; see the [root README](../../README.md) for the shared libraries, layout and release model. Paths below are relative to the repository root unless they are links.

Implementation of the CAMARA **Device Reachability Status** API, **v1.1.0** (Commonalities 0.6). It tells an API consumer whether a device is reachable over **DATA** and/or **SMS**, based on the device's live state in the 5G core. The service gets that state from the **TEP** (Telco Exposure & Programmability) API.

Every call needs an OAuth2 access token with the scope `device-reachability-status:read`, as the spec declares. See [Security](#security).

## How it works

```
API consumer ──POST /device-reachability-status/v1/retrieve──▶ reachability ──POST /subscriber/status──▶ TEP ──▶ 5G core
             Authorization: Bearer <token>
```

1. The device is identified in one of two ways, depending on the token (see [Security](#security)):
   - **two-legged token:** by `device.phoneNumber` in the body (E.164, e.g. `+306912345678`)
   - **three-legged token:** by the subscriber the token was issued for; the body must not contain `device`
2. The service calls TEP `POST /subscriber/status` with the MSISDN unchanged, including the `+`, in the body as a `TepDevice` (`{"phoneNumber": "+30…"}`), so it never appears in a URL.
3. It maps the TEP subscriber status by its `connectionState`, then its PDU sessions' `dnn`:

   | TEP status | CAMARA result |
   |---|---|
   | `connectionState` `DEREGISTERED` (the UE isn't registered with the core) | `reachable: false`, whatever its sessions |
   | Otherwise (`CONNECTED`, or `IDLE`, which the network pages), a PDU session with any `dnn` other than `ims` (or none) | `DATA` |
   | Otherwise, a PDU session with `dnn` `ims` (any case) | `SMS` |
   | No PDU sessions | `reachable: false` |

   Each type is listed once, `DATA` first.

4. `lastStatusTime` is the time of the request, because TEP exposes no status timestamp.
5. `device` is echoed in the response only when the request contained more than one identifier, as the spec requires.

### Example

```bash
curl -s -X POST http://localhost:9091/device-reachability-status/v1/retrieve \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'x-correlator: b4333c46-49c0-4f62-80d7-f0ef930f1c46' \
  -d '{"device":{"phoneNumber":"+306912345678"}}'
# {"lastStatusTime":"2026-10-20T12:00:00Z","reachable":true,"connectivity":["DATA","SMS"]}
```

See [Testing auth with a local Keycloak](#testing-auth-with-a-local-keycloak) for how to get a `$TOKEN`.

### Errors

Every error is a CAMARA `ErrorInfo` body: `{"status":…, "code":"…", "message":"…"}`.

| Status | Code | When |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | Malformed JSON, phone number not in E.164 format, invalid `x-correlator` |
| 401 | `UNAUTHENTICATED` | No access token, or one that is malformed, expired, wrongly signed or from another issuer |
| 403 | `PERMISSION_DENIED` | A valid token without the `device-reachability-status:read` scope |
| 404 | `IDENTIFIER_NOT_FOUND` | TEP doesn't know the subscriber |
| 404 | `NOT_FOUND` | Unknown path |
| 422 | `MISSING_IDENTIFIER` | Two-legged token, and no `device` or a `device` with no identifiers |
| 422 | `UNNECESSARY_IDENTIFIER` | Three-legged token, and the body also contains a `device` (even the same one) |
| 422 | `UNSUPPORTED_IDENTIFIER` | Only `ipv4Address`, `ipv6Address` or `networkAccessIdentifier` given (only `phoneNumber` is supported) |
| 503 | `UNAVAILABLE` | TEP is unreachable, times out, or returns any other error. Also when a token can't be checked because the issuer (`AUTH_ISSUER_URI`) is wrong or unreachable |
| 500 | `INTERNAL` | Unexpected server error (a bug) |

A valid `x-correlator` is echoed on every response, 401 and 403 included, and forwarded to TEP. An invalid one is rejected with a 400 and not echoed.

## Security

The service is an OAuth2 **resource server**. It doesn't issue tokens; an authorization server does (Keycloak, or the operator's own), and the service only checks them:

1. **Token check.** Spring Security reads the issuer's discovery document (`AUTH_ISSUER_URI` + `/.well-known/openid-configuration`), fetches its signing keys, and checks each token's signature, expiry and issuer. A missing or bad token gets 401 `UNAUTHENTICATED`.
2. **Scope check.** The token must carry `device-reachability-status:read`. Without it the request gets 403 `PERMISSION_DENIED`.
3. **Who the request is about** (`ReachabilityStatusDelegate.resolveDevice`):
   - **Two-legged** token (client credentials: an application acting for itself): the token names no subscriber, so `device.phoneNumber` must be in the body.
   - **Three-legged** token (a subscriber logged in and consented): the phone number comes from the token's `phone_number` claim (`AUTH_PHONE_NUMBER_CLAIM`), and a `device` in the body is rejected with `UNNECESSARY_IDENTIFIER`.

   A token counts as three-legged when it has that claim. The authorization server decides that, so check what claim your issuer uses for the subscriber's number.

**What needs no token:**
- Swagger UI (`/`), the spec file, and `/.well-known/oauth-protected-resource/device-reachability-status`. That last one is RFC 9728 metadata naming the issuer and the scope; every 401's `WWW-Authenticate` header links to it. The path ends with the API's, so one host can serve every API's metadata.
- Actuator, which runs on its own management port that the chart's Ingress never exposes.

**Other protections:**
- Phone numbers are masked in logs, e.g. `+3069******78`.
- The container runs as a non-root user (UID 10001) with a read-only root filesystem.
- The service holds no secrets: checking tokens only needs the issuer's public keys.

**Turning it off.** `AUTH_ENABLED=false` lets any caller query any subscriber, using the two-legged rules. It is for labs without an authorization server. The `local` profile defaults to it, so `spring-boot:run` works without Keycloak.

**Not covered:**
- Calls to TEP are unauthenticated. Adding that would mean one more interceptor in `TepClientConfig` (`tep-client`), shared by every service.
- Tokens aren't checked for an audience, so a token from the same issuer for another API with the same scope name would be accepted. To add the check, set `spring.security.oauth2.resourceserver.jwt.audiences`.

### Using an existing Keycloak

[dev/keycloak](../../dev/keycloak) is for laptops only. For a shared Keycloak, an admin sets these up once. This covers two-legged consumers; three-legged ones also need the `phone_number` claim, as `subscriber-app` has in [camara-realm.json](../../charts/camara/files/camara-realm.json).

1. **Realm.** A dedicated `camara` realm keeps the API's clients apart; an existing realm works too. Its issuer is the `issuer` field behind *Realm settings → OpenID Endpoint Configuration*, typically `http://<keycloak>/realms/camara`. That exact string is `AUTH_ISSUER_URI`.
2. **Client scope.** *Client scopes → Create client scope*:
   - name `device-reachability-status:read`
   - type *None*, protocol *OpenID Connect*
   - **Include in token scope** on
3. **One client per API consumer.** *Clients → Create client*:
   - **Client authentication** on, **Service account roles** on; *Standard flow* and *Direct access grants* off
   - in its *Client scopes* tab, add `device-reachability-status:read` as **Optional**, so a token carries the scope only when the consumer asks for it
   - the *Credentials* tab shows the secret to hand to the consumer
4. **Plain HTTP.** *Realm settings → General → Require SSL* must allow HTTP from wherever tokens are requested, or Keycloak answers `HTTPS required`. Over HTTP, tokens cross the network unencrypted, and anyone who captures one can use it until it expires (the realm's access token lifespan). That is acceptable on a lab network only.

**Run and test locally against it** (PowerShell, from the repository root):

```powershell
$env:AUTH_ENABLED='true'; $env:AUTH_ISSUER_URI='http://<keycloak>/realms/camara'
./mvnw -pl services/reachability spring-boot:run '-Dspring-boot.run.profiles=local'

# another terminal
$env:KEYCLOAK_URL='http://<keycloak>'; $env:CLIENT_ID='<client>'; $env:CLIENT_SECRET='<secret>'; $env:TWO_LEGGED_ONLY='true'
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
```

In bash, set the same variables before `docker compose`. The test prints the token's issuer first; it must equal `AUTH_ISSUER_URI`. See [dev/keycloak](../../dev/keycloak/README.md#against-another-keycloak-or-service).

**Deploy** with the issuer set (see [Container image and Helm chart](#container-image-and-helm-chart)):

```bash
helm upgrade --install reachability oci://registry-1.docker.io/pnetrepo/reachability-chart --version <version> \
  -n camara --create-namespace \
  --set config.tepBaseUrl=http://tep.tep.svc:8080/tep-api/v1 \
  --set config.authIssuerUri=http://<keycloak>/realms/camara
```

The pods must be able to reach that exact URL. To test a deployment, run `kubectl -n camara port-forward svc/reachability 8080 8081`, then run the script as above, adding `-ApiUrl http://localhost:8080/device-reachability-status/v1/retrieve -ManagementUrl http://localhost:8081`.

**Troubleshooting:**

| Symptom | Likely cause |
|---|---|
| Keycloak answers `invalid_client` or `unauthorized_client` | Wrong client secret, or *Service account roles* is off |
| Keycloak answers `invalid_scope` | The scope isn't assigned to the client |
| Keycloak answers `HTTPS required` | *Require SSL* (step 4) |
| Every request with a token gets 503 `UNAVAILABLE` (requests without one still get 401) | The service couldn't load the issuer. Its log line `UNAVAILABLE: token issuer unusable: …` says which case it is. `The Issuer "…" provided in the configuration did not match the requested issuer "…"`: `AUTH_ISSUER_URI` differs from Keycloak's own issuer (compare with the issuer the script prints). `Unable to resolve the Configuration with the provided Issuer of "…"`: the service can't reach Keycloak at that URL |
| 403 `PERMISSION_DENIED` | The token doesn't carry the scope: request it with `scope=device-reachability-status:read` |
| The script shows SKIP for "without the scope" | The scope was added to the client as *Default*: every token of that client carries it, so there's no token to test the 403 with |

## Configuration

All settings are environment variables. They map to [application.yaml](src/main/resources/application.yaml), and the Helm chart sets them through a ConfigMap. The `TEP_*` settings are read by the shared `tep-client` module and mean the same in every service.

| Variable | Default | Purpose |
|---|---|---|
| `TEP_BASE_URL` | `http://localhost:9999/tep-api/v1` | TEP API base URL |
| `TEP_CONNECT_TIMEOUT` | `2s` | TCP connect timeout to TEP |
| `TEP_READ_TIMEOUT` | `5s` | Response timeout from TEP |
| `SERVER_PORT` | `9091` | API port; the Helm chart sets `8080`. Swagger UI's "Try it out" calls the address the spec was loaded from, on any port |
| `MANAGEMENT_PORT` | `8081` | Actuator port (health, info, metrics) |
| `LOG_FORMAT` | `ecs` | Structured JSON logs (ECS). The `local` profile switches to plain text |
| `SPRINGDOC_ENABLED` | `true` | Swagger UI (at `/`, showing the CAMARA spec) and `/v3/api-docs`. The spec file itself, `/device-reachability-status.yaml`, is a static resource and is served either way |
| `SHUTDOWN_TIMEOUT` | `20s` | Graceful shutdown window |
| `AUTH_ENABLED` | `true` (`false` in the `local` profile) | Require access tokens. `false` is for labs only |
| `AUTH_ISSUER_URI` | none | The authorization server's issuer, exactly as it appears in tokens' `iss`, e.g. `http://localhost:8180/realms/camara`, or `http://<host>/auth/realms/camara` for a Keycloak that serves its realms under `/auth`. Required when `AUTH_ENABLED=true`: without it the service refuses to start. The spec is also served with its `openIdConnectUrl` set to this issuer's discovery document, so it must be reachable from the browser for Swagger UI's *Authorize* |
| `AUTH_PHONE_NUMBER_CLAIM` | `phone_number` | Claim that carries the subscriber's phone number in three-legged tokens |

## Running locally

Requirements: JDK 17+. Maven comes through the bundled wrapper. Run these from the repository root.

```bash
./mvnw -DskipTests install                                        # once, and again after changing libs/
./mvnw -pl services/reachability spring-boot:run -Dspring-boot.run.profiles=local   # TEP expected on localhost:9999
TEP_BASE_URL=http://tep.lab:8080/tep-api/v1 ./mvnw -pl services/reachability spring-boot:run
```

`spring-boot:run` can't be combined with `-am`: Maven would try to run the libraries too. That's why the libraries are installed into the local Maven repository first.

On Windows PowerShell, quote any `-D` argument that contains dots, e.g. `'-Drevision=1.0.0'`.

The `local` profile runs without auth. To run with it, see the next section.

### Testing auth with a local Keycloak

[dev/keycloak](../../dev/keycloak) holds a Keycloak image with a ready-made `camara` realm:

| In the realm | What it is |
|---|---|
| Client scope `device-reachability-status:read` | The scope the spec requires. It is optional, so a token has it only when it is asked for |
| Client `acme-backend` (secret `acme-backend-secret`) | A **two-legged** consumer: client credentials, no user |
| Client `subscriber-app` (public) | A **three-legged** consumer: a subscriber logs in; adds the `phone_number` claim |
| User `alice` / `alice` | A subscriber with `phone_number` `+306912345678` |

Run these three steps from the repository root (PowerShell shown for step 2; bash is in [dev/keycloak](../../dev/keycloak/README.md#auth-tests)):

```powershell
# 1. Keycloak on http://localhost:8180 (admin console: admin / admin). Keep the 8180 port: the issuer is pinned to it.
docker compose -f dev/keycloak/compose.yaml up -d --build --wait

# 2. The service, with auth on (another terminal). TEP isn't needed for the auth checks.
./mvnw -DskipTests install
$env:AUTH_ENABLED='true'; $env:AUTH_ISSUER_URI='http://localhost:8180/realms/camara'
./mvnw -pl services/reachability spring-boot:run '-Dspring-boot.run.profiles=local'

# 3. The checks: 401, 403, the two- and three-legged rules, and the open endpoints
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
```

The test prints each case as PASS or FAIL and exits non-zero on any failure. Without TEP, calls that get past auth end in 503 `UNAVAILABLE`, which counts as passing. To test against another Keycloak, see [Using an existing Keycloak](#using-an-existing-keycloak). Stop Keycloak with `docker compose -f dev/keycloak/compose.yaml down`.

To try it by hand (bash):

```bash
TOKEN=$(curl -s http://localhost:8180/realms/camara/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=acme-backend -d client_secret=acme-backend-secret \
  -d scope=device-reachability-status:read | jq -r .access_token)
curl -s -X POST http://localhost:9091/device-reachability-status/v1/retrieve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"device":{"phoneNumber":"+306912345678"}}'
```

For a three-legged token, use `-d grant_type=password -d client_id=subscriber-app -d username=alice -d password=alice -d 'scope=openid device-reachability-status:read'`, and send `{}` as the body. The password grant only stands in for a subscriber logging in; real three-legged apps use a browser flow.

The spec is served with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, here this realm's, so **Authorize** in Swagger UI (http://localhost:9091) can also fetch a token, offering only this API's scopes and the flows it can log in with (client credentials, authorization code, password), e.g. client credentials with `acme-backend`.

### Operations endpoints (port 8081)

| Endpoint | Purpose |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | Kubernetes probes. Readiness doesn't depend on TEP; a TEP outage gives 503s, not unready pods |
| `/actuator/info` | Build version and git commit |
| `/actuator/prometheus` | Metrics (see below) |

Metrics:
- **`http_server_requests_seconds{method,uri,status,code,…}`**: every API request, by route, HTTP status and CAMARA error code, e.g. `code="IDENTIFIER_NOT_FOUND"`; a success is `code="none"`.
- **`http_client_requests_seconds{uri="/tep-api/v1/subscriber/status",…}`**: latency and outcome of TEP calls. Phone numbers are in the request body, so they never appear in the `uri` tag.

Every request and TEP call is logged; see [Logging](../../README.md#logging).

## Tests

```bash
./mvnw -pl services/reachability -am verify
```

`verify` runs these tests:
- **Unit tests:** mapping rules, delegate logic.
- **Web slice tests:** every error code, the correlator, malformed input, all through the real generated controller and the real security rules (401, 403, open paths, auth turned off).
- **Libraries (via `-am`):** `camara-common` (error rendering, correlator, masking) and `tep-client` (`TepClientIT`: URL encoding, Jackson 3 deserialization, correlator forwarding, timeouts).

A JaCoCo gate fails the build if line coverage of non-generated code drops below **80%**. The gate applies to each module separately and counts unit and integration tests together.

## Code generation

The CAMARA server layer is generated at build time by `openapi-generator-maven-plugin`. Edit the spec, not the generated code.

| Spec | Generated into | Notes |
|---|---|---|
| [device-reachability-status.yaml](src/main/resources/static/device-reachability-status.yaml) | `target/generated-sources/reachability-server` | Spring server, delegate pattern; the logic lives in `ReachabilityStatusDelegate` |

The TEP client is generated in the shared [`tep-client`](../../libs/tep-client) module.

The spec lives in `src/main/resources/static/`, so the same file is both the generator input and what Swagger UI shows (`springdoc.swagger-ui.url`). There is one copy, so the documented contract can't drift from the implemented one.

## Container image and Helm chart

| Artifact | Location |
|---|---|
| Image | `docker.io/pnetrepo/reachability:<tag>` (tags: `vX.Y.Z`, short SHA, `latest`) |
| Chart | `oci://registry-1.docker.io/pnetrepo/reachability-chart`, version `X.Y.Z` (appVersion `vX.Y.Z`) |

```bash
helm install reachability oci://registry-1.docker.io/pnetrepo/reachability-chart --version 1.0.0 \
  -n camara --create-namespace \
  --set config.tepBaseUrl=http://tep.tep.svc:8080/tep-api/v1 \
  --set config.authIssuerUri=https://keycloak.example/realms/camara   # or --set config.authEnabled=false (lab only)
```

The pods must be able to reach the issuer URL to fetch its signing keys. The URL must also match the tokens' `iss` exactly, which is easy to get wrong when consumers reach Keycloak by an external hostname and the pods by an in-cluster one. Pin Keycloak's hostname (`KC_HOSTNAME`) so the two agree.

The chart lives in [charts/reachability](../../charts/reachability). Its templates come from the shared [camara-lib](../../charts/camara-lib) library chart. Its notable values:

| Value | Purpose |
|---|---|
| `config.*` | Application settings (see [Configuration](#configuration)) |
| `config.authEnabled`, `config.authIssuerUri`, `config.authPhoneNumberClaim` | Auth settings. `authIssuerUri` has no default: set it, or turn auth off. The install notes say which of the two the release uses |
| `config.extra` | Service-specific settings, added to the ConfigMap as `ENV_NAME: value` |
| `ingress.*` | Disabled by default; routes only the API port |
| `imagePullSecrets` | Pull secrets, if the Docker Hub repositories are private |
| `autoscaling.*` | HPA, off by default |
| `podDisruptionBudget.*` | PDB, `minAvailable: 1` |
| `serviceMonitor.enabled` | Prometheus Operator scraping of the management port |
| `extraEnv` / `extraEnvFrom` | Extra settings and secrets |

Pods use these defaults:
- 2 replicas, non-root, read-only root filesystem, all capabilities dropped
- probes on the management port
- a 5 s preStop delay, followed by graceful shutdown

To build the image locally (Jib, no Docker daemon needed):

```bash
# package, not compile: Jib needs the library jars (see .github/workflows/release.yml)
./mvnw -pl services/reachability -am -DskipTests package jib:buildTar      # → services/reachability/target/jib-image.tar
./mvnw -pl services/reachability -am -DskipTests package jib:dockerBuild   # straight into the local Docker daemon
```

## CI and releases

The jobs are GitHub Actions: [ci.yml](../../.github/workflows/ci.yml) for pull requests and `main`, and [release.yml](../../.github/workflows/release.yml) for tags.

| Job | Runs on | Does |
|---|---|---|
| `test` | Pull requests and pushes to `main` that touch this service or shared code; `reachability-v*` tags | `mvn -pl services/reachability -am verify`, with the coverage in the job summary |
| `helm-lint` | same as `test` | `helm lint` / `helm template`, including [ci-values.yaml](../../charts/reachability/ci-values.yaml) |
| `image` | tags `reachability-vX.Y.Z` | Jib build and push to Docker Hub |
| `helm-publish` | tags `reachability-vX.Y.Z` | `helm package` (version taken from the tag), `helm push`, and the GitHub Release |

**To cut a release:** tag and push, `git tag -a reachability-v1.0.0 -m "reachability v1.0.0" && git push origin reachability-v1.0.0`.

The tag is the only source of the release version: Maven gets `-Drevision=1.0.0`, the image is tagged `v1.0.0`, and the chart gets version `1.0.0`. No other service is built or published.

## Known limitations

- **No audience check on tokens, and no auth on calls to TEP;** see [Security](#security).
- **Only `phoneNumber` is supported** as a device identifier.
- **Reachability comes from registration and PDU sessions.** An `IDLE` UE counts as reachable through its sessions, and a UE that is registered but has no PDU session is reported not reachable, so SMS over NAS isn't detected. The IMS DNN is fixed to `ims`.
- **`lastStatusTime` is the time of the request,** not the time the network last observed the status.
- **Readiness does not reflect TEP availability.** When TEP is down, requests get 503 `UNAVAILABLE`.

## Project layout

```
services/reachability/
  src/main/resources/static/  CAMARA Device Reachability Status spec: server generated from it, served to Swagger UI
  src/main/java/…/delegate    API logic: device resolution, TEP call, reachability mapping
  src/main/java/…/security    Which paths need a token, and which scope
charts/reachability/          Helm chart (values and NOTES; templates from charts/camara-lib)
```

The error handling, correlator filter, log masking and TEP client wiring are shared modules under `libs/`; see the [root README](../../README.md).
