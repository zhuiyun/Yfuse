set -euo pipefail
test "$GITHUB_ACTIONS" = true
build="$1"
app_sha="$2"
[[ "$build" =~ ^[0-9]+$ && "$app_sha" =~ ^[0-9a-f]{40}$ ]]
api="https://api.github.com/repos/$GITHUB_REPOSITORY"
state="$RUNNER_TEMP/qualified-build.json"
for i in $(seq 1 150); do
  curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build" > "$state"
  status=$(jq -r .status "$state")
  if [[ "$status" = completed ]]; then break; fi
  sleep 30
done
jq -e '.status=="completed" and .conclusion=="success"' "$state" >/dev/null
curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build/jobs?per_page=100" > "$RUNNER_TEMP/qualified-jobs.json"
jq -e '[.jobs[] | select(.name=="build" or .name=="Actual ARM64 software-frame and seek-preview contract") | .conclusion] | length==2 and all(.=="success")' "$RUNNER_TEMP/qualified-jobs.json" >/dev/null
artifact=$(curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/runs/$build/artifacts" | jq -r '.artifacts[] | select(.name=="candidate-test-input" and .expired==false) | .id')
[[ "$artifact" =~ ^[0-9]+$ ]]
curl -fsSL --retry 2 -H "Authorization: Bearer $API_TOKEN" "$api/actions/artifacts/$artifact/zip" -o "$RUNNER_TEMP/qualified-input.zip"
mkdir -p preview
unzip -q "$RUNNER_TEMP/qualified-input.zip" -d preview
(cd preview && sha256sum -c apk.sha256)
grep -Fx "app=$app_sha" preview/provenance.txt
