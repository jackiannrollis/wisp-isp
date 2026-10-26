#!/usr/bin/env bash
# Renders one router's onboarding script (docs/06 Router onboarding script) from the template
# infra/lab/routeros/onboarding.rsc.tmpl and a values file of KEY=VALUE lines, one per placeholder
# (infra/ops/onboarding.example.env lists them), on the operator's machine:
#
#   infra/ops/render-onboarding.sh <values file> > <router>.rsc
#
# Every placeholder must have a value and every value a placeholder, or nothing is rendered. The output
# holds the router's RADIUS secret, its REST password and its SNMP community: hand it over like a
# secret and delete it, with the values file, once the router has run it (runbooks/pilot-onboarding.md).
# The generator in the admin app that docs/06 describes is not built yet; this is the stand-in.
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <values file> > <router>.rsc" >&2; exit 2; }
values=$1
template="$(dirname "$(readlink -f "$0")")/../lab/routeros/onboarding.rsc.tmpl"
[ -r "$values" ] || { echo "render-onboarding: cannot read $values" >&2; exit 2; }
[ -r "$template" ] || { echo "render-onboarding: template missing: $template" >&2; exit 2; }
awk -v values="$values" '
BEGIN {
    while ((getline line < values) > 0) {
        sub(/\r$/, "", line)
        if (line ~ /^[[:space:]]*(#|$)/) continue
        eq = index(line, "=")
        if (eq < 2 || substr(line, 1, eq - 1) !~ /^[A-Z][A-Z0-9_]*$/) { msg = "bad line in " values ": " line; fatal = 1; exit 2 }
        key = substr(line, 1, eq - 1); val = substr(line, eq + 1)
        if (val == "") { msg = key " is empty"; fatal = 1; exit 2 }
        v[key] = val
    }
}
{
    line = $0
    for (key in v) {
        ph = "{{" key "}}"
        while ((i = index(line, ph)) > 0) {
            line = substr(line, 1, i - 1) v[key] substr(line, i + length(ph))
            used[key] = 1
        }
    }
    if (match(line, /\{\{[A-Z0-9_]+\}\}/)) { msg = msg "no value for " substr(line, RSTART + 2, RLENGTH - 4) " (template line " NR ")\n"; bad = 1 }
    out = out line "\n"
}
END {
    if (fatal) { print "render-onboarding: " msg > "/dev/stderr"; exit 2 }
    for (key in v) if (!(key in used)) { msg = msg key " is not a placeholder of the template\n"; bad = 1 }
    if (bad) { printf "render-onboarding: %s", msg > "/dev/stderr"; exit 2 }
    printf "%s", out
}' "$template"
