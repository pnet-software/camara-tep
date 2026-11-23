# camara chart

Deploys any set of the CAMARA APIs in one Helm release, each as its own released service chart, together with what they share:

- **One host.** One Ingress routes each API's path and spec file, Keycloak's path, and a Swagger UI at `/`.
- **One Swagger UI** listing the spec of every API you deployed.
- **Keycloak.** Added when an API you deployed has auth on. You choose whether it runs in this release or you use one you already have.
- **Redis.** Added for Quality On Demand. It runs in this release or points at one you already have.

Nothing is deployed until you turn an API on.

```bash
helm install camara oci://registry-1.docker.io/pnetrepo/camara-chart --version X.Y.Z -n camara --create-namespace -f my-values.yaml
```

## Which versions it runs

The chart is a list of released versions. `Chart.yaml` pins one service chart version per API, pulled from Docker Hub (`oci://registry-1.docker.io/pnetrepo`). Each of those charts runs its own release's image (its appVersion). Nothing is built for this chart; CI only checks and packages it.

- **Upgrading an API:** release the service as usual (`<service>-vX.Y.Z`), bump its version in `Chart.yaml`, then release this chart (`camara-vX.Y.Z`).
- **Running another image:** set `<api>.image.tag`, e.g. to try a build that isn't released.
- **From a checkout:** `helm dependency update charts/camara` (after `helm registry login registry-1.docker.io`, if the repositories are private), then `helm install camara charts/camara ...`. It fails if a pinned version isn't released.
- **Unreleased chart changes:** `charts/camara/local-charts.sh /tmp/camara` makes a copy that uses this repository's service charts instead of the released ones. CI lints the chart that way. Install the copy with `helm install camara /tmp/camara ...`. Its images are the repository charts' appVersion (`v1.0.0`), so set `<api>.image.tag` to the images you mean.

## Choosing and configuring the APIs

| Key | API | Path | Spec |
|---|---|---|---|
| `reachability` | Device Reachability Status | `/device-reachability-status` | `/device-reachability-status.yaml` |
| `roaming` | Device Roaming Status | `/device-roaming-status` | `/device-roaming-status.yaml` |
| `locationverification` | Location Verification | `/location-verification` | `/location-verification.yaml` |
| `deviceswap` | Device Swap | `/device-swap` | `/device-swap.yaml` |
| `simswap` | SIM Swap | `/sim-swap` | `/sim-swap.yaml` |
| `qosprofiles` | QoS Profiles | `/qos-profiles` | `/qos-profiles.yaml` |
| `qualityondemand` | Quality On Demand | `/quality-on-demand` | `/quality-on-demand.yaml` |

`<key>.enabled: true` deploys an API. Everything under the key is that service chart's own values (see `charts/<service>/values.yaml` and the service's README), e.g. `roaming.replicaCount` or `qualityondemand.config.extra.QOD_API_ROOT`.

The version an API runs is the chart version pinned in `Chart.yaml` (see above).

**Shared settings** live in `global.camara`, and an API's own `config.*` value wins over them:

| `global.camara.` | API override | |
|---|---|---|
| `tepBaseUrl` | `config.tepBaseUrl` | TEP's exposure API as the pods reach it |
| `auth.enabled` | `config.authEnabled` | Tokens required; `false` is lab only |
| `auth.issuerUri` | `config.authIssuerUri` | An issuer you already have (see below) |
| `auth.phoneNumberClaim` | `config.authPhoneNumberClaim` | The three-legged token's phone number claim |
| `imagePullSecrets` | `imagePullSecrets` | |

