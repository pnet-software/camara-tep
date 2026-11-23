# Local Keycloak with the "camara" test realm, for trying the services' auth on a laptop.
# Dev mode: plain HTTP, in-memory database, fixed test secrets. Never use it for a shared environment.
#
# Start it with compose.yaml (see README.md). The realm is the camara chart's (one copy for both), passed in as the
# "realm" build context; without compose:
#
#   docker build -t camara-keycloak --build-context realm=charts/camara/files dev/keycloak
#   docker run --rm -p 8180:8080 --name camara-keycloak camara-keycloak
#
# Admin console: http://localhost:8180 (admin / admin). Issuer: http://localhost:8180/realms/camara
FROM quay.io/keycloak/keycloak:26.5.4

COPY --from=realm camara-realm.json /opt/keycloak/data/import/camara-realm.json

# The issuer in every token comes from this URL. Pinning it means tokens and the service agree on
# http://localhost:8180/realms/camara however Keycloak is reached, so publish the container on 8180.
# Health is on the management port 9000, for compose's healthcheck.
ENV KC_HOSTNAME=http://localhost:8180 \
    KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    KC_HEALTH_ENABLED=true

CMD ["start-dev", "--import-realm"]
