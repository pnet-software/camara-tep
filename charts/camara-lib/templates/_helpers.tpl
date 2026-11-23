{{- define "camara-lib.name" -}}
{{- /* Service charts are named <service>-chart; resources are named after the service */}}
{{- default (.Chart.Name | trimSuffix "-chart") .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "camara-lib.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := include "camara-lib.name" . }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "camara-lib.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "camara-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ include "camara-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "camara-lib.labels" -}}
helm.sh/chart: {{ include "camara-lib.chart" . }}
{{ include "camara-lib.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: camara
{{- end }}

{{- define "camara-lib.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "camara-lib.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "camara-lib.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) }}
{{- end }}

{{- /*
  Shared settings. Each comes from the service's own value, else from global.camara (set by the camara umbrella
  chart for every API it deploys), else from a built-in default. An empty string means "not set" at each level.
*/}}
{{- define "camara-lib.global" -}}
{{- toYaml (dig "camara" dict (.Values.global | default dict)) }}
{{- end }}

{{- define "camara-lib.tepBaseUrl" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- coalesce .Values.config.tepBaseUrl (dig "tepBaseUrl" "" $g) "http://tep:8080/tep-api/v1" }}
{{- end }}

{{- /* "true" or "false"; a bool or a "true"/"false" string at either level counts as set */}}
{{- define "camara-lib.authEnabled" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- $own := .Values.config.authEnabled }}
{{- $global := dig "auth" "enabled" "" $g }}
{{- if or (kindIs "bool" $own) (and (kindIs "string" $own) (ne $own "")) }}
{{- $own | toString }}
{{- else if or (kindIs "bool" $global) (and (kindIs "string" $global) (ne $global "")) }}
{{- $global | toString }}
{{- else }}
{{- "true" }}
{{- end }}
{{- end }}

{{- /* true when the umbrella runs Keycloak in the cluster, and this service needs tokens and has no issuer of its own */}}
{{- define "camara-lib.inClusterKeycloak" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- /* Without publicUrl there is no issuer to derive; the umbrella chart fails the install and says so */}}
{{- if and (not .Values.config.authIssuerUri) (dig "keycloak" "deploy" false $g) (dig "keycloak" "publicUrl" "" $g) (eq (include "camara-lib.authEnabled" .) "true") }}true{{ end }}
{{- end }}

{{- define "camara-lib.authIssuerUri" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- if include "camara-lib.inClusterKeycloak" . }}
{{- $kc := $g.keycloak }}
{{- printf "%s/realms/%s" (trimSuffix "/" $kc.publicUrl) (default "camara" $kc.realm) }}
{{- else }}
{{- coalesce .Values.config.authIssuerUri (dig "auth" "issuerUri" "" $g) | default "" }}
{{- end }}
{{- end }}

{{- /* The in-cluster Keycloak's signing keys, fetched through its Service rather than the public URL */}}
{{- define "camara-lib.authJwkSetUri" -}}
{{- if include "camara-lib.inClusterKeycloak" . }}
{{- $kc := (include "camara-lib.global" . | fromYaml).keycloak }}
{{- $path := trimSuffix "/" (urlParse $kc.publicUrl).path }}
{{- printf "http://%s:8080%s/realms/%s/protocol/openid-connect/certs" (printf "%s-keycloak" .Release.Name | trunc 63 | trimSuffix "-") $path (default "camara" $kc.realm) }}
{{- end }}
{{- end }}

{{- define "camara-lib.authPhoneNumberClaim" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- coalesce .Values.config.authPhoneNumberClaim (dig "auth" "phoneNumberClaim" "" $g) "phone_number" }}
{{- end }}

{{- /*
  secretEnv: environment variables from Secrets, as ENV_NAME: {name, key, optional}. A map, so a chart's defaults
  survive a user's own extraEnv list. name and key are templates; an entry whose name renders empty is left out.
*/}}
{{- define "camara-lib.secretEnv" -}}
{{- range $env, $ref := (.Values.secretEnv | default dict) }}
{{- $name := tpl (toString (default "" $ref.name)) $ }}
{{- if $name }}
- name: {{ $env }}
  valueFrom:
    secretKeyRef:
      name: {{ $name | quote }}
      key: {{ tpl (toString (default "" $ref.key)) $ | quote }}
      optional: {{ default false $ref.optional }}
{{- end }}
{{- end }}
{{- end }}

{{- define "camara-lib.imagePullSecrets" -}}
{{- $g := include "camara-lib.global" . | fromYaml }}
{{- with (default (dig "imagePullSecrets" list $g) .Values.imagePullSecrets) }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}
