#!/usr/bin/env bash
# Type-checks everything that can be checked without Google's Maven repository:
#   1. :core + :engine + tests (and runs them)
#   2. every app file that uses only the Android framework, against the public SDK 36 stub jar
#   3. NearbyTransport, against Java stubs of the documented Nearby Connections signatures
# Compose UI files are not checked (the Compose compiler plugin and runtime come from Maven).
# Usage: ANDROID_JAR=/path/to/android-36.jar tools/compile-check/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-/tmp/segue-compile-check}"
: "${ANDROID_JAR:?set ANDROID_JAR to an SDK android.jar (platforms/android-36/android.jar)}"
rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/app"

echo "== 1. core + engine + tests"
kotlinc "$ROOT/core/src/main/kotlin" "$ROOT/engine/src/main/kotlin" "$ROOT/engine/src/test/kotlin" \
  -include-runtime -d "$OUT/engine-test.jar" 2>&1 | grep -v '^warning' || true
java -cp "$OUT/engine-test.jar" com.segue.engine.EngineSelfTestKt "$OUT/engine-test" | tail -1

echo "== 2+3. app sources against the SDK jar and Nearby stubs"
javac -d "$OUT/stubs" -cp "$ANDROID_JAR" $(find "$ROOT/tools/compile-check/stubs" -name '*.java')
APP="$ROOT/app/src/main/kotlin/com/segue/app"
kotlinc -no-reflect -jvm-target 17 \
  -cp "$ANDROID_JAR:$OUT/stubs:$OUT/engine-test.jar" -d "$OUT/app" \
  "$APP/player" "$APP/route" "$APP/store" "$APP/bench" "$APP/service" "$APP/transport" "$APP/ui/UiModel.kt" \
  "$ROOT/tools/compile-check/stubs/kotlin-stubs"
echo "app classes: $(find "$OUT/app" -name '*.class' | wc -l)"
echo "OK"
