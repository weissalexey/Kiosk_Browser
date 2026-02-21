#!/usr/bin/env sh

# Minimal gradle wrapper script
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_CMD="java"

# Prefer JAVA_HOME if set
if [ -n "$JAVA_HOME" ] ; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi

exec "$JAVA_CMD" -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
