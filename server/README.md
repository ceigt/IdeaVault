# IdeaVault 多用户同步服务

同一台服务可供多人使用。每个用户拥有独立的用户名、访问令牌、数据密钥和笔记集合，用户令牌不能读取其他人的数据。`ADMIN_TOKEN` 只用于创建和列出账户，绝对不要填进 Android 应用或发给普通用户。

服务端数据卷保存 AES-GCM 密文和账户密钥。拥有 VPS 或数据卷管理权限的人理论上可以取得密钥，所以它不是零知识服务。请同时备份 `.env` 与数据卷。

## 首次部署（宿主机已有 Nginx）

要求：域名已解析到 VPS；已安装 Docker Engine、Docker Compose Plugin 和 Nginx；防火墙开放 80/443，**不要**开放 18080。

### 1. 下载并生成配置

```bash
git clone https://github.com/ceigt/IdeaVault.git /opt/ideavault
cd /opt/ideavault
cp .env.example .env

openssl rand -hex 32     # 复制到 ADMIN_TOKEN
openssl rand -hex 32     # 复制到 SYNC_TOKEN（owner 的个人令牌）
openssl rand -base64 32  # 复制到 DATA_KEY（owner 的数据密钥）
nano .env
```

把 `DOMAIN` 改为实际域名；`OWNER_USERNAME` 可保留为 `owner`。三个密钥不能相同，`.env` 不要提交到 Git。`DATA_KEY` 一旦使用就不要更改。

### 2. 启动 API

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.nginx.yml \
  up -d --build api

docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
curl http://127.0.0.1:18080/v1/health
```

预期返回 `{"status":"ok"}`。

### 3. 配置 Nginx 与 HTTPS

```bash
sudo cp server/nginx-ideavault.conf.example /etc/nginx/sites-available/ideavault
sudo sed -i 's/notes.example.com/你的实际域名/g' /etc/nginx/sites-available/ideavault
sudo ln -s /etc/nginx/sites-available/ideavault /etc/nginx/sites-enabled/ideavault
sudo nginx -t
sudo systemctl reload nginx

sudo apt update
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的实际域名
```

若发行版使用 `/etc/nginx/conf.d/`，把示例复制为 `/etc/nginx/conf.d/ideavault.conf`。验证公网入口：

```bash
curl https://你的实际域名/v1/health
set -a; . ./.env; set +a
curl -sS -H "Authorization: Bearer $SYNC_TOKEN" \
  https://你的实际域名/v1/config
```

配置 Android：服务器地址填 `https://你的实际域名`（不要附加 `/v1`），访问令牌填个人令牌。首次同步成功后右上角会显示 `owner`；点击后域名右侧出现绿色勾。

## 创建朋友账户

用户名仅允许 3–32 位小写字母、数字、下划线或短横线。以下命令会生成独立令牌和数据密钥：

```bash
cd /opt/ideavault
set -a; . ./.env; set +a
curl -sS -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"friend"}' \
  https://你的实际域名/v1/admin/users
unset ADMIN_TOKEN SYNC_TOKEN DATA_KEY
```

响应示例：

```json
{"username":"friend","accessToken":"一串新令牌"}
```

访问令牌只在创建时返回一次。通过安全渠道把“服务器地址 + 该令牌”发给朋友；不要发送 `ADMIN_TOKEN`、`SYNC_TOKEN`、`DATA_KEY` 或 `.env`。朋友安装同一个 APK 后填写这两项，他只能同步自己的笔记。

列出账户（不会显示个人令牌）：

```bash
cd /opt/ideavault
set -a; . ./.env; set +a
curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://你的实际域名/v1/admin/users
unset ADMIN_TOKEN SYNC_TOKEN DATA_KEY
```

## 从 1.2.0 升级到 2.0.0

先备份，然后更新代码，并在现有 `.env` 中新增 `ADMIN_TOKEN` 和可选的 `OWNER_USERNAME=owner`。保留原来的 `SYNC_TOKEN`、`DATA_KEY` 和 Docker 数据卷：服务启动时会把旧笔记自动迁移到 owner，Android 端不需要重新输入配置。

```bash
cd /opt/ideavault
docker compose -f docker-compose.yml -f docker-compose.nginx.yml stop api

docker run --rm \
  -v ideavault_ideavault_data:/data \
  -v "$PWD":/backup \
  alpine tar czf /backup/ideavault-data-before-v2.tgz -C /data .

openssl rand -hex 32  # 写入 .env 的 ADMIN_TOKEN
git pull
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api
docker compose -f docker-compose.yml -f docker-compose.nginx.yml logs --tail=100 api
```

如果 Compose 项目名不是 `ideavault`，先运行 `docker volume ls` 找到实际数据卷名。不要创建新的空卷替代旧卷。

## 备份、升级和排错

完整备份应包含 `.env` 和数据卷归档，并存到 VPS 之外。仅备份其中一个不足以恢复全部账户。

```bash
# 查看状态与日志
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
docker compose -f docker-compose.yml -f docker-compose.nginx.yml logs -f api

# 更新并重建
git pull
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api

# 本机 API 与公网 HTTPS 检查
curl http://127.0.0.1:18080/v1/health
curl https://你的实际域名/v1/health
```

验证是否同步成功：在设备 A 新建一条测试笔记，等待右上角账户详情出现绿色勾；设备 B 使用**同一用户令牌**打开应用，笔记应出现。使用朋友令牌登录的设备不应看到 owner 的测试笔记，这同时验证了用户隔离。

绿色勾表示该客户端最近一次完成了健康检查、令牌鉴权和笔记合并；网络中断或同步错误时会变成红色空心圆并显示错误信息。

## 使用内置 Caddy（可选）

如果 VPS 没有宿主机 Nginx，可直接运行：

```bash
docker compose up -d --build
docker compose ps
curl https://你的实际域名/v1/health
```

Compose 中的 Caddy 会监听 80/443 并自动申请 HTTPS 证书；不要与宿主机 Nginx 同时占用这些端口。