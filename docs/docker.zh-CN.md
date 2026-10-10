# Docker

[English](docker.md)

Ketch 的 Docker 镜像在 NAS 或家用服务器上运行 [`ketch server`](../cli/README.md#server)：下载引擎、
网页版和 REST API，Ketch 各个 App 和浏览器扩展都可以连接它。它能下载 HTTP(S)、FTP/FTPS、BitTorrent
和磁力链接，以及有限长度的 HLS/DASH 视频流。

- 提供 x64（`linux/amd64`）和 ARM64（`linux/arm64`）镜像，内含原生 `ketch` 程序，基于 Debian，
  不需要 Java
- 每次发布都会推送到 GitHub Container Registry 的 `ghcr.io/linroid/ketch` 和 Docker Hub 的
  `linroid/ketch`
- 以你指定的用户运行（`PUID`、`PGID`），下载的文件归 NAS 上的这个用户所有
- 自带健康检查，恢复完已保存的下载后即为健康
- 用环境变量、配置文件或两者一起配置
- 固定的 BitTorrent 端口 16881，可以在路由器上做端口转发

通用设置之后是 [飞牛 fnOS](#飞牛-fnos) 和 [TrueNAS](#truenas) 的步骤。

## 快速开始

```bash
docker run -d --name ketch --restart unless-stopped \
  -e PUID=1000 -e PGID=1000 \
  -v /srv/ketch:/config -v /srv/downloads:/downloads \
  -p 8642:8642 -p 16881:16881 -p 16881:16881/udp \
  ghcr.io/linroid/ketch
```

或者用 Docker Compose，参考 [`docker/compose.yaml`](../docker/compose.yaml)：

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
      KETCH_API_TOKEN: ""
    volumes:
      - ./config:/config
      - ./downloads:/downloads
    ports:
      - "8642:8642" # 网页版和 REST API
      - "16881:16881" # BitTorrent 连接
      - "16881:16881/udp" # BitTorrent DHT
```

然后打开 `http://<NAS 地址>:8642`。

## 连接

Ketch 在网络上提供服务时需要访问码，也就是它的 API 令牌。可以用 `KETCH_API_TOKEN` 自己设置；留空时，
首次启动会自动生成一个，打印在容器日志里，并保存在 `/config/api-token`：

```text
  Auth:          new token, saved to /config/api-token
  Token:         3f2a9c1e…
```

之后的启动都从这个文件读取；`docker exec ketch cat /config/api-token` 可以再次查看，删除文件后
下次启动会生成新的访问码。

- **网页版**：打开 `http://<NAS 地址>:8642` 并输入访问码，或者直接打开
  `http://<NAS 地址>:8642/#token=<访问码>`
- **Ketch App**：**设备 → 添加设备**，填入地址和访问码。使用[主机网络](#网络)时，App 还能自动
  发现这台 NAS
- **浏览器扩展**：添加服务器 `http://<NAS 地址>:8642`，并填入访问码

## 文件夹

| 路径 | 存放 |
|---|---|
| `/config` | `config.toml`（可选）、任务数据库 `ketch.db`、访问码 `api-token`，以及 `torrent-state`（DHT 节点和 Tracker 列表） |
| `/downloads` | 下载的文件，除非某个下载指定了别的文件夹 |

两个都要挂载：不挂载的话，Docker 会把它们放在匿名卷里，重建容器时很容易丢失。客户端只能保存到
`/downloads` 和 `KETCH_ALLOWED_DIRS` 里的文件夹，这些文件夹也要挂载，例如
`-v /srv/media:/media -e KETCH_ALLOWED_DIRS=/media`。设置了访问码而没有设置这个列表时，客户端可以
保存到容器里的任何位置；参见[保存文件夹](../cli/README.md#save-folders)。

## 用户和权限

容器以 root 启动，然后以 `PUID`:`PGID`（默认 1000:1000）运行 Ketch。把它们设为应当拥有下载文件的
用户和用户组，通常就是挂载到 `/downloads` 的文件夹的所有者；在 NAS 上运行 `id <用户名>` 可以看到。
启动 Ketch 之前，入口脚本会：

- 把 `/config` 里的所有文件交给这个用户：这个文件夹只存放 Ketch 自己的文件
- 只有当 `/downloads` 为空且属于 root 时（也就是没有挂载任何东西、由 Docker 自动创建时），才把它
  交给这个用户。你挂载的文件夹保持原有的所有者和权限；如果这个用户无法写入，日志里会提示

`UMASK`（默认 `022`）决定新文件的权限：`002` 让同组用户也能写入。`PUID=0` 会以 root 运行 Ketch，
不建议这样做。

也可以用 compose 的 `user: "568:568"` 或 `docker run --user` 以某个用户启动容器。这时 Ketch 以这个
用户运行，入口脚本不会修改任何所有权，所以 `/config` 和 `/downloads` 必须已经可以被它写入。

## 端口

| 端口 | 用途 |
|---|---|
| `8642/tcp` | 网页版和 REST API（`KETCH_PORT`） |
| `16881/tcp` | 其他 BitTorrent 用户连接 Ketch（`KETCH_TORRENT_PORT`） |
| `16881/udp` | BitTorrent DHT，使用同一个端口 |

即使外部无法访问 BitTorrent 端口，种子也能下载，但能访问时可以连上更多用户：在路由器上把 TCP 和
UDP 16881 转发到 NAS。Ketch 对外公布的是它监听的端口，所以映射时两边要用同一个端口号。要换成别的
端口，比如 51413，就设置 `KETCH_TORRENT_PORT=51413`，并映射 `51413:51413` 和 `51413:51413/udp`。
NAS 上的其他 BitTorrent 客户端需要使用不同的端口。

## 环境变量

环境变量优先于 `/config/config.toml`，而作为容器命令传入的 `ketch server` 选项又优先于两者。值为空
等同于没有设置。

| 变量 | 镜像中的默认值 | 说明 |
|---|---|---|
| `PUID`、`PGID` | `1000` | 运行 Ketch 的用户和用户组；参见[用户和权限](#用户和权限) |
| `UMASK` | `022` | 新文件的权限 |
| `TZ` | UTC | 日志使用的时区，例如 `Asia/Shanghai` |
| `KETCH_API_TOKEN` | *（自动生成）* | 访问码；参见[连接](#连接) |
| `KETCH_NAME` | `Ketch` | App 里显示的服务器名称 |
| `KETCH_PORT` | `8642` | 网页版和 REST API 的端口 |
| `KETCH_HOST` | `0.0.0.0` | 监听的地址 |
| `KETCH_DOWNLOAD_DIR` | `/downloads` | 下载文件夹 |
| `KETCH_ALLOWED_DIRS` | | 除下载文件夹外，客户端可以保存和删除文件的文件夹，用逗号分隔 |
| `KETCH_TORRENT_PORT` | `16881` | BitTorrent 端口（TCP 和 UDP）；`0` 表示每次启动随机选择空闲端口 |
| `KETCH_SPEED_LIMIT` | 不限速 | 全局限速，例如 `10m`（MB/s）或 `500k`（KB/s） |
| `KETCH_MAX_CONCURRENT_DOWNLOADS` | `4` | 同时进行的下载数；`0` 表示不限 |
| `KETCH_MAX_CONNECTIONS_PER_DOWNLOAD` | `4` | 每个 HTTP 或 FTP 下载的连接数 |
| `KETCH_MAX_CONNECTIONS_PER_HOST` | `16` | 同一主机同时进行的下载数；`0` 表示不限 |
| `KETCH_CORS` | 有访问码时为 `*` | 允许调用 API 的网页来源，用逗号分隔 |
| `KETCH_ALLOWED_HOSTS` | | 没有访问码时额外接受的 `Host` 名称，用逗号分隔 |
| `KETCH_MDNS` | `true` | 是否在局域网中广播这台服务器 |
| `KETCH_CONFIG_DIR` | `/config` | `config.toml`、数据库和访问码所在的文件夹 |

没有对应变量的设置，例如额外的 Tracker 或重试次数，写在
[`/config/config.toml`](../cli/README.md#configuration-file) 里。容器命令接受 `ketch server` 的
[选项](../cli/README.md#server)：`docker run ... ghcr.io/linroid/ketch --port 9000`，或在 compose 里写
`command: ["server", "--port", "9000"]`。

## 健康检查

镜像的 `HEALTHCHECK` 运行 `ketch health`，它会请求服务器的 `GET /api/health`：Ketch 恢复完之前保存的
下载后返回 `200 {"status":"ready"}`，在此之前返回 `503 {"status":"starting"}`。之后 Docker 会把容器
显示为 `healthy`，服务器停止响应时则显示为 `unhealthy`。`ketch health` 会请求正在运行的服务器所报告
的地址，所以作为容器命令传入的 `--port` 等选项也会生效。这个接口不需要访问码，Uptime Kuma 等监控
工具也可以使用。Ketch 恢复下载期间（通常只需片刻），其他 API 都会返回 `503`，App 会在恢复完成后
连上。

## 网络

默认的 bridge 网络按上面的端口映射。使用主机网络（`network_mode: host`，不写 `ports`）时，Ketch
直接监听 NAS 自己的地址，并通过 mDNS 广播自己，同一网络里的 Ketch App 不用输入地址就能找到它。
请先确认 NAS 上的 8642 和 16881 端口没有被占用。

请保留访问码。不使用访问码时（`command: ["server", "--no-token"]`），网络里的任何人都能使用这台
服务器，而且它只响应发给容器自身的请求：在 bridge 网络中，要把访问它所用的名称和地址写进
`KETCH_ALLOWED_HOSTS`，例如 `192.168.1.20,nas.local`。参见[接受的主机](../cli/README.md#accepted-hosts)。

## 更新

拉取新镜像并重建容器；设置、访问码和下载的文件都保存在你的文件夹里：

```bash
docker compose pull && docker compose up -d
```

| 标签 | 跟随 |
|---|---|
| `latest` | 最新的正式版 |
| `0.4` | 最新的 `0.4.x` 版本 |
| `0.4.0` | 这个版本，也包括 `0.4.0-rc1` 这样的预发布版 |

容器里不能使用 `ketch update`：镜像需要整体更新。

## 飞牛 fnOS

在飞牛 fnOS 上，把 Ketch 作为 **Docker** 应用里的一个 Compose 项目来运行。

1. 在 **文件管理** 里为 Ketch 的设置新建一个文件夹，例如 `docker/ketch`，并选好下载文件存放的文件夹。
2. 查看应当拥有下载文件的飞牛用户的 ID：通过 SSH 登录后运行 `id`。
3. 打开 **Docker → Compose**，新增项目，名称填 `ketch`，路径选择刚才的设置文件夹，创建
   `docker-compose.yml` 并粘贴 [`docker/fnos.yaml`](../docker/fnos.yaml)：

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

   把 `PUID`、`PGID` 和下载文件夹（`/vol1/1000/Downloads`）换成你自己的。
4. 启动项目。项目日志里会显示访问码，然后打开 `http://<飞牛地址>:8642`。

如果拉取 `ghcr.io` 太慢，可以改用 Docker Hub 上的 `linroid/ketch`，并在 Docker 应用的设置里开启
镜像加速。更新时，在 **Docker → 镜像** 里重新拉取镜像，再重新构建项目。

## TrueNAS

TrueNAS 24.10（Electric Eel）及以后的版本中，应用就是 Docker 容器，**Install via YAML** 可以直接
使用 compose 文件。

1. 在 **Datasets** 里为 Ketch 的设置创建一个数据集，例如 `tank/apps/ketch`，选择 **Apps** 预设，
   这样 apps 用户（568）就有访问权限。选好下载文件存放的数据集，在它的权限里给 apps 用户写入权限，
   或者记下它所有者的 ID。
2. 打开 **Apps → Discover Apps**，选择 **⋮ → Install via YAML**，名称填 `ketch`，粘贴
   [`docker/truenas.yaml`](../docker/truenas.yaml)，把两个 `/mnt` 路径换成你的数据集；如果 apps
   用户无法写入下载数据集，把 `568` 换成所有者的 ID。
3. 保存。应用运行后，在它的日志里查看访问码（或者事先设置 `KETCH_API_TOKEN`），然后打开
   `http://<TrueNAS 地址>:8642`。

更新时，让 TrueNAS 重新拉取镜像并重新部署应用；设置、访问码和下载的文件都保存在数据集里。

## 自己构建镜像

镜像从 `docker/build/linux-<架构>/` 复制各个架构的原生程序。发布流程会从命令行版本的压缩包中
取出它们；自己构建时，可以使用在对应架构的 Linux 上编译的程序（`./gradlew :cli:nativeCompile`，
参见[命令行](../cli/README.md#build--run)），或者从发布的压缩包中取出：

```bash
mkdir -p docker/build/linux-amd64
tar -xzf ketch-cli-<version>-linux-x64.tar.gz -C docker/build/linux-amd64
docker build -t ketch docker
docker/smoke-test.sh ketch
```

发布的压缩包经过 UPX 压缩；`upx -d docker/build/linux-amd64/ketch` 可以解压程序，这样每次启动
占用的内存更少。`docker/test-entrypoint.sh` 不需要程序就能测试入口脚本。
