#!/usr/bin/env bash
# Formats the given Java files in place with a pinned google-java-format, cached
# under XDG_CACHE_HOME.
#
# 1.35.0 is the first release verified on JDK 25; older ones hit a
# NoSuchMethodError in the javac internals.
set -euo pipefail

GJF_VERSION=1.35.0
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/maestro"
JAR="${CACHE_DIR}/google-java-format-${GJF_VERSION}-all-deps.jar"
URL="https://github.com/google/google-java-format/releases/download/v${GJF_VERSION}/google-java-format-${GJF_VERSION}-all-deps.jar"

if [[ ! -f "${JAR}" ]]; then
  mkdir -p "${CACHE_DIR}"
  echo "fetching google-java-format ${GJF_VERSION}..." >&2
  curl -fsSL -o "${JAR}" "${URL}"
fi

# JDK 16+ needs these exports for the javac internals.
exec java \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -jar "${JAR}" --replace "$@"
