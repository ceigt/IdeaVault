# IdeaVault 同步服务

服务端数据卷只保存 Android 客户端产生的 AES-GCM 密文、随机 IV、笔记 ID 和更新时间。加密密钥由 `.env` 中的 `DATA_KEY` 管理，并通过 HTTPS 提供给已认证设备；拥有 `.env` 的管理员理论上可以解密数据。

## VPS 部署

要求：一个已解析到 VPS 的域名、Docker Engine 和 Docker Compose Plugin，防火墙开放 80/443。

```bash
cp .env.example .env
# 将 DOMAIN 改成你的域名
# 分别执行下面两条命令，并把输出填入 .env
openssl rand -hex 32
openssl rand -base64 32

docker compose up -d --build
docker compose ps
curl https://你的域名/v1/health
```

然后在 Android 应用的同步设置中填写：

- 服务器地址：`https://你的域名`
- 访问令牌：`.env` 中的 `SYNC_TOKEN`

## 备份和升级

备份数据卷：

```bash
docker compose stop api
docker run --rm -v ideavault_ideavault_data:/data -v "$PWD":/backup alpine tar czf /backup/ideavault-data.tgz -C /data .
docker compose start api
```

升级：

```bash
git pull
docker compose up -d --build
```

请离线保存 `.env`。丢失或更改 `DATA_KEY` 后，服务器上的密文无法恢复。访问令牌可以轮换，但 `DATA_KEY` 不可轮换。
## 使用宿主机 Nginx 反向代理

如果 VPS 已运行 Nginx，不需要启动 Compose 内的 Caddy。Nginx 专用覆盖文件只把 API 发布到本机回环地址 `127.0.0.1:18080`，公网不能直接访问此端口。

### 1. 准备配置

```bash
cp .env.example .env
openssl rand -hex 32      # 填入 SYNC_TOKEN
openssl rand -base64 32   # 填入 DATA_KEY
```

仍然建议在 `DOMAIN` 中填写实际同步域名。`DATA_KEY` 一旦开始同步就不能更改。

### 2. 只启动 API

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.nginx.yml \
  up -d --build api

curl http://127.0.0.1:18080/v1/health
```

预期返回 `{"status":"ok"}`。不要把 18080 端口开放到防火墙公网入口。

### 3. 配置 Nginx

```bash
sudo cp server/nginx-ideavault.conf.example /etc/nginx/sites-available/ideavault
sudo sed -i 's/notes.example.com/你的实际域名/g' /etc/nginx/sites-available/ideavault
sudo ln -s /etc/nginx/sites-available/ideavault /etc/nginx/sites-enabled/ideavault
sudo nginx -t
sudo systemctl reload nginx
```

如果发行版使用 `/etc/nginx/conf.d/`，直接把示例复制为 `/etc/nginx/conf.d/ideavault.conf`。

### 4. 申请 HTTPS 证书

Ubuntu/Debian 可使用 Certbot：

```bash
sudo apt update
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的实际域名
```

选择自动跳转到 HTTPS，然后验证：

```bash
curl https://你的实际域名/v1/health
set -a; . ./.env; set +a
curl -sS -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $SYNC_TOKEN" \
  https://你的实际域名/v1/config
```

Android 应用中的服务器地址填写 `https://你的实际域名`，不要附加 `/v1`。

### 常用维护命令

```bash
# 查看日志
docker compose -f docker-compose.yml -f docker-compose.nginx.yml logs -f api

# 升级并重建
git pull
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api

# 重启
docker compose -f docker-compose.yml -f docker-compose.nginx.yml restart api
```
## 从 1.1.0 升级

1.2.0 改用服务器管理的 `DATA_KEY`，不兼容 1.1.0 的客户端口令密钥。如果服务器已经保存过 1.1.0 同步数据，请先在旧客户端保留本地完整笔记并做好备份，再使用新的空数据卷部署 1.2.0；不要直接删除唯一的数据副本。