# Cabessa Social build environment: JDK 21, Android SDK and release-check Python.
#
# Usage:
#   1. Put the stock Instagram APK/BUNDLE next to build.sh (e.g. instagram.apkm)
#   2. Drop the Morphe CLI jar into tools/ (build.sh requires it)
#      Also supply the public patches-library JAR and Gradle 9.5.0/lib compiler
#      files at the default tools/ paths in docs/BUILD_FROM_SOURCE.md.
#   3. docker build --platform linux/amd64 -t cabessa-social-build .
#   4. docker run --rm --platform linux/amd64 \
#        -v "$PWD:/work" -v social-gradle:/root/.gradle \
#        -v "$CABESSA_KEYSTORE:/signing/release.p12:ro" \
#        -e CABESSA_KEYSTORE=/signing/release.p12 -e CABESSA_KEY_ALIAS \
#        -e CABESSA_KEYSTORE_PASS -e CABESSA_KEY_PASS \
#        cabessa-social-build ./build.sh instagram.apkm
#
# The verified result lands at ./dist/cabessa-social.apk. The signing key remains
# outside /work; configure the existing key privately before running this command.
# Android's Linux SDK tools are x86-64 binaries. On Apple Silicon, enable
# amd64 emulation in the container runtime and keep the platform flags above.
#
# Resource use: ~2-4 GB disk (JDK, SDK, Gradle cache), 4 GB RAM recommended
# for the dex-patching step.

FROM eclipse-temurin:21-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git python3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -sSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/cmdtools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

WORKDIR /work
