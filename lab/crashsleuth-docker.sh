#!/bin/sh
# Runs the CrashSleuth command line in a Java container, for hosts without Java.
# CRASHSLEUTH_HOME points to an installDist output (cli/build/install/crashsleuth).
exec docker run --rm \
  -v "${CRASHSLEUTH_HOME:-/tmp/crashsleuth-cli}:/opt/crashsleuth:ro" \
  -v "${CRASHSLEUTH_LAB_RUNS:-/tmp/crashsleuth-lab-runs}:${CRASHSLEUTH_LAB_RUNS:-/tmp/crashsleuth-lab-runs}:ro" \
  eclipse-temurin:21-jre /opt/crashsleuth/bin/crashsleuth "$@"
