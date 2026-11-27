# production-backend — runtime image
#
# This is a runtime-only image: the jar is built by Maven first
# (locally: `mvn package`; in CI: the workflow's Maven step), then
# copied in. The image contains no build tools and no source.
#
# There is deliberately no RUN instruction in this file: the build
# environment this image is verified in cannot execute containers,
# so a RUN step would make `docker build` fail there. If the image
# is ever built where container execution works, a multi-stage
# build (Maven stage + this runtime stage) is a one-line change;
# see docs/ARCHITECTURE.md.
#
# Build:  mvn package -DskipTests && docker build -t production-backend .
# Run:    docker compose up --build

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="production-backend"
LABEL org.opencontainers.image.description="Order and payment processing API — idempotency, duplicate detection, rate limiting"

WORKDIR /app

COPY target/production-backend-1.0.0.jar app.jar

# Non-root: the UID need not exist in /etc/passwd for the kernel to
# run the process as it. The jar is world-readable, the working
# directory needs no writes (H2 is in-memory by default; the
# postgres profile writes nothing to disk).
USER 10001:10001

EXPOSE 8080

# Actuator health. wget is in the Alpine base image; the endpoint
# is UP only when the database is reachable.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
