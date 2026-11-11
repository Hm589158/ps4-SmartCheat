#!/usr/bin/env bash
# Prepares the release keystore from repository secrets for Gradle. Source it (". prepare-keystore.sh"); it exports
#   SMARTCHEAT_KEYSTORE_FILE, SMARTCHEAT_KEYSTORE_PASSWORD, SMARTCHEAT_KEY_ALIAS, SMARTCHEAT_KEY_PASSWORD
# and sets KEYSTORE_TMP (a directory to delete afterwards).
#
# Input (environment):
#   SMARTCHEAT_KEYSTORE_BASE64   required: the keystore file (PKCS#12 or JKS), base64
#   SMARTCHEAT_KEYSTORE_PASSWORD optional: empty for a PKCS#12 file without a password
#   SMARTCHEAT_KEY_ALIAS         optional: default = the only key in the file (else "android-release")
#   SMARTCHEAT_KEY_PASSWORD      optional: default = the keystore password
#
# A PKCS#12 file without a password cannot be used by the Android Gradle plugin directly (it requires a store
# password, and Java does not list the keys of an unencrypted PKCS#12 file without a MAC). Such a file is converted
# here, key and certificate unchanged, into a temporary PKCS#12 protected by a random password that only exists
# during this job. Secret values are never printed; the random password is masked in the log.
set -euo pipefail
umask 077

KEYSTORE_TMP="$(mktemp -d "${RUNNER_TEMP:-/tmp}/keystore.XXXXXX")"
src="$KEYSTORE_TMP/source.keystore"
if ! printf '%s' "${SMARTCHEAT_KEYSTORE_BASE64:?SMARTCHEAT_KEYSTORE_BASE64 is not set}" | tr -d ' \r\n' | base64 -d > "$src" 2>/dev/null || [ ! -s "$src" ]; then
  echo "::error::SMARTCHEAT_KEYSTORE_BASE64 is not valid base64 (encode the keystore file with: base64 -w0 release.p12)"; return 1 2>/dev/null || exit 1
fi

list_aliases() { # keystore, password env var name
  keytool -list -keystore "$1" -storepass:env "$2" 2>/dev/null | grep -i 'PrivateKeyEntry' | cut -d, -f1 | sed 's/[[:space:]]*$//'
}

if [ -n "${SMARTCHEAT_KEYSTORE_PASSWORD:-}" ]; then
  # Password given: use the file as it is.
  if ! keytool -list -keystore "$src" -storepass:env SMARTCHEAT_KEYSTORE_PASSWORD >/dev/null 2>&1; then
    echo "::error::The keystore could not be opened with SMARTCHEAT_KEYSTORE_PASSWORD (wrong password, or not a keystore)"; return 1 2>/dev/null || exit 1
  fi
  aliases="$(list_aliases "$src" SMARTCHEAT_KEYSTORE_PASSWORD)"
  export SMARTCHEAT_KEYSTORE_FILE="$src"
  export SMARTCHEAT_KEY_PASSWORD="${SMARTCHEAT_KEY_PASSWORD:-$SMARTCHEAT_KEYSTORE_PASSWORD}"
else
  # No password: a PKCS#12 file without one. Read it with OpenSSL (also older RC2/3DES files with -legacy).
  pem="$KEYSTORE_TMP/key.pem"
  if ! openssl pkcs12 -in "$src" -passin pass: -nodes -out "$pem" 2>/dev/null \
     && ! openssl pkcs12 -legacy -in "$src" -passin pass: -nodes -out "$pem" 2>/dev/null; then
    echo "::error::SMARTCHEAT_KEYSTORE_PASSWORD is empty, but the keystore is not a PKCS#12 file without a password. Add the password as SMARTCHEAT_KEYSTORE_PASSWORD."
    return 1 2>/dev/null || exit 1
  fi
  keys=$(grep -c 'BEGIN .*PRIVATE KEY' "$pem" || true)
  if [ "$keys" != "1" ]; then
    rm -f "$pem"; echo "::error::The PKCS#12 file holds $keys private keys; exactly one is supported without a password."; return 1 2>/dev/null || exit 1
  fi
  # The key's name in the file (friendlyName), unless SMARTCHEAT_KEY_ALIAS says otherwise.
  name=$(sed -n 's/^[[:space:]]*friendlyName:[[:space:]]*//p' "$pem" | head -n1)
  alias_name="${SMARTCHEAT_KEY_ALIAS:-${name:-android-release}}"
  pw="$(openssl rand -hex 24)"
  echo "::add-mask::$pw"
  export SMARTCHEAT_KEYSTORE_PASSWORD="$pw" SMARTCHEAT_KEY_PASSWORD="$pw"
  export SMARTCHEAT_KEYSTORE_FILE="$KEYSTORE_TMP/release.p12"
  openssl pkcs12 -export -in "$pem" -name "$alias_name" -passout env:SMARTCHEAT_KEYSTORE_PASSWORD -out "$SMARTCHEAT_KEYSTORE_FILE"
  rm -f "$pem"
  aliases="$(list_aliases "$SMARTCHEAT_KEYSTORE_FILE" SMARTCHEAT_KEYSTORE_PASSWORD)"
  echo "PKCS#12 without a password: converted to a temporary keystore for this build (same key and certificate)."
fi

count=$(printf '%s\n' "$aliases" | grep -c . || true)
echo "The keystore holds $count private key(s)."
if [ -z "${SMARTCHEAT_KEY_ALIAS:-}" ]; then
  if [ "$count" = "1" ]; then
    export SMARTCHEAT_KEY_ALIAS="$aliases"
  else
    echo "::error::SMARTCHEAT_KEY_ALIAS is missing and the keystore holds $count keys — set it to the alias to sign with"; return 1 2>/dev/null || exit 1
  fi
elif ! printf '%s\n' "$aliases" | grep -qixF "$SMARTCHEAT_KEY_ALIAS"; then
  echo "::error::SMARTCHEAT_KEY_ALIAS does not match any key in the keystore ($count key(s) found)"; return 1 2>/dev/null || exit 1
fi
echo "Signing with key alias: $SMARTCHEAT_KEY_ALIAS"
