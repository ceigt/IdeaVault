# IdeaVault 同步服务

服务端只保存 Android 客户端产生的 AES-GCM 密文、随机 IV、笔记 ID 和更新时间。加密口令不会发送到服务器。

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
- 加密口令：自行设置，服务端不知道此口令；所有设备必须输入完全相同的口令

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

请同时离线保存 `.env`。丢失 `KDF_SALT` 或客户端加密口令后，服务器上的密文无法恢复。