# orkx in a container, for the machine that should not have a JDK on it.
#
# Self-contained: `docker build .` from a clean checkout produces the image, because the
# native binary is built in the first stage rather than copied in from somewhere. That costs
# a few minutes a build and buys a Dockerfile that means the same thing on a laptop as it
# does in CI, which is the only way the CI result is evidence about anything.

FROM ghcr.io/graalvm/native-image-community:25 AS build

WORKDIR /src

# The wrapper and the pom first, so a change to a source file does not re-download Maven and
# every dependency. distributionType=only-script means mvnw fetches Maven itself over the
# network, which is why this layer exists at all rather than being folded into the build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode dependency:go-offline

COPY src/ src/
COPY LICENSE NOTICE ./
RUN ./mvnw --batch-mode -Pnative package -DskipTests

# Made here, not in the runtime stage: the runtime image has no shell to mkdir with. Owned by
# the user the container runs as, so a volume mounted over it inherits that ownership instead
# of arriving root-owned and unwritable, which is the whole reason to ship the directory.
RUN mkdir -p /config && chown 65532:65532 /config


# The one thing distroless does not carry that this binary needs. A GraalVM image links zlib
# dynamically, so without libz.so.1 it builds, ships, and then refuses to start:
#
#   error while loading shared libraries: libz.so.1: cannot open shared object file
#
# distroless/base-debian12 is built from Debian 12, so this is the same library built against
# the same glibc rather than a version borrowed from another distribution. Taking it costs
# 120 KB; the alternative, a full debian:trixie-slim runtime, costs 60 MB.
FROM debian:12-slim AS libs
RUN apt-get update \
    && apt-get install --yes --no-install-recommends zlib1g \
    && rm -rf /var/lib/apt/lists/*


# Distroless: glibc and the CA certificates, which with libz is the whole of what this needs
# to make an HTTPS request. No shell and no package manager, so there is nothing in the image
# to run but orkx.
FROM gcr.io/distroless/base-debian12:nonroot

# The glob matches one path per architecture — x86_64-linux-gnu or aarch64-linux-gnu — so the
# same line serves both without naming either.
COPY --from=libs /usr/lib/*-linux-gnu/libz.so.1 /usr/lib/

COPY --from=build /src/target/orkx /usr/local/bin/orkx
# Ownership is what matters here and it is what COPY carries. The mode does not survive —
# the directory arrives 0755 whatever the build stage set, and --chmod applies to file
# contents rather than to the directory itself — but nothing is lost by it: orkx writes
# session.json 0600 itself, and that file is the whole of the credential.
COPY --from=build --chown=65532:65532 /config /config

# The licence travels with the binary. NOTICE carries the section 7(b) term requiring the
# attribution orkx prints to be preserved, so shipping the program without it would ship the
# obligation without the statement of it.
COPY --from=build /src/LICENSE /src/NOTICE /usr/share/doc/orkx/

# Where the session goes. First of orkx's own list, so it needs no HOME to be set and no
# guessing about whose ~ this is: mount a volume here and a login survives the container.
#   docker run -v orkx:/config orknux/orknux-cli login
ENV ORKNUX_CONFIG_HOME=/config

VOLUME ["/config"]

# 65532 is distroless's nonroot. Named as a number so it needs nothing of /etc/passwd.
USER 65532:65532

ENTRYPOINT ["/usr/local/bin/orkx"]
CMD ["help"]

LABEL org.opencontainers.image.title="orkx" \
      org.opencontainers.image.description="Command line client for orknux-server, the open source agent orchestration platform." \
      org.opencontainers.image.url="https://orknux.ai" \
      org.opencontainers.image.source="https://github.com/michjak-szymanski/orknux-cli" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.vendor="Michał Szymański"
