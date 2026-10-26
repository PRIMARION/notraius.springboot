# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_ACTOR=mohamedbello18
ARG GITHUB_TOKEN=
ENV GITHUB_ACTOR=${GITHUB_ACTOR}
ENV GITHUB_TOKEN=${GITHUB_TOKEN}

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar

# FlowMap parity with local runs. The static analyzer (shared Workspace)
# only traces classes whose code source is a DIRECTORY, and hover popovers
# need real .java files (SourceSnippetReader). A bare fat jar offers neither,
# so the map degrades to a boundary-only skeleton ("pale" result). Unpack
# host and shared classes into directory form; host sources are copied into
# the runtime stage below.
RUN mkdir -p /app/flowmap/classes /app/flowmap/shared-classes \
    && cd /app/flowmap/classes \
    && jar -xf /app/build/libs/notraius.springboot-0.0.1-SNAPSHOT.jar \
    && mv BOOT-INF/classes/* . \
    && cd /app/flowmap/shared-classes \
    && jar -xf /app/flowmap/classes/BOOT-INF/lib/$(ls /app/flowmap/classes/BOOT-INF/lib | grep '^shared.springboot-.*\.jar$' | head -1) \
    && rm -rf /app/flowmap/classes/BOOT-INF /app/flowmap/shared-classes/META-INF

# ---- runtime stage ----
FROM eclipse-temurin:25-jre AS runtime

# Attachment storage root — kept in sync with the .env default. A different
# value can be supplied at runtime (docker run -e / compose env_file); the
# entrypoint then creates/chowns that path on boot.
ARG STORAGE_ROOT_DIR=/var/primarion/attachments
ENV STORAGE_ROOT_DIR=${STORAGE_ROOT_DIR}

# gosu: the entrypoint starts as root only to prepare the attachments dir,
# then drops to the non-root `springboot` user before exec'ing the JVM.
RUN apt-get update \
    && apt-get install -y --no-install-recommends gosu \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system springboot \
    && adduser --system --ingroup springboot springboot \
    && mkdir -p "${STORAGE_ROOT_DIR}" \
    && chown -R springboot:springboot "${STORAGE_ROOT_DIR}"

WORKDIR /app
COPY --from=build /app/build/libs/notraius.springboot-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /app/src/main/java /app/flowmap/sources
COPY --from=build /app/flowmap/classes /app/flowmap/classes
COPY --from=build /app/flowmap/shared-classes /app/flowmap/shared-classes
# Analyzer roots: host classes, shared classes (trace edges), host sources
# (hover popovers). Operators can extend via the same variable.
ENV FLOWMAP_WORKSPACE_ROOTS=/app/flowmap/classes,/app/flowmap/shared-classes,/app/flowmap/sources
COPY entrypoint.sh entrypoint.sh
RUN chmod +x entrypoint.sh

EXPOSE 8086
ENTRYPOINT ["./entrypoint.sh", "java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
