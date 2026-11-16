#!/usr/bin/env bash
# A diagnostic baseline deliberately records known defects. This does not qualify a release.
set -euo pipefail
test "$GITHUB_ACTIONS" = true
build="$1"
app_sha="$2"
[[ "$build" =~ ^[0-9]+$ && "$app_sha" =~ ^[0-9a-f]{40}$ ]]
api="https://api.github.com/repos/$GITHUB_REPOSITORY"
mkdir -p preview results
curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build" > results/baseline-build-run.json
curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build/jobs?per_page=100" > results/baseline-build-jobs.json
jq -e '[.jobs[] | select(.name=="build") | .conclusion] == ["success"]' results/baseline-build-jobs.json >/dev/null
artifact=$(curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build/artifacts" | jq -r '.artifacts[] | select(.name=="candidate-test-input" and .expired==false) | .id')
[[ "$artifact" =~ ^[0-9]+$ ]]
curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/artifacts/$artifact/zip" -o "$RUNNER_TEMP/baseline-input.zip"
unzip -q "$RUNNER_TEMP/baseline-input.zip" -d preview
(cd preview && sha256sum -c apk.sha256)
grep -Fx "app=$app_sha" preview/provenance.txt
cp results/baseline-build-{run,jobs}.json preview/
printf '%s\n' 'Diagnostic baseline only. Native retry qualification has a known failure; signing is blocked.' > preview/baseline-limit.txt
