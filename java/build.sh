#!/usr/bin/env bash
# Builds miniboard-lib and stages a drop-in distribution folder.
# Runs the committed Gradle wrapper (Gradle itself need not be installed).
# Output lands in build/dist/.
set -euo pipefail

clean=0
offline=0
for arg in "$@"; do
    case "$arg" in
        --clean) clean=1 ;;
        --offline) offline=1 ;;
        *)
            echo "Unknown option: $arg" >&2
            echo "Usage: $0 [--clean] [--offline]" >&2
            exit 1
            ;;
    esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

echo '=== MiniBoard54 Library Build ==='

# --- 1. Java present? ---
java_exe=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_exe="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    java_exe="$(command -v java)"
fi
if [ -z "$java_exe" ]; then
    echo 'No Java found. Set JAVA_HOME or put java on PATH.' >&2
    exit 1
fi
echo "Java: $java_exe"

# --- 2. purejavacomm availability notice ---
# 0.0.29 is not published to Maven Central under any groupId; it must be supplied.
pjc="$root/libs/purejavacomm-0.0.29.jar"
if [ -f "$pjc" ]; then
    echo 'purejavacomm: using libs/purejavacomm-0.0.29.jar'
else
    echo 'purejavacomm: MISSING from libs/purejavacomm-0.0.29.jar'
    echo '  Not available on Maven Central - supply the jar by hand.'
    echo '  Protocol code will compile; transport and discovery will not.'
fi

# --- 3. Build ---
gradle_args=()
[ "$clean" -eq 1 ] && gradle_args+=(clean)
gradle_args+=(build)
[ "$offline" -eq 1 ] && gradle_args+=(--offline)

chmod +x "$root/gradlew"
echo "Running: ./gradlew ${gradle_args[*]}"
if ! "$root/gradlew" "${gradle_args[@]}"; then
    echo ''
    echo 'BUILD FAILED.' >&2
    echo 'If the failure is unresolved purejavacomm:0.0.29, place the jar at:' >&2
    echo "  $pjc" >&2
    exit 1
fi

echo ''
echo 'BUILD OK'
echo "Distribution: $root/build/dist"
find "$root/build/dist" -name '*.jar' | while read -r jar; do
    echo "  ${jar#"$root"/}"
done
