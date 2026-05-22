FROM eclipse-temurin:17-jdk

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

ARG CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=34
ARG ANDROID_BUILD_TOOLS=34.0.0

RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    bash \
    lib32stdc++6 \
    lib32z1 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools

WORKDIR /opt

RUN wget -O commandlinetools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip && \
    unzip commandlinetools.zip -d cmdline-tools-temp && \
    mkdir -p $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    mv cmdline-tools-temp/cmdline-tools/* $ANDROID_SDK_ROOT/cmdline-tools/latest/ && \
    rm -rf commandlinetools.zip cmdline-tools-temp

RUN yes | sdkmanager --licenses

RUN sdkmanager \
    "platform-tools" \
    "platforms;android-${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS}"

WORKDIR /app

COPY . .

RUN chmod +x ./gradlew

CMD ["./gradlew", "assembleDebug"]