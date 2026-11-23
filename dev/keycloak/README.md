# Local Keycloak and auth tests

For trying the services' authentication on a laptop: a Keycloak with the `camara` test realm, and an end-to-end auth test per service. Never deployed. Everything runs in Docker, so the commands are the same in bash and PowerShell. Run them from the repository root.

| File | What it is |
|---|---|
| [compose.yaml](compose.yaml) | Keycloak on port 8180, and the `auth-test` runner |
| [Dockerfile](Dockerfile) | Keycloak in dev mode with the realm from [charts/camara/files/camara-realm.json](../../charts/camara/files/camara-realm.json), the same file the camara chart uses |
| [auth-test/auth-test.sh](auth-test/auth-test.sh) | The test engine: tokens, requests, PASS/FAIL report. Its header lists every setting |
| [auth-test/cases/](auth-test/cases) | One file of checks per service |

## Keycloak

```bash
docker compose -f dev/keycloak/compose.yaml up -d --build --wait    # waits until Keycloak is ready
docker compose -f dev/keycloak/compose.yaml down
```

- **Admin console:** http://localhost:8180, `admin` / `admin`.
- **Issuer:** `http://localhost:8180/realms/camara`. This is the services' `AUTH_ISSUER_URI`. Keep port 8180, because the issuer is pinned to it.
- **Realm:** reset to the file on every start (in-memory database). After changing `camara-realm.json`, run `up` with `--build` again.

| In the realm | What it is |
|---|---|
| A client scope per API operation, e.g. `device-reachability-status:read` | Optional, so a token has a scope only when it asks for it |
| Client `acme-backend` (secret `acme-backend-secret`) | A **two-legged** consumer: client credentials, no user |
| Client `subscriber-app` (public) | A **three-legged** consumer: a subscriber logs in; adds the `phone_number` claim |
| User `alice` / `alice` | A subscriber with `phone_number` `+306912345678` |

## Auth tests

1. **Start Keycloak** as above.
2. **Start the service with auth on**, on this machine (another terminal). TEP isn't needed.

   bash:
   ```bash
   ./mvnw -DskipTests install
   AUTH_ENABLED=true AUTH_ISSUER_URI=http://localhost:8180/realms/camara \
     ./mvnw -pl services/reachability spring-boot:run -Dspring-boot.run.profiles=local
   ```

   PowerShell:
   ```powershell
   ./mvnw -DskipTests install
   $env:AUTH_ENABLED='true'; $env:AUTH_ISSUER_URI='http://localhost:8180/realms/camara'
   ./mvnw -pl services/reachability spring-boot:run '-Dspring-boot.run.profiles=local'
   ```

3. **Run its tests:**
   ```bash
   docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
   ```

The services are `reachability`, `roaming`, `locationverification`, `deviceswap`, `simswap`, `qosprofiles` and `qualityondemand`.

Each run checks:
- **401:** no token, or one that isn't a JWT.
- **403:** a token without the API's scope. Where operations have their own scopes, it also checks that each scope opens only its own operations.
- **Passing auth:** the two- and three-legged rules, i.e. which device a token may name (`MISSING_IDENTIFIER`, `UNNECESSARY_IDENTIFIER`).
- **Open endpoints:** the health probe and the spec file need no token.

Every case prints PASS, FAIL or SKIP, and the run exits non-zero on any failure. A request that gets past auth passes on any answer the API can give without or with TEP. Without TEP, that's 503 `UNAVAILABLE`. A 403 check is skipped when the client gets a scope without asking for it (assigned as *Default* instead of *Optional*), because no token can then lack it.

The runner reaches the service and Keycloak through `host.docker.internal`, so the service keeps its default ports, 9091 and 8081.

### Against another Keycloak or service

Set the variables in [auth-test.sh](auth-test/auth-test.sh)'s header in your shell, and compose passes them on. For a Keycloak with only a client-credentials client:

bash:
```bash
KEYCLOAK_URL=http://keycloak.example CLIENT_ID=my-client CLIENT_SECRET=my-secret TWO_LEGGED_ONLY=true \
  docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
```

PowerShell:
```powershell
$env:KEYCLOAK_URL='http://keycloak.example'; $env:CLIENT_ID='my-client'; $env:CLIENT_SECRET='my-secret'; $env:TWO_LEGGED_ONLY='true'
docker compose -f dev/keycloak/compose.yaml run --rm --build auth-test reachability
```

`API_ROOT` and `MANAGEMENT_URL` point the tests at a service elsewhere. The run prints the token's issuer first, and it must equal the service's `AUTH_ISSUER_URI`.

### Without Docker

With bash, curl and jq installed (Linux, macOS, or Git Bash on Windows), the engine runs directly. Its defaults are `localhost` URLs:

```bash
bash dev/keycloak/auth-test/auth-test.sh reachability
```

### Adding a service

Copy the closest file in [auth-test/cases/](auth-test/cases) to `<service>.sh`, and change `SPEC`, the URLs, the scopes and the request bodies. The engine runs the health probe and spec file checks itself.
