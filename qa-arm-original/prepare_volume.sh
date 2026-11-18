set -euo pipefail
test "$GITHUB_ACTIONS" = true
bash qa-integrated/acquire-qualified-candidate.sh "$1" "${QA_APP_SHA:?missing expected app source}"
mkdir -p candidate
cp preview/composeApp-release.apk preview/provenance.txt driver/driver.apk candidate/
awk '$2 == "composeApp-release.apk" { print }' preview/apk.sha256 > candidate/apk.sha256
test "$(wc -l < candidate/apk.sha256)" -eq 1
(cd driver && sha256sum driver.apk) >> candidate/apk.sha256
(cd candidate && sha256sum -c apk.sha256)
