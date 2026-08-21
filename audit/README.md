# Historical audit evidence

Binary screenshots, videos, and the unused extracted `mpvaar/` tree were removed from Git on
2026-08-21. The source reports and UI hierarchy XML remain in this directory so findings are still
searchable and reviewable without adding more than 130 MiB to every checkout.

The recovery bundle is named `repository-binaries-before-cleanup-20260821.zip` and has SHA-256:

```text
2C1E5ADE5D817B1FFBD1D487685328A0467CF16544EE56FF2F39FCF1862EC56E
```

Keep future screenshots and recordings as CI/release artifacts. Commit only the report, compact
machine-readable evidence, and a checksum or artifact identifier needed to retrieve the binary
bundle.
