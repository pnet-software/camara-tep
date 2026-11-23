{{- /*
  The APIs this chart can deploy: values key (= service chart alias), Swagger UI name, path prefix and spec file.
  Adding an API means a dependency in Chart.yaml, a block in values.yaml and an entry here.
*/}}
{{- define "camara.apis" -}}
- key: reachability
  title: Device Reachability Status
  prefix: /device-reachability-status
  spec: /device-reachability-status.yaml
- key: roaming
  title: Device Roaming Status
  prefix: /device-roaming-status
  spec: /device-roaming-status.yaml
- key: locationverification
  title: Location Verification
  prefix: /location-verification
  spec: /location-verification.yaml
- key: deviceswap
  title: Device Swap
  prefix: /device-swap
  spec: /device-swap.yaml
- key: simswap
  title: SIM Swap
  prefix: /sim-swap
  spec: /sim-swap.yaml
- key: qosprofiles
  title: QoS Profiles
  prefix: /qos-profiles
  spec: /qos-profiles.yaml
- key: qualityondemand
  title: Quality On Demand
  prefix: /quality-on-demand
  spec: /quality-on-demand.yaml
{{- end }}

{{- /* The enabled APIs, as {apis: [...]} (fromYaml needs a map at the top) */}}
{{- define "camara.enabledApis" -}}
{{- $enabled := list }}
{{- range (include "camara.apis" . | fromYamlArray) }}
{{- if (index $.Values .key).enabled }}
{{- $enabled = append $enabled . }}
{{- end }}
{{- end }}
{{- toYaml (dict "apis" $enabled) }}
{{- end }}

{{- /* The pinned version of an API's chart (Chart.yaml's dependency). Takes (dict "root" $ "key" <api>) */}}
{{- define "camara.apiChartVersion" -}}
{{- range .root.Chart.Dependencies }}
{{- if eq (default .Name .Alias) $.key }}{{ .Version }}{{ end }}
{{- end }}
{{- end }}

{{- /* An API's Service name, as camara-lib names it in the service chart. Takes (dict "root" $ "key" <api>) */}}
{{- define "camara.apiServiceName" -}}
{{- $values := index .root.Values .key }}
{{- if $values.fullnameOverride }}
{{- $values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .key $values.nameOverride }}
{{- if contains $name .root.Release.Name }}
{{- .root.Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .root.Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- /* "true" or "false": an API's own config.authEnabled, else global.camara.auth.enabled, else true (as camara-lib) */}}
{{- define "camara.apiAuthEnabled" -}}
{{- $own := ((index .root.Values .key).config | default dict).authEnabled }}
{{- $global := dig "camara" "auth" "enabled" "" (.root.Values.global | default dict) }}
{{- if or (kindIs "bool" $own) (and (kindIs "string" $own) (ne $own "")) }}
{{- $own | toString }}
{{- else if or (kindIs "bool" $global) (and (kindIs "string" $global) (ne $global "")) }}
{{- $global | toString }}
{{- else }}
{{- "true" }}
{{- end }}
{{- end }}

{{- /* The enabled APIs that need tokens, as {apis: [...]} */}}
{{- define "camara.authApis" -}}
{{- $auth := list }}
{{- range (include "camara.enabledApis" . | fromYaml).apis }}
{{- if eq (include "camara.apiAuthEnabled" (dict "root" $ "key" .key)) "true" }}
{{- $auth = append $auth . }}
{{- end }}
{{- end }}
{{- toYaml (dict "apis" $auth) }}
{{- end }}

{{- /* "true" when this release runs Keycloak: asked for, and some enabled API needs tokens */}}
{{- define "camara.deployKeycloak" -}}
{{- if and .Values.global.camara.keycloak.deploy (include "camara.authApis" . | fromYaml).apis }}true{{ end }}
{{- end }}

{{- define "camara.keycloakName" -}}
{{- printf "%s-keycloak" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "camara.swaggerUiName" -}}
{{- printf "%s-swagger-ui" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- /* The Keycloak public URL's parts: host (without port) and path (no trailing slash; "" for the root) */}}
{{- define "camara.keycloakHost" -}}
{{- regexReplaceAll ":[0-9]+$" (urlParse .Values.global.camara.keycloak.publicUrl).host "" }}
{{- end }}

{{- define "camara.keycloakPath" -}}
{{- trimSuffix "/" (urlParse .Values.global.camara.keycloak.publicUrl).path }}
{{- end }}

{{- define "camara.realm" -}}
{{- default "camara" .Values.global.camara.keycloak.realm }}
{{- end }}

