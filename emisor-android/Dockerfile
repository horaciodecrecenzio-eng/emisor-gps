# Compila el APK sin instalar Android Studio.
# Uso (en la carpeta emisor-android):
#   docker build -t cor-emisor .
#   docker run --rm -v "${PWD}:/export" cor-emisor      (PowerShell / bash)
# Queda el archivo app-debug.apk en esta misma carpeta.

FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV GRADLE_VERSION=8.7
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip wget ca-certificates && rm -rf /var/lib/apt/lists/*

# --- Android command-line tools + SDK ---
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

# --- Gradle ---
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
ENV PATH=${PATH}:/opt/gradle-${GRADLE_VERSION}/bin

WORKDIR /project
COPY . /project

# Compila el APK de depuración (instalable por sideload) durante el build.
RUN gradle --no-daemon assembleDebug && \
    mkdir -p /out && cp app/build/outputs/apk/debug/app-debug.apk /out/app-debug.apk

# Al correr el contenedor, copia el APK a la carpeta montada en /export.
CMD ["cp", "/out/app-debug.apk", "/export/app-debug.apk"]
