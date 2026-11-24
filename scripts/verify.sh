#!/usr/bin/env bash
# Сборка и проверка Hybrid Reading: unit-тесты, lint, debug и release APK.
#
# Требуется JDK 17-21: на JDK 25 AGP 8.9.2 падает с ошибкой вида «25.0.4.1».
# JAVA_HOME можно задать заранее, иначе скрипт попробует найти JDK 21 сам.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_BIN="${GRADLE:-./gradlew}"

if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "/c/Program Files/Eclipse Adoptium"/jdk-21* /usr/lib/jvm/*21*; do
        if [ -x "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi

echo "== JAVA_HOME=${JAVA_HOME:-<системный>}"
cd "$PROJECT_DIR"
"$GRADLE_BIN" testDebugUnitTest lintDebug assembleDebug assembleRelease

echo
echo "== APK:"
find app/build/outputs/apk -type f -name '*.apk' | sort | while IFS= read -r apk; do
    printf '  %-7s %s\n' "$(du -h "$apk" | cut -f1)" "$apk"
done

echo "== Тесты:"
grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml |
    sed -E 's/<testsuite name="([^"]+)" tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1: \2 тестов, провалов \4, ошибок \5/'
echo "VERIFY OK"
