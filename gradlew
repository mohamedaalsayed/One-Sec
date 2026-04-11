#!/bin/sh
#
# Gradle wrapper script — run ./gradlew assembleDebug or assembleRelease
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Detect OS
case "`uname`" in
  Darwin* ) darwin=true;;
  CYGWIN* ) cygwin=true;;
esac

# Escape application args
save () { for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/"; done; echo " " ; }
APP_ARGS=`save "$@"`

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  $APP_ARGS