{{- /*
  Where Swagger UI is opened, so the bundled realm lets it get tokens: keycloak.realm.swaggerUiUrl, else the ingress
  host, else (Swagger UI on a fixed NodePort) Keycloak's public host with that port
*/}}
{{- define "camara.swaggerUiUrl" -}}
{{- $ui := .Values.swaggerUi.service }}
{{- if .Values.keycloak.realm.swaggerUiUrl }}
{{- trimSuffix "/" .Values.keycloak.realm.swaggerUiUrl }}
{{- else if and .Values.ingress.enabled .Values.ingress.host }}
{{- printf "%s://%s" (ternary "https" "http" (gt (len .Values.ingress.tls) 0)) .Values.ingress.host }}
{{- else if and (eq $ui.type "NodePort") $ui.nodePort .Values.global.camara.keycloak.publicUrl }}
{{- $kc := urlParse .Values.global.camara.keycloak.publicUrl }}
{{- printf "%s://%s:%v" $kc.scheme (include "camara.keycloakHost" .) $ui.nodePort }}
{{- end }}
{{- end }}

{{- define "camara.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: camara
{{- end }}

{{- define "camara.imagePullSecrets" -}}
{{- with .Values.global.camara.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{- /* How to set up an issuer of your own; shown when one is missing, and in NOTES.txt */}}
{{- define "camara.externalKeycloakSteps" -}}
To use a Keycloak (or another OpenID Connect issuer) of your own:
  1. Import charts/camara/files/camara-realm.json as a realm, or create the same client scopes in yours.
     Their names must match exactly: they are the scopes the APIs check, e.g. quality-on-demand:sessions:create.
  2. Give your API clients the scopes of the APIs they call.
  3. For three-legged tokens, add a protocol mapper putting the subscriber's phone number (E.164) in a
     phone_number claim, or set global.camara.auth.phoneNumberClaim to your claim's name.
  4. Set global.camara.auth.issuerUri to the realm's issuer exactly as tokens carry it in "iss",
     e.g. https://keycloak.example.com/realms/camara.
  5. The API pods must reach that URL: they load <issuer>/.well-known/openid-configuration and the signing keys.
{{- end }}

{{- /* Fails the install with what to set, before anything is created */}}
{{- define "camara.validate" -}}
{{- $authApis := (include "camara.authApis" . | fromYaml).apis }}
{{- if include "camara.deployKeycloak" . }}
{{- if not .Values.global.camara.keycloak.publicUrl }}
{{- fail "global.camara.keycloak.publicUrl is required when the chart deploys Keycloak: the URL clients and Swagger UI reach it at, e.g. https://camara.example.com/auth. It is the issuer in every token" }}
{{- end }}
{{- if and .Values.keycloak.ingress.enabled (not .Values.ingress.enabled) }}
{{- fail (printf "Keycloak can't be reached at %s: keycloak.ingress.enabled routes it through this chart's Ingress, but ingress.enabled is false. Either turn the Ingress on, or expose Keycloak yourself, e.g. keycloak.ingress.enabled=false, keycloak.service.type=NodePort, keycloak.service.nodePort=<the port in publicUrl>" .Values.global.camara.keycloak.publicUrl) }}
{{- end }}
{{- if and (eq .Values.keycloak.service.type "NodePort") .Values.keycloak.service.nodePort }}
{{- $port := (urlParse .Values.global.camara.keycloak.publicUrl).host | regexFind ":[0-9]+$" | trimPrefix ":" }}
{{- if ne $port (toString .Values.keycloak.service.nodePort) }}
{{- fail (printf "global.camara.keycloak.publicUrl (%s) must use Keycloak's NodePort, %v, as clients reach Keycloak there" .Values.global.camara.keycloak.publicUrl .Values.keycloak.service.nodePort) }}
{{- end }}
{{- end }}
{{- if and .Values.ingress.enabled .Values.keycloak.ingress.enabled (eq (include "camara.keycloakHost" .) .Values.ingress.host) (not (include "camara.keycloakPath" .)) .Values.swaggerUi.enabled }}
{{- fail "global.camara.keycloak.publicUrl is on the ingress host, where / is Swagger UI: give it a path, e.g. https://camara.example.com/auth" }}
{{- end }}
{{- if not (or .Values.keycloak.admin.existingSecret .Values.keycloak.admin.password) }}
{{- fail "keycloak.admin.password (or keycloak.admin.existingSecret) is required when the chart deploys Keycloak" }}
{{- end }}
{{- if and (eq .Values.keycloak.database.vendor "postgres") (not .Values.keycloak.database.postgres.url) }}
{{- fail "keycloak.database.postgres.url is required with keycloak.database.vendor=postgres, e.g. jdbc:postgresql://postgres:5432/keycloak" }}
{{- end }}
{{- if not (has .Values.keycloak.database.vendor (list "dev-file" "postgres")) }}
{{- fail "keycloak.database.vendor must be dev-file or postgres" }}
{{- end }}
{{- else if not .Values.global.camara.keycloak.deploy }}
{{- range $authApis }}
{{- $own := ((index $.Values .key).config | default dict).authIssuerUri }}
{{- if not (or $own $.Values.global.camara.auth.issuerUri) }}
{{- fail (printf "%s has auth on, but there is no issuer: set global.camara.auth.issuerUri (or global.camara.keycloak.deploy=true to run Keycloak in this release, or %s.config.authEnabled=false).\n\n%s" .key .key (include "camara.externalKeycloakSteps" $)) }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}
