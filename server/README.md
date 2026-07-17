# IdeaVault 自助注册同步服务

用户可直接在 Android App 中注册用户名和密码，不需要管理员手工分配令牌。每个用户拥有独立的数据密钥、笔记集合和登录会话，无法读取其他用户的笔记。

密码采用带随机盐的 Argon2id 摘要，服务端不保存明文密码；App 也不保存密码，只保存 Android Keystore 加密后的随机会话令牌。服务端管理数据加密密钥，因此它不是零知识服务。

## 首次部署（宿主机 Nginx）

要求：域名已解析到 VPS；已安装 Docker Engine、Docker Compose Plugin、Nginx；防火墙开放 80/443，不开放 18080。

```bash
git clone https://github.com/ceigt/IdeaVault.git /opt/ideavault
cd /opt/ideavault
cp .env.example .env

openssl rand -hex 32     # ADMIN_TOKEN
openssl rand -hex 32     # SYNC_TOKEN（兼容 owner 的恢复令牌）
openssl rand -base64 32  # DATA_KEY（owner 数据密钥）
nano .env
```

`.env` 示例：

```dotenv
DOMAIN=notes.example.com
ADMIN_TOKEN=分别生成的随机字符串
ALLOW_REGISTRATION=true
OWNER_USERNAME=owner
SYNC_TOKEN=另一条随机字符串
DATA_KEY=base64随机密钥
```

三个密钥不要重复。`DATA_KEY` 一旦使用就不能更改。

启动 API：

```bash
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
curl http://127.0.0.1:18080/v1/health
```

配置 Nginx 和 HTTPS：

```bash
sudo cp server/nginx-ideavault.conf.example /etc/nginx/sites-available/ideavault
sudo sed -i 's/notes.example.com/你的实际域名/g' /etc/nginx/sites-available/ideavault
sudo ln -s /etc/nginx/sites-available/ideavault /etc/nginx/sites-enabled/ideavault
sudo nginx -t
sudo systemctl reload nginx

sudo apt update
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的实际域名
curl https://你的实际域名/v1/health
```

App 中填写 `https://你的实际域名`，不要附加 `/v1`。点击“没有账户？立即注册”，自行设置用户名和密码即可。

## 公开注册开关

`ALLOW_REGISTRATION=true` 允许任何知道域名的人注册。服务端对每个来源地址限制每分钟 20 次认证请求，但如果只想让朋友在一段时间内注册，可以在大家注册完成后改为：

```dotenv
ALLOW_REGISTRATION=false
```

应用配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d api
```

关闭后现有用户仍可正常登录和同步，只禁止创建新账户。需要新增朋友时再临时改回 `true`。

## 从 2.0.0 升级到 2.1.0

先保留 `.env` 和原数据卷：

```bash
cd /opt/ideavault
cp .env .env.backup-before-v2.1
docker volume ls
docker compose -f docker-compose.yml -f docker-compose.nginx.yml stop api

docker run --rm \
  -v ideavault_ideavault_data:/data \
  -v "$PWD":/backup \
  alpine tar czf /backup/ideavault-data-before-v2.1.tgz -C /data .
```

若数据卷名称不同，请使用 `docker volume ls` 显示的实际名称。然后升级：

```bash
printf '\nALLOW_REGISTRATION=true\n' >> .env
git pull
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api
docker compose -f docker-compose.yml -f docker-compose.nginx.yml logs --tail=100 api
```

已有 Android 配置仍可用原会话令牌同步，不需要重新登录。

## 给旧 owner 设置登录密码

旧 owner 默认只有 `.env` 中的 `SYNC_TOKEN`，没有密码。设置一次密码后，就能在新设备上使用 `owner + 密码` 登录。以下命令需要 `jq` 来安全构造 JSON：

```bash
cd /opt/ideavault
sudo apt install -y jq
set -a; . ./.env; set +a
read -rsp '为 owner 设置新密码（至少 10 个字符）: ' OWNER_PASSWORD; echo
jq -n --arg password "$OWNER_PASSWORD" '{password:$password}' | \
  curl -sS -X POST \
    -H "Authorization: Bearer $SYNC_TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary @- \
    https://你的实际域名/v1/auth/password
unset OWNER_PASSWORD ADMIN_TOKEN SYNC_TOKEN DATA_KEY
```

预期返回：

```json
{"status":"password-set"}
```

这不会删除旧笔记，也不会立即使旧设备退出登录。

## 验证多人同步和隔离

1. 设备 A 注册 `alice`，新建测试笔记，等待账户详情出现绿色勾。
2. 设备 B 使用 `alice` 和同一密码登录，应看到测试笔记。
3. 设备 C 注册 `bob`，不应看到 `alice` 的笔记。
4. 修改、删除笔记后，其他同账户设备应在启动或下一次同步时更新。

管理员可查看账户列表，但看不到密码。`noteCount` 只统计当前有效笔记，不包含为防止旧设备恢复删除内容而保留的墓碑：

```bash
cd /opt/ideavault
set -a; . ./.env; set +a
curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://你的实际域名/v1/admin/users
unset ADMIN_TOKEN SYNC_TOKEN DATA_KEY
```

## 维护与备份

恢复所有账户必须同时拥有 `.env` 和 Docker 数据卷备份。

```bash
# 状态与日志
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
docker compose -f docker-compose.yml -f docker-compose.nginx.yml logs -f api

# 更新
git pull
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build api

# 健康检查
curl http://127.0.0.1:18080/v1/health
curl https://你的实际域名/v1/health
```

绿色勾表示该 App 最近一次完成服务器连接、会话鉴权和笔记合并；失败时显示红色空心圆和错误信息。