#!/usr/bin/env bash
# Generates the Ed25519 key pair that signs update manifests, and prints only the public key.
#
#   bash scripts/generate-update-manifest-key.sh /path/outside/git/update-keys
#
# The public key (base64 SubjectPublicKeyInfo DER) belongs in gradle.properties as
# yfuse.updateManifestPublicKey, and in CI as a variable available to release builds. The private
# key signs update-v2.json in the release workflow and must never be committed: keep it in a CI
# secret (or a password manager), with a copy somewhere you can still reach if CI is rebuilt.
#
# Why this matters: without a key, release builds accept any manifest whose APK matches the
# package name, version code and SHA-256 — good for integrity, no protection against a manifest
# written by someone else. With a key, only manifests signed by this pair are accepted.
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <output-directory-outside-git>" >&2
  exit 1
fi
for dependency in openssl git; do
  command -v "$dependency" >/dev/null 2>&1 || {
    echo "$dependency is required" >&2
    exit 1
  }
done

umask 077
mkdir -p -- "$1"
output_dir="$(cd -- "$1" && pwd -P)"
if [[ "$(git -C "$output_dir" rev-parse --is-inside-work-tree 2>/dev/null || true)" == true ]]; then
  echo "Refusing to generate a private key inside a Git worktree." >&2
  exit 1
fi
private_key="$output_dir/update-manifest-signing-key.pem"
public_key="$output_dir/update-manifest-public-key.b64"
for key_file in "$private_key" "$public_key"; do
  if [[ -e "$key_file" || -L "$key_file" ]]; then
    echo "Refusing to overwrite existing key file: $key_file" >&2
    exit 1
  fi
done

temporary_dir="$(mktemp -d "$output_dir/.update-manifest-key.XXXXXX")"
cleanup() {
  rm -f -- "$temporary_dir/private.pem" "$temporary_dir/public.b64"
  rmdir -- "$temporary_dir"
}
trap cleanup EXIT
openssl genpkey -algorithm ED25519 -out "$temporary_dir/private.pem"
chmod 600 "$temporary_dir/private.pem"
openssl pkey -in "$temporary_dir/private.pem" -pubout -outform DER | base64 | tr -d '\n' >"$temporary_dir/public.b64"
# Hard links install complete files atomically and fail if another invocation already created them.
ln -- "$temporary_dir/private.pem" "$private_key"
if ! ln -- "$temporary_dir/public.b64" "$public_key"; then
  if [[ "$private_key" -ef "$temporary_dir/private.pem" ]]; then
    rm -- "$private_key"
  fi
  exit 1
fi

cat <<EOF
Generated:
  private key  $private_key   (chmod 600, never commit)
  public key   $public_key

Next steps
1. Put the public key into gradle.properties for release builds:
     yfuse.updateManifestPublicKey=$(cat "$public_key")
2. Add the private key material to the release workflow's secret store, e.g.
     UPDATE_MANIFEST_SIGNING_KEY (the PEM contents), and have the release job sign
     update-v2.json with it before publishing.
3. Back the private key up off-machine. Losing it means installed clients reject every
   future signed manifest, and you would have to ship a build without a key to recover.
EOF
