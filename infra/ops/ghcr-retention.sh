#!/usr/bin/env bash
# GHCR retention (decisions.md 2026-10-21): per package, keep the newest $KEEP release images
# (tag sha-<40 hex>), every image tagged deployed-* or previous-* (set by the deploy playbook), and the
# cosign signature of every kept image (tag sha256-<hex of its digest>). Everything else that carries
# only release or signature tags is deleted, but only with APPLY=true; otherwise this prints the plan.
# Untagged versions and versions with any other tag are never touched.
# Needs gh (authenticated with packages read, and delete when applying) and jq.
set -euo pipefail

OWNER="${OWNER:?set OWNER, the GHCR namespace (a GitHub user)}"
PACKAGES="${PACKAGES:-corebalt-isp-backend corebalt-isp-freeradius corebalt-isp-wireguard corebalt-isp-web}"
KEEP="${KEEP:-30}"
APPLY="${APPLY:-false}"

for package in $PACKAGES; do
    versions="$(gh api --paginate "/users/$OWNER/packages/container/$package/versions?per_page=100" \
        | jq -s 'add // []')"
    plan="$(jq --argjson keep "$KEEP" '
        def tags: (.metadata.container.tags // []);
        def release: any(tags[]; test("^sha-[0-9a-f]{40}$"));
        def pinned: any(tags[]; test("^(deployed|previous)-"));
        def signature: (tags | length) > 0 and all(tags[]; test("^sha256-[0-9a-f]{64}$"));
        def plain_release: (tags | length) > 0 and all(tags[]; test("^sha-[0-9a-f]{40}$"));
        (map(select(release)) | sort_by(.created_at) | reverse) as $releases
        | ([$releases[:$keep][], (.[] | select(pinned))] | map(.name) | unique) as $kept
        | {
            kept: $kept,
            delete: (
                [ $releases[$keep:][] | select(plain_release and (pinned | not)) ]
                + [ .[] | select(signature)
                        | select((.metadata.container.tags[0] | sub("^sha256-"; "sha256:")) as $d
                                 | ($kept | index($d)) == null) ]
            ) | map({id, name, tags: .metadata.container.tags})
          }' <<<"$versions")"
    echo "== $package: keeping $(jq '.kept | length' <<<"$plan") images, deleting $(jq '.delete | length' <<<"$plan") versions"
    jq -r '.delete[] | "   delete \(.id) \(.tags | join(","))"' <<<"$plan"
    if [ "$APPLY" = "true" ]; then
        for id in $(jq -r '.delete[].id' <<<"$plan"); do
            gh api -X DELETE "/users/$OWNER/packages/container/$package/versions/$id"
        done
    fi
done
[ "$APPLY" = "true" ] || echo "Dry run: nothing deleted (APPLY=true deletes)."
