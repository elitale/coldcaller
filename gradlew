#!/bin/sh
set -e
APP_HOME="$(cd "$(dirname "$0")" && pwd)"

# Prefer explicitly-set JAVA_HOME, then try Homebrew Java 21, then fallback
if [ -z "$JAVA_HOME" ]; then
    for candidate in \
        /opt/homebrew/opt/openjdk@21 \
        /usr/local/opt/openjdk@21 \
        /Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home; do
        [ -x "$candidate/bin/java" ] && JAVA_HOME="$candidate" && break
    done
fi

JAVA_EXE="${JAVA_HOME:+$JAVA_HOME/bin/}java"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVA_EXE" -Xmx64m -Xms64m \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
