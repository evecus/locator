# Locator 位置共享

互信小圈子的按需位置共享：一台 Linux VPS 跑 Rust 服务端，各设备装 APK，
谁想看谁就点一下，对方收到请求后现场取一次定位回传，全程 wss 加密。

```
┌─────────────┐  wss://  ┌──────────────┐  wss://  ┌─────────────┐
│ APK 客户端 A │ ◄──────► │ locator-server│ ◄──────► │ APK 客户端 B │
│  (请求方)    │   TLS    │  Rust + SQLite│   TLS    │  (被请求方)  │
└─────────────┘          └──────────────┘          └─────────────┘
                                    │
                              Caddy 反代（证书自动续期）
```

## 目录

```
locator/
├── server/        Rust 服务端（单二进制 + SQLite）
├── android/       Kotlin Compose 客户端（Osmdroid 免 key 地图）
├── deploy/        Caddyfile 示例 + systemd unit
└── dist/          已编译好的 x86_64 linux 服务端二进制
```

---

## 一、服务端部署（VPS）

### 1. 上传二进制并生成邀请码

```bash
# dist/locator-server 已预编译（x86_64 linux，glibc），也可自行编译：
# cd server && cargo build --release
sudo cp dist/locator-server /usr/local/bin/ && sudo chmod +x /usr/local/bin/locator-server

# 生成 3 个一次性邀请码
locator-server invite --db /var/lib/locator/locator.db --count 3
```

### 2. systemd 常驻

```bash
sudo useradd -r -s /usr/sbin/nologin locator || true
sudo mkdir -p /var/lib/locator && sudo chown locator /var/lib/locator
sudo cp deploy/locator.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now locator
```

服务监听 `127.0.0.1:8080`，仅本机可达，对外由 Caddy 转发。

### 3. Caddy 证书反代（deploy/Caddyfile）

```bash
sudo apt install caddy
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile   # 改成你的域名
sudo systemctl reload caddy
```

Caddy 自动申请/续期 Let's Encrypt 证书，客户端全程 `wss://`。

## 二、客户端

### 构建

```bash
cd android && ./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
# release：./gradlew assembleRelease（自行配置签名）
```

### 使用流程

1. 首页填 **服务端地址**（如 `loc.example.com`，自动走 wss/https）、**用户名**、**密码**、**邀请码** → 注册并登录
2. 进入设备列表，绿点 = 在线，点击在线设备 → 进入地图页等待对方上报
3. 对方 APP 收到请求后**现场取一次定位**并回传，地图自动打点居中
4. 被请求方不做任何确认（互信模式），定位**只在被请求时获取一次**，平时不采集

### 权限说明

- `ACCESS_FINE_LOCATION`：收到请求时单次定位
- 前台服务通知：维持 WS 长连接（`foregroundServiceType="location"`，符合 Android 10+ 后台定位限制）
- Android 13+ 会请求通知权限

## 三、协议速查（JSON 文本帧）

| type | 方向 | 说明 |
|---|---|---|
| `auth_ok` | S→C | 握手后确认，带自己的 device_id |
| `device_list` | S→C | 全量设备+在线状态，上下线即广播 |
| `ping` / `pong` | C→S / S→C | 应用层心跳（另有 OkHttp 协议层 ping 30s） |
| `request_location` | C→S | `{target_id}` |
| `location_request` | S→C | `{request_id, from_id, from_name}` |
| `location_report` | C→S | `{request_id, lat, lng, accuracy, ts}` |
| `location_result` | S→C | 转发给请求方 |
| `location_error` | 双向 | 定位失败 / 对方离线 / 15s 超时 |

HTTP：`POST /api/register`（username+password+invite_code）、`POST /api/login`、`GET /health`。

## 五、CI（GitHub Actions）

| workflow | 触发 | 产物 |
|---|---|---|
| `.github/workflows/server.yml` | push main / tag `v*` / 手动 | `locator-server-linux-x86_64`（tag 时自动附到 Release） |
| `.github/workflows/android.yml` | push main / tag `v*` / 手动 | `locator-arm64-release.apk`（R8 精简 + release 签名，tag 时自动附到 Release） |

### 配置 release 签名（仓库 Secrets）

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_B64` | keystore.jks 的 **base64**（`base64 -w0 keystore.jks`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_PASSWORD` | key 密码 |

生成 keystore：`keytool -genkeypair -v -keystore keystore.jks -alias locator -keyalg RSA -validity 10000`。
未配置 Secrets 时 workflow 会自动回退 debug 签名，构建不会失败。

### APK 精简手段

- R8 混淆（`minifyEnabled`）+ 资源收缩（`shrinkResources`）
- 仅保留中英文资源（`resourceConfigurations`）
- `abiFilters` 声明 arm64-v8a（本应用无 native 库，APK 天然全架构通用）

## 七、网页端（/user 与 /admin）

服务端自带两个网页（HTML/JS 内嵌在 Rust 源码里，无外部模板/静态文件）：

| 路径 | 用途 |
|---|---|
| `https://域名/user` | **网页客户端**：注册/登录（和 APK 同一套账号）→ 设备列表 + 地图，可直接在浏览器请求其他设备定位；被请求时用浏览器 Geolocation 现场定位回传，网页端也能被定位 |
| `https://域名/admin` | **管理后台**：设备列表 + 在线状态（5 秒刷新）、生成/查看邀请码、删除设备 |

### 开启管理后台

```bash
locator-server passwd --db /var/lib/locator/locator.db --password 你的密码
```

设置后访问 `/admin` 用该密码登录即可，无需重启服务。

### 技术说明

- 网页地图用 Leaflet（jsdelivr CDN），瓦片默认高德（国内加载快），JS 侧做 WGS-84→GCJ-02 偏移转换，可切 OSM
- 网页端登录态存 localStorage，WS 断线自动重连；token 失效连续 3 次握手失败自动退回登录页
- 管理接口走 `Authorization: Bearer <jwt>`（管理员 user_id=0），与普通用户 token 同一套签名密钥

## 六、常用运维命令

```bash
# 生成邀请码
locator-server invite --db /var/lib/locator/locator.db --count 5

# 查看日志
journalctl -u locator -f

# 数据库位置
/var/lib/locator/locator.db   # users / invites / config，不存位置历史
```
