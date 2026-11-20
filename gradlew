#!/usr/bin/env sh
set -e

GRADLE_VERSION="8.5"
GRADLE_DIST_DIR="${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
GRADLE_BIN="${GRADLE_DIST_DIR}/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  echo "Bootstrapping Gradle ${GRADLE_VERSION} distribution..."
  mkdir -p "$GRADLE_DIST_DIR"
  TMP_ZIP="${GRADLE_DIST_DIR}/gradle-${GRADLE_VERSION}-bin.zip"
  curl --retry 5 --retry-all-errors -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$TMP_ZIP"
  unzip -q -o "$TMP_ZIP" -d "$GRADLE_DIST_DIR"
  rm -f "$TMP_ZIP"
  chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
