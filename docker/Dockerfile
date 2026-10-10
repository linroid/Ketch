# syntax=docker/dockerfile:1
#
# The Ketch server image: the native `ketch` binary of a release on Debian, for linux/amd64 and
# linux/arm64. It copies each architecture's binary and license notices from
# build/linux-<arch>/ in the build context, which the release workflow fills from the CLI
# archives; see docs/docker.md to build one yourself.
FROM debian:trixie-slim

ARG TARGETARCH
ARG VERSION=dev

LABEL org.opencontainers.image.title="Ketch" \
  org.opencontainers.image.description="Ketch download server with web UI: HTTP, FTP, BitTorrent" \
  org.opencontainers.image.source="https://github.com/linroid/Ketch" \
  org.opencontainers.image.licenses="Apache-2.0" \
  org.opencontainers.image.version="${VERSION}"

COPY --chmod=755 build/linux-${TARGETARCH}/ketch /usr/local/bin/ketch
COPY build/linux-${TARGETARCH}/licenses/ /usr/share/doc/ketch/licenses/
COPY --chmod=755 entrypoint.sh /usr/local/bin/ketch-entrypoint

# KETCH_DOCKER makes `ketch update` point to the image instead of replacing its binary.
ENV KETCH_DOCKER=1 \
  KETCH_CONFIG_DIR=/config \
  KETCH_DOWNLOAD_DIR=/downloads \
  KETCH_TORRENT_PORT=16881 \
  PUID=1000 \
  PGID=1000 \
  UMASK=022

VOLUME ["/config", "/downloads"]
# The web UI and REST API, then BitTorrent peers (TCP) and DHT (UDP).
EXPOSE 8642/tcp 16881/tcp 16881/udp

# Healthy once the server listens and has restored the saved downloads.
HEALTHCHECK --interval=30s --timeout=10s --start-period=1m --start-interval=5s --retries=3 \
  CMD ["ketch", "health"]

ENTRYPOINT ["ketch-entrypoint"]
CMD ["server"]