**What an API needs before it starts:**
- **Roaming:** nothing here: TEP says whether a device is roaming, from the home PLMNs its deployment is configured with (open5gs-em's `homePlmns`).
- **Quality On Demand:** `qualityondemand.config.extra.QOD_API_ROOT` (its public root) is worth setting.

If something required is missing, the install fails before anything is created and says what to set.

```yaml
# my-values.yaml: three APIs on camara.example.com, with this release's Keycloak and Redis
global:
  camara:
    tepBaseUrl: http://tep.tep:8080/tep-api/v1
    keycloak:
      publicUrl: https://camara.example.com/auth
reachability:
  enabled: true
roaming:
  enabled: true
qualityondemand:
  enabled: true
  config:
    extra:
      QOD_API_ROOT: https://camara.example.com
keycloak:
  admin:
    existingSecret: camara-keycloak-admin   # keys: username, password
ingress:
  enabled: true
  className: nginx
  host: camara.example.com
  tls:
    - hosts: [camara.example.com]
      secretName: camara-tls
```

## Auth and Keycloak

Auth is on for every API unless you turn it off, either globally (`global.camara.auth.enabled: false`) or for one API (`<key>.config.authEnabled: false`). If no API you deploy has auth on, no Keycloak is deployed either.

### Keycloak in this release (`global.camara.keycloak.deploy: true`, the default)

**`global.camara.keycloak.publicUrl` (required).** The address where clients and Swagger UI reach Keycloak, e.g. `https://camara.example.com/auth`:
- **Issuer.** Every token's `iss` is `<publicUrl>/realms/<realm>`, and every API checks it.
- **Base path.** The URL's path (`/auth`) is Keycloak's base path. On the ingress host, a path is required, because `/` is Swagger UI.
- **Ingress.** With `ingress.enabled`, the chart's Ingress routes that URL to Keycloak, including on a different host.
- **Without the Ingress** (`keycloak.ingress.enabled: false`), expose Keycloak another way. For example, use `keycloak.service.type: NodePort` with `nodePort: 30667` and `publicUrl: http://<node-ip>:30667/auth`.

**Signing keys.** The APIs fetch them from Keycloak's Service inside the cluster (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`). The pods therefore never need to reach the public URL, but the issuer is still checked.

**Admin user.** `keycloak.admin.password`, or `keycloak.admin.existingSecret` with keys `username` and `password`. The console is at `<publicUrl>/admin`.

**Realm.** `files/camara-realm.json` is imported on first start and left alone afterwards:
- **Scopes:** every API's scopes.
- **Test clients:** `acme-backend` (client credentials, secret `acme-backend-secret`) and `subscriber-app` (three-legged, with a `phone_number` claim).
- **Test user:** `alice` / `alice`.
- **Swagger UI:** the chart adds Swagger UI's URL (`keycloak.realm.swaggerUiUrl`, by default the ingress host) to the clients' redirect URIs and web origins, so *Authorize* works.
- **For anything shared,** change those secrets, or import a realm of your own with `keycloak.realm.existingConfigMap` (a ConfigMap holding `<realm>.json`).

**Database:**
- **`dev-file` (default):** an embedded database. Keycloak calls it lab only. With `keycloak.persistence.enabled` it is kept on a PersistentVolumeClaim; otherwise everything changed in Keycloak is lost on restart, and the realm is imported again.
- **`postgres`:** a database of yours. Set `keycloak.database.postgres.url`, `username` and `existingSecret`.

**How it runs:** one replica, in production mode (`start`) behind the ingress's TLS. It runs non-root, and its health checks use the management port.

### A Keycloak you already have (`global.camara.keycloak.deploy: false`)

Set `global.camara.auth.issuerUri`, or one API's `config.authIssuerUri`. The install fails if an API with auth on has no issuer. To set the realm up:

1. **Scopes.** Import [files/camara-realm.json](files/camara-realm.json) as a realm, or create the same client scopes in yours. Their names must match exactly, because they are the scopes the APIs check (e.g. `quality-on-demand:sessions:create`).
2. **Clients.** Give your API clients the scopes of the APIs they call.
3. **Three-legged tokens.** Add a protocol mapper that puts the subscriber's phone number (E.164) in a `phone_number` claim, or set `global.camara.auth.phoneNumberClaim` to your claim's name.
4. **Issuer.** Set `global.camara.auth.issuerUri` to the realm's issuer, exactly as tokens carry it in `iss`, e.g. `https://keycloak.example.com/realms/camara`.
5. **Reachability.** The API pods must be able to reach that URL: they load `<issuer>/.well-known/openid-configuration` and the signing keys from it.
6. **Swagger UI.** For *Authorize* there, add Swagger UI's URL to your client's redirect URIs (`<url>/*`) and web origins.

## Quality On Demand's Redis

| `qualityondemand.redis.` | |
|---|---|
| `enabled: true` (default) | A Redis pod in this release |
| `auth.enabled`, `auth.password` / `auth.existingSecret` | Require a password; the service gets it automatically |
| `persistence.enabled`, `size`, `storageClass` | Keep the sessions across Redis restarts (off: a restart forgets them) |
| `enabled: false` + `external.host`, `port`, `existingSecret`, `existingSecretKey` | A Redis you already have |

## Swagger UI and the Ingress

**Swagger UI** (`swaggerUi.enabled`):
- It is the stock `swaggerapi/swagger-ui` image, running nginx as a non-root user on a read-only filesystem.
- It lists the deployed APIs' specs. Each spec is served by its own API, with `openIdConnectUrl` set to the issuer, so *Authorize* gets tokens from the right Keycloak.
- *Authorize* offers only the selected API's scopes (and `openid`), not every scope of the realm, and only the flows Swagger UI can log in with: client credentials, authorization code and password (`files/swagger-scope-filter.js`).
- Its nginx forwards each API's spec file, path and protected resource metadata to that API's Service. Its own address therefore serves the UI, the specs and the APIs, with or without the Ingress.
- *Try it out* calls the API at the address you opened the UI at: the specs' server URLs are relative.

**Without an Ingress** (NodePorts only), expose Swagger UI and Keycloak on fixed NodePorts:

```yaml
global:
  camara:
    keycloak:
      publicUrl: http://<node-ip>:30667/auth
keycloak:
  ingress:
    enabled: false
  service:
    type: NodePort
    nodePort: 30667
swaggerUi:
  service:
    type: NodePort
    nodePort: 30668          # UI, specs and APIs at http://<node-ip>:30668/
```

The bundled realm then lets `http://<Keycloak's host>:30668` get tokens. If you open the UI at another address, set `keycloak.realm.swaggerUiUrl`.

**The Ingress** (`ingress.enabled`, `host`, `className`, `annotations`, `tls`) routes:
- each deployed API's path, and its spec file as an exact match;
- each deployed API's OAuth protected resource metadata (RFC 9728) as an exact match, e.g. `/.well-known/oauth-protected-resource/device-reachability-status`. The API's 401s link to it, and it names the issuer and that API's scopes;
- Keycloak's path;
- `/` to Swagger UI.

Not routed, so they stay inside the cluster:
- actuator (management port 8081);
- Quality On Demand's `/tep-notifications`, which TEP calls at the Service's address;
- `/.well-known/oauth-protected-resource` without an API's path. Every API answers it, so a shared host can't route it to one; the APIs' 401s link to their own paths instead.

## CI and releasing

| Job | When | What |
|---|---|---|
| `camara-lint` ([ci.yml](../../.github/workflows/ci.yml)) | Any chart change | Lints and templates the chart against this commit's service charts (`local-charts.sh`) |
| `camara-publish` ([release.yml](../../.github/workflows/release.yml)) | Tag `camara-vX.Y.Z` | Requires every pinned version, then publishes `camara-chart` version `X.Y.Z` with the pinned charts inside, and the GitHub Release |

To release: tag the services whose new versions you pinned, wait for their charts to be published, then tag `camara-vX.Y.Z`.

Adding an API to the chart: see [CONTRIBUTING.md](../../CONTRIBUTING.md).
