#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <repository-directory> <deb-package>" >&2
  exit 64
fi

repository_directory=$1
deb_package=$2
: "${APT_SIGNING_KEY_FINGERPRINT:?APT_SIGNING_KEY_FINGERPRINT is required}"
: "${APT_SIGNING_PASSPHRASE:?APT_SIGNING_PASSPHRASE is required}"

[[ -f "$deb_package" ]] || { echo "Debian package not found: $deb_package" >&2; exit 66; }
[[ $(dpkg-deb --field "$deb_package" Package) == "mcp-cli-server" ]] || { echo "Unexpected package name" >&2; exit 65; }

pool_directory="$repository_directory/pool/main/m/mcp-cli-server"
distribution_directory="$repository_directory/dists/stable"
binary_directory="$distribution_directory/main/binary-all"
mkdir -p "$pool_directory" "$binary_directory"
cp "$deb_package" "$pool_directory/"

(
  cd "$repository_directory"
  dpkg-scanpackages pool /dev/null > dists/stable/main/binary-all/Packages
  gzip --no-name --best --force dists/stable/main/binary-all/Packages

  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin="Caimito" \
    -o APT::FTPArchive::Release::Label="Caimito MCP CLI Server" \
    -o APT::FTPArchive::Release::Suite="stable" \
    -o APT::FTPArchive::Release::Codename="stable" \
    -o APT::FTPArchive::Release::Architectures="all" \
    -o APT::FTPArchive::Release::Components="main" \
    -o APT::FTPArchive::Release::Description="Caimito MCP CLI Server APT repository" \
    release dists/stable > dists/stable/Release

  gpg --batch --yes --pinentry-mode loopback --passphrase "$APT_SIGNING_PASSPHRASE" \
    --local-user "$APT_SIGNING_KEY_FINGERPRINT" --clearsign --output dists/stable/InRelease dists/stable/Release
  gpg --batch --yes --pinentry-mode loopback --passphrase "$APT_SIGNING_PASSPHRASE" \
    --local-user "$APT_SIGNING_KEY_FINGERPRINT" --armor --detach-sign --output dists/stable/Release.gpg dists/stable/Release
  gpg --batch --yes --export "$APT_SIGNING_KEY_FINGERPRINT" | gpg --dearmor --yes --output caimito-mcp-cli-server-archive-keyring.gpg
)
