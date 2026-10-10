# Docker

[简体中文](docker.zh-CN.md)

The Ketch Docker image runs [`ketch server`](../cli/README.md#server) on a NAS or home server: the
download engine with its web UI and REST API, which the Ketch apps and the browser extension
connect to. It downloads HTTP(S), FTP/FTPS, BitTorrent and magnet links, and finite HLS/DASH
streams.

- Images for x64 (`linux/amd64`) and ARM64 (`linux/arm64`), with the native `ketch` binary on
  Debian; no Java inside
- Published with every release as `ghcr.io/linroid/ketch` on GitHub Container Registry and
  `linroid/ketch` on Docker Hub
- Runs as the user you choose (`PUID`, `PGID`), so downloads belong to a user of your NAS
- A health check that passes once saved downloads are restored
- Configured through environment variables, a config file, or both
- A fixed BitTorrent port, 16881, which you can forward on your router

Guides for [TrueNAS](#truenas) and [fnOS](#fnos) follow the general setup.

## Quick start

```bash
docker run -d --name ketch --restart unless-stopped \
  -e PUID=1000 -e PGID=1000 \
  -v /srv/ketch:/config -v /srv/downloads:/downloads \
  -p 8642:8642 -p 16881:16881 -p 16881:16881/udp \
  ghcr.io/linroid/ketch
```

Or with Docker Compose, using [`docker/compose.yaml`](../docker/compose.yaml):

```yaml
services:
  ketch:
    image: ghcr.io/linroid/ketch:latest
    container_name: ketch
    restart: unless-stopped
    environment:
      PUID: "1000"
      PGID: "1000"
      TZ: Etc/UTC
      KETCH_API_TOKEN: ""
    volumes:
      - ./config:/config
      - ./downloads:/downloads
    ports:
      - "8642:8642" # web UI and REST API
      - "16881:16881" # BitTorrent peers
      - "16881:16881/udp" # BitTorrent DHT
```

Then open `http://<NAS address>:8642`.

## Connecting

Ketch asks for an access code, its API token, as the server listens on the network. Set your own
with `KETCH_API_TOKEN`; left empty, the first start creates one, prints it in the container's log
and keeps it in `/config/api-token`:

```text
  Auth:          new token, saved to /config/api-token
  Token:         3f2a9c1e…
```

Later starts read it from there; `docker exec ketch cat /config/api-token` shows it again, and
deleting the file makes a new one on the next start.

- **Web UI**: open `http://<NAS address>:8642` and enter the code, or open
  `http://<NAS address>:8642/#token=<code>`
- **Ketch apps**: **Devices → Add device**, then the address and the code. On the
  [host network](#networking), the apps also find the NAS on their own
- **Browser extension**: add `http://<NAS address>:8642` as a server with the code

## Folders

| Path | Holds |
|---|---|
| `/config` | `config.toml` (optional), the task database `ketch.db`, the access code `api-token`, and `torrent-state` (DHT nodes and tracker lists) |
| `/downloads` | Downloads, unless a download names another folder |

Mount both: without a mount, Docker keeps them in an anonymous volume that is easy to lose when
the container is recreated. Clients can only save to `/downloads` and the folders in
`KETCH_ALLOWED_DIRS`; mount those too, for example `-v /srv/media:/media -e
KETCH_ALLOWED_DIRS=/media`. With a token and no list, clients may save anywhere in the
container; see [save folders](../cli/README.md#save-folders).

## Users and permissions

The container starts as root, then runs Ketch as `PUID`:`PGID` (1000:1000 by default). Set them
to the user and group that should own the downloads, usually the owner of the folder you mount at
`/downloads`; `id <user>` on the NAS shows them. Before starting Ketch, the entrypoint:

- gives every file in `/config` to that user: the folder only holds Ketch's own files
- gives `/downloads` to that user only when it is empty and owned by root, as when Docker created
  it because nothing was mounted. A folder you mounted keeps its owner and permissions; when the
  user cannot write to it, the log says so

`UMASK` (default `022`) sets the permissions of new files: `002` lets the group write to them too.
`PUID=0` runs Ketch as root, which is not recommended.

You can also start the container as a user with compose's `user: "568:568"` or `docker run
--user`. Ketch then runs as that user and the entrypoint changes no ownership, so `/config` and
`/downloads` must already be writable by it.

## Ports

| Port | Used for |
|---|---|
| `8642/tcp` | Web UI and REST API (`KETCH_PORT`) |
| `16881/tcp` | BitTorrent peers connecting to Ketch (`KETCH_TORRENT_PORT`) |
| `16881/udp` | BitTorrent DHT, on the same port |

Torrents download without the BitTorrent port reachable from outside, but more peers can connect
when it is: forward TCP and UDP 16881 on your router to the NAS. Ketch announces the port it
listens on, so map the same number on both sides. To use another port, say 51413, set
`KETCH_TORRENT_PORT=51413` and map `51413:51413` and `51413:51413/udp`. Another BitTorrent client
on the NAS needs a port of its own.

## Environment variables

Environment variables take precedence over `/config/config.toml`, and `ketch server` options
given as the container's command over both. Blank values count as unset.

| Variable | Default in the image | Description |
|---|---|---|
| `PUID`, `PGID` | `1000` | User and group Ketch runs as; see [users and permissions](#users-and-permissions) |
| `UMASK` | `022` | Permissions of new files |
| `TZ` | UTC | Time zone of the log, such as `Europe/Berlin` |
| `KETCH_API_TOKEN` | *(created)* | Access code; see [connecting](#connecting) |
| `KETCH_NAME` | `Ketch` | Name the apps show for this server |
| `KETCH_PORT` | `8642` | Port of the web UI and REST API |
| `KETCH_HOST` | `0.0.0.0` | Address to listen on |
| `KETCH_DOWNLOAD_DIR` | `/downloads` | Download folder |
| `KETCH_ALLOWED_DIRS` | | Comma-separated folders, besides the download folder, clients may save to and delete from |
| `KETCH_TORRENT_PORT` | `16881` | BitTorrent port (TCP and UDP); `0` picks a free one at every start |
| `KETCH_SPEED_LIMIT` | unlimited | Global speed limit, such as `10m` (MB/s) or `500k` (KB/s) |
| `KETCH_MAX_CONCURRENT_DOWNLOADS` | `4` | Downloads at once; `0` for no limit |
| `KETCH_MAX_CONNECTIONS_PER_DOWNLOAD` | `4` | Connections per HTTP or FTP download |
| `KETCH_MAX_CONNECTIONS_PER_HOST` | `16` | Downloads from one host at once; `0` for no limit |
| `KETCH_CORS` | `*` with a token | Comma-separated origins whose web pages may call the API |
| `KETCH_ALLOWED_HOSTS` | | Comma-separated extra `Host` names accepted without a token |
| `KETCH_MDNS` | `true` | Announce the server on the local network |
| `KETCH_CONFIG_DIR` | `/config` | Folder of `config.toml`, the database and the access code |

Settings without a variable, such as extra trackers or retries, go in
[`/config/config.toml`](../cli/README.md#configuration-file). The container's command takes
`ketch server` [options](../cli/README.md#server): `docker run ... ghcr.io/linroid/ketch --port
9000`, or `command: ["server", "--port", "9000"]` in compose.

## Health check

The image's `HEALTHCHECK` runs `ketch health`, which asks the server's `GET /api/health`: it
answers `200 {"status":"ready"}` once Ketch has restored the downloads saved by earlier runs, and
`503 {"status":"starting"}` before. Docker shows the container as `healthy` from then on, or
`unhealthy` when the server stops answering. `ketch health` asks where the running server says it
listens, so it follows options given as the container's command, such as `--port`. The endpoint
needs no access code, so monitors such as Uptime Kuma can ask it too. While Ketch restores the
downloads, which takes a moment, the rest of the API answers `503` and the apps connect once it
is done.

## Networking

The default bridge network maps the ports above. With the host network
(`network_mode: host`, without `ports`), Ketch listens on the NAS's own addresses instead, and
announces itself over mDNS, so the Ketch apps on the same network list it without typing an
address. Check that ports 8642 and 16881 are free on the NAS first.

Keep the access code. Without one (`command: ["server", "--no-token"]`), anyone on your network
can use the server, and it only answers requests addressed to the container itself: on the bridge
network, list the names and addresses you reach it by in `KETCH_ALLOWED_HOSTS`, such as
`192.168.1.20,nas.local`. See [accepted hosts](../cli/README.md#accepted-hosts).

## Updating

Pull the new image and recreate the container; settings, the access code and downloads stay in
your folders:

```bash
docker compose pull && docker compose up -d
```

| Tag | Follows |
|---|---|
| `latest` | The newest release |
| `0.4` | The newest `0.4.x` release |
| `0.4.0` | That release, pre-releases such as `0.4.0-rc1` included |

`ketch update` does not work inside the container: the image updates as a whole.

## TrueNAS

On TrueNAS 24.10 (Electric Eel) or later, apps are Docker containers, and **Install via YAML**
takes a compose file.

1. Under **Datasets**, create a dataset for Ketch's settings, such as `tank/apps/ketch`, with the
   **Apps** preset, which gives the apps user (568) access. Pick the dataset downloads go to and
   give the apps user write access to it in its permissions, or note its owner's IDs.
2. Open **Apps → Discover Apps**, choose **⋮ → Install via YAML**, name it `ketch` and paste
   [`docker/truenas.yaml`](../docker/truenas.yaml):

   ```yaml
   services:
     ketch:
       image: ghcr.io/linroid/ketch:latest
       restart: unless-stopped
       environment:
         PUID: "568"
         PGID: "568"
         TZ: Etc/UTC
         KETCH_NAME: TrueNAS
         KETCH_API_TOKEN: ""
       volumes:
         - /mnt/tank/apps/ketch:/config
         - /mnt/tank/downloads:/downloads
       ports:
         - "8642:8642"
         - "16881:16881"
         - "16881:16881/udp"
   ```

   Replace the two `/mnt` paths with your datasets, and `568` with the owner's IDs if the apps
   user cannot write to the downloads dataset.
3. Save. Once the app is running, open its logs for the access code, or set `KETCH_API_TOKEN`
   first, and open `http://<TrueNAS address>:8642`.

To update, have TrueNAS pull the image again and redeploy the app; the datasets keep the
settings, the access code and the downloads.

The **Custom App** form works too: image `ghcr.io/linroid/ketch`, tag `latest`, the environment
variables above, the three ports, and host path storage mounted at `/config` and `/downloads`.

## fnOS

On fnOS (飞牛), set Ketch up as a Compose project of the **Docker** app.

1. In **Files**, create a folder for Ketch's settings, such as `docker/ketch`, and pick the
   folder downloads go to.
2. Find the IDs of the fnOS user who should own the downloads: sign in over SSH and run `id`.
3. In **Docker → Compose**, add a project, name it `ketch`, choose the settings folder as its
   path, create its `docker-compose.yml` and paste [`docker/fnos.yaml`](../docker/fnos.yaml):

   ```yaml
   services:
     ketch:
       image: ghcr.io/linroid/ketch:latest
       container_name: ketch
       restart: unless-stopped
       environment:
         PUID: "1000"
         PGID: "1000"
         TZ: Asia/Shanghai
         KETCH_NAME: 飞牛 NAS
         KETCH_API_TOKEN: ""
       volumes:
         - ./config:/config
         - /vol1/1000/Downloads:/downloads
       ports:
         - "8642:8642"
         - "16881:16881"
         - "16881:16881/udp"
   ```

   Replace `PUID`, `PGID` and the download folder (`/vol1/1000/Downloads`) with yours.
4. Start the project. Its log shows the access code; open `http://<fnOS address>:8642`.

Where `ghcr.io` is slow, use `linroid/ketch` from Docker Hub with a registry mirror enabled in
the Docker app's settings. To update, pull the image in **Docker → Images** and rebuild the
project.

## Building the image

The image copies the native binary of each architecture from `docker/build/linux-<arch>/`. The
release workflow fills it from the CLI archives; to build your own, use a binary built on Linux
for the architecture (`./gradlew :cli:nativeCompile`, see the [CLI](../cli/README.md#build--run))
or from a release archive:

```bash
mkdir -p docker/build/linux-amd64
tar -xzf ketch-cli-<version>-linux-x64.tar.gz -C docker/build/linux-amd64
docker build -t ketch docker
docker/smoke-test.sh ketch
```

Release archives are UPX-compressed; `upx -d docker/build/linux-amd64/ketch` unpacks the binary,
which then uses less memory at every start. `docker/test-entrypoint.sh` tests the entrypoint
without a binary.
