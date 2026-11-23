#!/bin/sh
# Copies the camara chart to <dir> with its dependencies pointing at the service charts in this repository
# instead of the released ones on Docker Hub, and vendors them. For chart changes that aren't released yet: CI lints
# them this way, and you can template or install the result.
#
#   charts/camara/local-charts.sh /tmp/camara
#   helm install camara /tmp/camara -f my-values.yaml
#
# Each service chart then runs its own appVersion's image (v1.0.0 in the repository): set <api>.image.tag to the
# image you mean to run.
set -eu

out="${1:?usage: $0 <output-dir>}"
here="$(cd "$(dirname "$0")" && pwd)"
charts="$(dirname "$here")"
rm -rf "$out"
mkdir -p "$out"
cp -R "$here/." "$out/"
rm -rf "$out/charts" "$out/Chart.lock"

services="$(awk '/^  - name: .*-chart$/ { s = $3; sub(/-chart$/, "", s); print s }' "$here/Chart.yaml")"

# The service charts, each with camara-lib vendored, copied in so a relative file:// path reaches them on any OS
mkdir -p "$out/local-src"
for service in $services; do
  helm dependency update "$charts/$service" > /dev/null
  cp -R "$charts/$service" "$out/local-src/$service"
done

# Each dependency <service>-chart comes from local-src/<service>, whatever its version there
awk '
  /^  - name: .*-chart$/ { svc = $3; sub(/-chart$/, "", svc) }
  /^    repository: / && svc != "" { print "    repository: file://local-src/" svc; next }
  /^    version: / && svc != "" { print "    version: \"*\""; next }
  { print }
' "$here/Chart.yaml" > "$out/Chart.yaml"

helm dependency update "$out" > /dev/null
echo "$out: the camara chart with this repository's service charts"
