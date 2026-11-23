# camara-tep

Implementations of [CAMARA](https://camaraproject.org/) APIs backed by the **TEP** (Telco Exposure & Programmability) API of a 5G core. Every service is a Spring Boot app: its server layer is generated from a CAMARA spec, and it calls TEP through the same generated client.

Each service enforces the `openId` security its spec declares: an OAuth2 access token with the API's scope, checked against a configured issuer. [dev/keycloak](dev/keycloak) has a local Keycloak to try it with. See each service's README.

Each service has its own image and chart. The [camara chart](charts/camara/README.md) deploys any set of them in one release: one Ingress host, one Swagger UI for all their specs, and the Keycloak and Redis they need, in the cluster or external.

| Service | CAMARA API | README |
|---|---|---|
| `reachability` | Device Reachability Status v1.1.0 | [services/reachability](services/reachability/README.md) |
| `roaming` | Device Roaming Status v1.1.0 | [services/roaming](services/roaming/README.md) |
| `locationverification` | Device Location Verification v3.0.0 | [services/locationverification](services/locationverification/README.md) |
| `deviceswap` | Device Swap v1.1.0 | [services/deviceswap](services/deviceswap/README.md) |
| `simswap` | SIM Swap v2.1.0 | [services/simswap](services/simswap/README.md) |
| `qosprofiles` | QoS Profiles v1.1.0 | [services/qosprofiles](services/qosprofiles/README.md) |
| `qualityondemand` | Quality-On-Demand v1.1.0 | [services/qualityondemand](services/qualityondemand/README.md) |

## Layout

```
pom.xml                     Parent and aggregator: Spring Boot parent, plugin config, coverage gate, module list
libs/
  camara-common/            CAMARA Commonalities: ErrorInfo errors and global handler, x-correlator filter, log masking;
                            auth: ErrorInfo 401/403, 503 when the issuer is unusable, the three-legged token's subscriber
  tep-client/               TEP client generated from the TEP spec (src/main/openapi), RestClient wiring, TEP_* config
services/
  <service>/                One CAMARA API: spec, delegate logic, application.yaml, README
charts/
  camara-lib/               Helm library chart: hardened Deployment, Service, ConfigMap, Ingress, HPA, PDB, ServiceMonitor
  <service>/                Thin chart: values, NOTES and one include of camara-lib
  camara/                   Umbrella chart: any set of the service charts, plus Swagger UI, Keycloak and Redis as needed;
                            files/camara-realm.json is the "camara" realm (scopes, test clients), also used by dev/keycloak
dev/keycloak/               Local Keycloak and an auth test per service, all run with docker compose; never deployed
.github/workflows/          ci.yml: tests and lints what a change touches; release.yml: one release per tag
```

What lives where:
- **Shared code (`libs/`):** anything that would be the same in every TEP-backed CAMARA service. Services get it through component scanning, because each application class scans `gr.pnet.camara`.
- **Service code (`services/<name>/`):** only what is specific to one API. That means the generated server, the delegate that turns a CAMARA request into TEP calls, and the mapping back.

## Building

Requirements: JDK 17+. Maven comes through the bundled wrapper.

```bash
./mvnw verify                                   # everything: all libraries and services
./mvnw -pl services/reachability -am verify     # one service plus the libraries it depends on
```

The whole reactor shares one version (`${revision}`). The libraries are built in the same reactor as the services and are never published to a Maven repository. Each service image therefore contains exactly the library code from the commit it was tagged on.

Each module has its own 80% line-coverage gate. Generated code is excluded, and unit and integration tests count together.

## Releases: one service at a time

Each service is released by its own tag, `<service>-vX.Y.Z`:

```bash
git tag -a reachability-v1.0.0 -m "reachability v1.0.0" && git push origin reachability-v1.0.0
```

| Tag `reachability-v1.0.0` produces | |
|---|---|
| Maven version | `1.0.0` (`-Drevision`) |
| Image | `docker.io/pnetrepo/reachability:v1.0.0` (also short SHA, `latest`) |
| Chart | `oci://registry-1.docker.io/pnetrepo/reachability-chart`, version `1.0.0`, appVersion `v1.0.0` |
| GitHub Release | `reachability-v1.0.0`, with notes generated from the commits |

Only that service's jobs run on the tag, and no other service is rebuilt or republished. Services are versioned independently, so `reachability` can be at 1.4.0 while a newer service is at 1.0.0.

In pull requests and pushes to `main`, a service's `test` and `helm-lint` jobs run when one of these paths changes:
- the service's own files (`services/<name>/`, `charts/<name>/`)
- shared code (`libs/`, `charts/camara-lib/`, the root `pom.xml`, `.mvn/`, CI config)

A change to shared code therefore tests every service. It doesn't release any of them: you choose which services to tag.

**The camara chart** is released the same way, by a tag `camara-vX.Y.Z`. It publishes `oci://registry-1.docker.io/pnetrepo/camara-chart` version `X.Y.Z`, with no image or build of its own:
- **What it contains:** the service chart versions pinned in [charts/camara/Chart.yaml](charts/camara/Chart.yaml), pulled from Docker Hub. Each runs its own release's image.
- **Upgrading an API in it:** release the service, bump its version there, then tag `camara-vX.Y.Z`.
- **CI:** on any chart change, it lints the umbrella against this commit's charts. The release job requires every pinned version to exist on Docker Hub.

**Pipeline setup:**
- GitHub Actions: [ci.yml](.github/workflows/ci.yml) for pull requests and `main`, [release.yml](.github/workflows/release.yml) for tags. Image and chart names come from the tag's service, not from the repository name.
- Organization secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`, and the variable `DOCKERHUB_NAMESPACE` (`pnetrepo`).
- Protect tags matching `*-v*` with a tag ruleset.

## Adding a service

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Logging

Every service logs the same way, through `camara-common` and `tep-client`. Logs are ECS JSON on stdout by default (`LOG_FORMAT=ecs`); `LOG_FORMAT=` (empty) or the `local` profile gives plain text.

**One line per API request**, once it's answered (`AccessLogFilter`). Actuator, Swagger UI and the spec files are left out (`camara.access-log.exclude`); `ACCESS_LOG_ENABLED=false` turns it off.

```
POST /device-reachability-status/v1/retrieve 400 INVALID_ARGUMENT 3ms
```

| Field | Content |
|---|---|
| `http.request.method`, `url.path` | The path only, never the query string; a phone number in it is masked |
| `http.route` | The matched route template, when a controller answered |
| `http.response.status_code` | The HTTP status |
| `error.code`, `error.type` | The CAMARA error code answered and the exception behind it, if any |
| `event.duration` | Nanoseconds |
| `clientId` | The API client the token was issued to (`azp`, else `client_id`) |

**One line per TEP call** (logger `gr.pnet.camara.tep.TepCalls`): `TEP POST /tep-api/v1/subscriber/status 200 34ms`, with `tep.method`, `tep.status_code` and `event.duration`. `LOGGING_LEVEL_GR_PNET_CAMARA_TEP_TEPCALLS=WARN` keeps only the calls TEP didn't answer.

**On every line of a request** (MDC): `correlator`, the request's `x-correlator` or a generated UUID, and `clientId`. The correlator is also forwarded to TEP and to QoD sinks, so one value finds a request across the API, TEP and the notification. TEP 1.0.0 declares `x-correlator` on its operations: services pass `TepCorrelator.current()` to each call, the client's interceptor adds it to the 3GPP AsSessionWithQoS calls, and TEP logs the request under it and echoes it back.

| Level | When |
|---|---|
| INFO | Requests answered below 500, TEP calls, QoD session lifecycle and notifications |
| WARN | Requests answered 5xx (plus a line with the cause), TEP calls with no answer, failed notifications, auth disabled at startup |
| ERROR | Unhandled errors, with the stack trace: a bug |

**Never logged:** request or response bodies, query strings, tokens, sink URLs (only their host), exception messages from a request (the 5xx cause line masks phone numbers). Phone numbers that do appear are masked, e.g. `+3069******78`.

## Conventions

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `ci:`, `test:`, `docs:`, `chore:`). Use the service or library name as the scope, e.g. `feat(reachability): …`, `fix(tep-client): …`.
- **Branching:** trunk-based on `main`, using short-lived feature branches and pull requests.
- **Versioning:** SemVer per service. Releases come only from `<service>-vX.Y.Z` tags. Between releases, `main` builds `1.0.0-SNAPSHOT`.
- **Release notes:** each tag's GitHub Release, generated from the commits since the previous tag.

## License

Copyright 2026 P-NET New Generation Emerging Networks & Verticals Private Company.

Licensed under the [Apache License, Version 2.0](LICENSE). You may use, modify and redistribute this work, provided you keep the copyright and license notices and include the [NOTICE](NOTICE) file in what you distribute. The CAMARA and 3GPP API specifications in this repository keep their own licenses and copyright; see [NOTICE](NOTICE).
