#!/usr/bin/env bash
# Runs a pinned google-java-format over the given Java files, in place. Used by
# the local pre-commit hook. The jar is cached under XDG_CACHE_HOME on first use.
#
# gjf 1.35.0 is the first line we verified to run on JDK 25; older releases hit a
# NoSuchMethodError against the JDK 25 javac internals.
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

# JDK 16+ requires these exports to reach the javac internals gjf uses.
exec java \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -jar "${JAR}" --replace "$@"
