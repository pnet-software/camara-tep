# Contributing

How to add a CAMARA API to this monorepo. See the [README](README.md) for the layout, the build and the release model, and its *Conventions* for commits, branching and versioning.

## Adding a service

Use `reachability` as the template. Replace `<name>` below with the service name, e.g. `sim-swap`. The name must be the same in all four places: Maven module, chart, Docker Hub repository and tag prefix.

1. **Maven module** `services/<name>/`:
   - Copy `services/reachability/pom.xml`. Change `artifactId`, `name` and `description`, and point the `openapi-generator` execution at the new spec, with packages `gr.pnet.camara.<name>.generated…`.
   - Put the CAMARA spec in `src/main/resources/static/`. The generator reads it from there, and Swagger UI serves the same file.
   - Add an application class in `gr.pnet.camara.<name>` with `@SpringBootApplication(scanBasePackages = "gr.pnet.camara")`.
   - Write the delegate. Throw `CamaraApiException` for errors; the shared handler renders them as `ErrorInfo`. Until the logic is written, throw `CamaraApiException.notImplemented()` (501).
   - Copy `application.yaml` and `application-local.yaml`. In `application.yaml`, change `spring.application.name` and `springdoc.swagger-ui.url` (the spec's file name).
2. **Auth.** Every service enforces the `openId` security its spec declares (see [Auth in a new service](#auth-in-a-new-service) below):
   - copy `security/SecurityConfig.java` and set the API path and scopes
   - resolve the device from the token first, if the API takes a `device`
   - add the scopes to the dev Keycloak realm
3. **Register the module:** add `<module>services/<name></module>` to the root `pom.xml`.
4. **Chart** `charts/<name>/`:
   - Copy `charts/reachability`.
   - In `Chart.yaml`, change `name` to `<name>-chart` (CI checks this; resources are still named `<name>`), plus `description` and `keywords`.
   - In `values.yaml`, change `image.repository` and the ingress path. Keep `config.tepBaseUrl`, `config.authEnabled`, `config.authIssuerUri` and `config.authPhoneNumberClaim` empty: the shared ConfigMap turns them into `TEP_BASE_URL` and the `AUTH_*` variables, falling back to `global.camara.*` (set by the camara chart) and then to the defaults.
   - In `templates/NOTES.txt`, change the API path, and the scope on the `Auth:` line.
   - Put any service-specific environment variables under `config.extra`. Its values, and `extraEnv` / `extraEnvFrom`, are templates, so a default can name the release's own resources, e.g. `'{{ include "camara-lib.fullname" . }}-redis'`. For a variable from a Secret that must survive a user's own `extraEnv`, use the `secretEnv` map (see the qualityondemand chart's `REDIS_PASSWORD`).
   - In the [camara chart](charts/camara/README.md), make these changes:
     - `Chart.yaml`: add a dependency `<name>-chart` with alias `<name>`, `repository: oci://registry-1.docker.io/pnetrepo`, `condition: <name>.enabled`, and the version of its first release. Until that release exists, a camara release fails.
     - `values.yaml`: add a block with `enabled: false`.
     - `templates/_helpers.tpl`: add an entry (path prefix, spec file, title) to `camara.apis`. The Ingress and Swagger UI follow from that entry.
     - If the API needs a setting to start, add a check to `camara.validate`.
5. **CI:** add a filter for `<name>` to [.github/workflows/ci.yml](.github/workflows/ci.yml), like reachability's. [release.yml](.github/workflows/release.yml) needs no change: it takes the service from the tag.
6. **Docs:** add a `README.md` for the service, and a row to the services table at the top of the [README](README.md). Copy the *Security* section of the reachability README and change the scopes.

### Auth in a new service

Most of the auth is shared. The service gets it through component scanning, with nothing to wire:

| From `camara-common` | Does |
|---|---|
| `CamaraAuthErrorHandler` | 401 `UNAUTHENTICATED` and 403 `PERMISSION_DENIED` as `ErrorInfo`. A 401 links to the API's own protected resource metadata (RFC 9728), `/.well-known/oauth-protected-resource/<api>`, where `<api>` is the request's first path segment |
| `ResourceMetadata` | `PATHS`, the metadata paths a `SecurityConfig` lets through without a token |
| `IssuerUnavailableFilter` | 503 `UNAVAILABLE` when the issuer can't be used (wrong `AUTH_ISSUER_URI`, Keycloak down) |
| `AccessTokenSubject` | The phone number a three-legged token was issued for, and the API client any token was issued to (`clientId()`: `azp`, else `client_id`) |
| `SpecOpenIdConnectUrl` | Serves the spec (`static/*.yaml`) with its `openIdConnectUrl` set to `AUTH_ISSUER_URI`'s discovery document, so Swagger UI's *Authorize* uses the service's own Keycloak |
| `SwaggerScopeFilter` | Makes *Authorize* offer only the scopes the spec's operations require, not every scope of the realm, and only the flows Swagger UI can log in with (client credentials, authorization code, password). It adds `camara/swagger-scope-filter.js` to the Swagger UI springdoc serves; the umbrella chart's Swagger UI has the same script in `charts/camara/files/`, and a `camara-common` test keeps the two identical |

The copied `pom.xml` already has `spring-boot-starter-security-oauth2-resource-server` and its `-test` starter. The copied `application.yaml` and `application-local.yaml` already have `issuer-uri: ${AUTH_ISSUER_URI:}` and `camara.auth.*`. Keep all of them as they are. That leaves these per-service steps:

1. **Security rules.** Copy [SecurityConfig.java](services/reachability/src/main/java/gr/pnet/camara/reachability/security/SecurityConfig.java) into `gr.pnet.camara.<name>.security`. Then, from the new spec:
   - `API_PATHS`: the spec's base path, e.g. `/sim-swap/**`.
   - `SCOPE`: the scope in the operation's `security:` block.

   Many CAMARA APIs have **one scope per operation**, often next to an API-wide one. SIM Swap, for instance, takes `sim-swap` or `sim-swap:check` for `/check`, and `sim-swap` or `sim-swap:retrieve-date` for `/retrieve-date`. For those, give each path its own matcher instead of `anyRequest().hasAuthority(READ_SCOPE)`, and list every scope in the metadata, as [simswap's SecurityConfig](services/simswap/src/main/java/gr/pnet/camara/simswap/security/SecurityConfig.java) does:

   ```java
   .authorizeHttpRequests(requests -> requests
           .requestMatchers(ResourceMetadata.PATHS).permitAll()
           // Any method: a wrong one then reaches MVC and gets 405, not 403
           .requestMatchers("/sim-swap/v2/check").hasAnyAuthority("SCOPE_sim-swap", "SCOPE_sim-swap:check")
           .requestMatchers("/sim-swap/v2/retrieve-date").hasAnyAuthority("SCOPE_sim-swap", "SCOPE_sim-swap:retrieve-date")
           .anyRequest().denyAll())
   // …
   m.scope("sim-swap").scope("sim-swap:check").scope("sim-swap:retrieve-date").tlsClientCertificateBoundAccessTokens(false);
   ```

   Spring Security names a scope `SCOPE_<scope>` in the token's authorities.
2. **Which device.** If the API takes a `device`, resolve it the way `ReachabilityStatusDelegate.resolveDevice` does:
   - `AccessTokenSubject.phoneNumber()` first: a three-legged token names the subscriber, and a `device` in the body as well is `CamaraApiException.unnecessaryIdentifier()`.
   - Otherwise the device comes from the body (two-legged).
3. **Spec.** Point the `openId` scheme's `openIdConnectUrl` at the dev realm, `http://localhost:8180/realms/camara/.well-known/openid-configuration`, as reachability does. That is only what is served without an issuer: with `AUTH_ISSUER_URI` set, `SpecOpenIdConnectUrl` replaces the line with that issuer's discovery document, so *Authorize* works against whichever Keycloak the service uses. It looks for a line starting `openIdConnectUrl:`, so keep the URL on that line. Also set the `apiRoot` server variable's `default` to `""`, as every spec here does: the server URL becomes relative, so Swagger UI's *Try it out* calls the API wherever the spec was loaded from, instead of CAMARA's `http://localhost:9091`.
4. **Tests.** Copy the reachability patterns:
   - **Web slice tests** ([ReachabilityStatusWebTest](services/reachability/src/test/java/gr/pnet/camara/reachability/web/ReachabilityStatusWebTest.java)): `@Import({SecurityConfig.class, CamaraAuthErrorHandler.class})`, `@MockitoBean JwtDecoder`, and `.with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_…")))` on every API request. Include the 401, 403 and 503 cases.
   - **Auth turned off:** [ReachabilityAuthDisabledWebTest](services/reachability/src/test/java/gr/pnet/camara/reachability/web/ReachabilityAuthDisabledWebTest.java).
   - **`contextLoads`:** needs `spring.security.oauth2.resourceserver.jwt.issuer-uri` set to any URL ([ReachabilityApplicationTests](services/reachability/src/test/java/gr/pnet/camara/reachability/ReachabilityApplicationTests.java)). Nothing contacts it at startup.
5. **Keycloak.** In [charts/camara/files/camara-realm.json](charts/camara/files/camara-realm.json) (the realm both the camara chart and dev/keycloak import), add each new scope:
   - a `clientScopes` entry (copy `device-reachability-status:read`)
   - the scope's name in `defaultOptionalClientScopes`, and in `optionalClientScopes` of `acme-backend` and `subscriber-app`

   Then rebuild the image. On a shared Keycloak, create the same scopes and assign them to the consumers' clients, as the reachability README's *Using an existing Keycloak* describes.

   For an end-to-end check, add `dev/keycloak/auth-test/cases/<name>.sh`: copy the closest service's file and change `SPEC`, the URLs, the scopes and the request bodies (see [dev/keycloak](dev/keycloak/README.md#adding-a-service)).

To call a TEP operation the shared client doesn't wire yet, add a bean for its generated API class (e.g. `CellsApi`; `SubscribersApi`, `QualityProfilesApi` and the two AS session with QoS APIs are wired) in `libs/tep-client/.../TepClientConfig.java`. It then shares the same `ApiClient`, with the same timeouts, correlator forwarding and metrics.
