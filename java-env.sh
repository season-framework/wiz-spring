#!/usr/bin/env sh

: "${JAVA_HOME:=/opt/java/openjdk}"
export JAVA_HOME

case ":${PATH:-}:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) PATH="$JAVA_HOME/bin${PATH:+:$PATH}" ;;
esac
export PATH
