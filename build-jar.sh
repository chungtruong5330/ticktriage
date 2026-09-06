#!/usr/bin/env bash
# Build an installable plugin jar without Gradle.
#
# Gradle is the normal workflow (see build.gradle.kts) but it is a large
# install, and this project's dependency list is short enough to resolve by
# hand. This script downloads what paper-api needs, compiles everything, and
# packages the jar. Requires JDK 25 and curl.
set -euo pipefail

cd "$(dirname "$0")"

VERSION="0.1.0"
PAPER_API="26.2.build.121-stable"
ADVENTURE="5.2.0"
GUAVA="33.6.0-jre"
BUNGEE_CHAT="1.21-R0.2-deprecated+build.21"
ANNOTATIONS="26.0.2"
WORLDGUARD="7.0.18"
WORLDEDIT="7.4.5"
GRIEFPREVENTION="16.18.4"

JAVA_HOME_GUESS=$(ls -d "/c/Program Files/Microsoft/jdk-25"* 2>/dev/null | head -1 || true)
JAVAC="${JAVA_HOME_GUESS:+$JAVA_HOME_GUESS/bin/}javac"
JAR="${JAVA_HOME_GUESS:+$JAVA_HOME_GUESS/bin/}jar"

echo "Using: $("$JAVAC" -version 2>&1)"

mkdir -p libs
fetch() {
  local url="$1" dest="$2"
  if [ ! -f "libs/$dest" ]; then
    echo "  fetching $dest"
    curl -sfL --max-time 180 -o "libs/$dest" "$url"
  fi
}

PAPER_REPO="https://repo.papermc.io/repository/maven-public"
CENTRAL="https://repo1.maven.org/maven2"
ENGINEHUB="https://maven.enginehub.org/repo"
JITPACK="https://jitpack.io"

echo "Resolving dependencies..."
fetch "$PAPER_REPO/io/papermc/paper/paper-api/$PAPER_API/paper-api-$PAPER_API.jar" paper-api.jar
fetch "$CENTRAL/net/kyori/adventure-api/$ADVENTURE/adventure-api-$ADVENTURE.jar" adventure-api.jar
fetch "$CENTRAL/net/kyori/adventure-key/$ADVENTURE/adventure-key-$ADVENTURE.jar" adventure-key.jar
fetch "$CENTRAL/com/google/guava/guava/$GUAVA/guava-$GUAVA.jar" guava.jar
fetch "$CENTRAL/org/jetbrains/annotations/$ANNOTATIONS/annotations-$ANNOTATIONS.jar" annotations.jar
fetch "$PAPER_REPO/net/md-5/bungeecord-chat/$BUNGEE_CHAT/bungeecord-chat-$BUNGEE_CHAT.jar" bungeecord-chat.jar

# Optional claim-plugin APIs. compileOnly - the adapters are only loaded when
# the corresponding plugin is actually installed on the server.
fetch "$ENGINEHUB/com/sk89q/worldguard/worldguard-bukkit/$WORLDGUARD/worldguard-bukkit-$WORLDGUARD.jar" worldguard-bukkit.jar
fetch "$ENGINEHUB/com/sk89q/worldguard/worldguard-core/$WORLDGUARD/worldguard-core-$WORLDGUARD.jar" worldguard-core.jar
fetch "$ENGINEHUB/com/sk89q/worldedit/worldedit-bukkit/$WORLDEDIT/worldedit-bukkit-$WORLDEDIT.jar" worldedit-bukkit.jar
fetch "$ENGINEHUB/com/sk89q/worldedit/worldedit-core/$WORLDEDIT/worldedit-core-$WORLDEDIT.jar" worldedit-core.jar
fetch "$JITPACK/com/github/TechFortress/GriefPrevention/$GRIEFPREVENTION/GriefPrevention-$GRIEFPREVENTION.jar" griefprevention.jar

CP=$(ls libs/*.jar | tr '\n' ';')

echo "Compiling..."
rm -rf build/plugin build/jar
mkdir -p build/plugin build/jar
"$JAVAC" --release 25 -nowarn -encoding UTF-8 -cp "$CP" -d build/plugin $(find src/main/java -name '*.java')

echo "Packaging..."
cp -r build/plugin/* build/jar/
sed "s/\${version}/$VERSION/" src/main/resources/plugin.yml > build/jar/plugin.yml
cp src/main/resources/config.yml build/jar/
"$JAR" --create --file "build/TickTriage-$VERSION.jar" -C build/jar .

echo
echo "Built build/TickTriage-$VERSION.jar"
ls -lh "build/TickTriage-$VERSION.jar"
