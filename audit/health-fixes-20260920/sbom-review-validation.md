# Supply-chain review follow-up

- Fixed the CI upload condition so an existing SBOM is uploaded even when the scanner blocks on a vulnerability. An input or OSV query failure that produced no file skips upload.
- SPDX snapshots now contain a UTC `creationInfo.created` and a unique UUID document namespace.
- Added a behavior test generating two real SBOM files, checking their UTC timestamps, distinct UUID namespaces, and identical package payloads.
- Validation: bundled Python ran `-m unittest discover --start-directory scripts --pattern 'test_*.py' --verbose`: 47 tests passed in 3.849 seconds (exit 0).
- Scoped `git diff --check` passed for the three implementation/test/workflow files (only the existing CRLF normalization notice).
- Refreshed only metadata in `sbom.spdx.json`, retaining the prior scan's 772 packages and all vulnerability comments unchanged; did not repeat the OSV network scan. Older SBOM files under `build/sbom` were left as historical artifacts.
- GitHub upload behavior was reviewed from the workflow condition; this local check did not execute a GitHub Actions run or CodeQL extraction.
