#!/bin/sh
# Writes Signing/release.keystore.jks and Signing/keystore.properties (both
# gitignored) from CI secrets, for app/build.gradle.kts's release signing.
# Needs KEYSTORE_B64 (`base64 -w0 Signing/release.keystore.jks`),
# STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD in the environment.
set -eu

mkdir -p Signing
# tr strips any whitespace/newlines the KEYSTORE_B64 secret picked up when it
# was copy-pasted into the secret box - base64's alphabet never contains
# whitespace so this is always safe, and it's cheap insurance against a
# corrupted keystore silently failing 19 minutes into packageRelease.
printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]' | base64 -d >Signing/release.keystore.jks
cat >Signing/keystore.properties <<EOF
storeFile=Signing/release.keystore.jks
storePassword=${STORE_PASSWORD}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASSWORD}
EOF

# Fail fast with a clear message instead of discovering a corrupt/mismatched
# keystore deep inside packageRelease after a full build.
keytool -list -keystore Signing/release.keystore.jks -storepass "$STORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null
