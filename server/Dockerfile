# Built from the repository root, not from this directory:
#   docker build -f server/Dockerfile -t life-insights-server .
# The build needs the Gradle wrapper and the shared :sync-model module, both of which live above.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# The Android modules are skipped: there is no SDK in this image, and Gradle would fail configuring
# them long before it reached any server code. See settings.gradle.kts.
ENV LIFE_INSIGHTS_SERVER_ONLY=1

# Wrapper and build files first, so the dependency download is cached and only re-run when the
# build configuration actually changes rather than on every source edit.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY sync-model/build.gradle.kts sync-model/
COPY insights/build.gradle.kts insights/
COPY server/build.gradle.kts server/
RUN ./gradlew --no-daemon :server:dependencies --quiet || true

COPY sync-model/src sync-model/src
COPY insights/src insights/src
COPY server/src server/src
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:17-jre
WORKDIR /app

# Nothing here needs root, and a sync server holding someone's health history is exactly the sort
# of thing that should not be running as it.
RUN useradd --system --create-home --shell /usr/sbin/nologin insights
COPY --from=build --chown=insights:insights /src/server/build/install/server /app
USER insights

ENV PORT=8080
EXPOSE 8080

# DATABASE_URL and SYNC_TOKEN have no defaults on purpose. The server refuses to start without
# them rather than coming up in a state where anyone who finds the host can read the data.
CMD ["/app/bin/server"]
