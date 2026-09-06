#!/usr/bin/env bash
# Compile and run every core suite, then the benchmark. No server, no network,
# no Paper API needed - the core package deliberately has no Bukkit imports, so
# any JDK 11 or newer can build and run it.
#
# Use this for fast iteration on the rules. Use build-jar.sh to produce an
# actual installable plugin.
set -euo pipefail

cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes

SOURCES=$(find src/main/java/dev/ticktriage/core src/test/java -name '*.java')
javac -nowarn -encoding UTF-8 -d build/classes $SOURCES

FAILED=0
for suite in CoreTests CensusTests RemedyTests ProtectionTests HistoryTests; do
  java -cp build/classes "dev.ticktriage.core.$suite" || FAILED=1
done

echo
java -cp build/classes dev.ticktriage.core.Bench

echo
java -cp build/classes dev.ticktriage.core.Demo

exit $FAILED
