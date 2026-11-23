// Swagger UI's responseInterceptor, on the openIdConnectUrl's discovery document, which Authorize is built from:
// - scopes_supported lists every scope of the realm, of every API. This keeps the ones the open spec's operations
//   require, and openid.
// - grant_types_supported gets a box each in Authorize, most of which Swagger UI can't log in with (refresh_token,
//   device code, token exchange, CIBA...). This keeps the ones it can: client credentials (two-legged), and
//   authorization code and password (three-legged, a subscriber logs in).
// Kept identical in libs/camara-common/src/main/resources/camara/swagger-scope-filter.js (each service's Swagger
// UI) and charts/camara/files/swagger-scope-filter.js (the umbrella chart's); a camara-common test checks.
(response) => {
  if (!response || !response.ok || typeof response.text !== "string" || !window.ui
      || !/\/\.well-known\/openid-configuration([?#].*)?$/.test(response.url || "")) {
    return response;
  }
  try {
    const discovery = JSON.parse(response.text);
    if (Array.isArray(discovery.scopes_supported)) {
      const spec = window.ui.specSelectors.specJson().toJS();
      const wanted = new Set(["openid"]);
      const add = (security) => (security || []).forEach((requirement) =>
          Object.values(requirement || {}).forEach((scopes) => (scopes || []).forEach((scope) => wanted.add(scope))));
      add(spec.security);
      Object.values(spec.paths || {}).forEach((pathItem) =>
          Object.values(pathItem || {}).forEach((operation) => add(operation && operation.security)));
      discovery.scopes_supported = discovery.scopes_supported.filter((scope) => wanted.has(scope));
    }
    if (Array.isArray(discovery.grant_types_supported)) {
      const flows = ["client_credentials", "authorization_code", "password"];
      const offered = discovery.grant_types_supported.filter((grant) => flows.includes(grant));
      // None of them: as the issuer lists them, as an empty list would leave Authorize without a box
      if (offered.length) {
        discovery.grant_types_supported = offered;
      }
    }
    response.text = JSON.stringify(discovery);
  } catch (e) {
    // Not the document expected: Authorize shows it unfiltered
  }
  return response;
}
