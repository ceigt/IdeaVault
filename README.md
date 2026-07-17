# 灵感记事（IdeaVault）

一个面向 Android 12 及以上版本的离线记事本。适合记录灵感、临时信息，以及需要额外保护的账号资料。

## 功能

- 新建、编辑、搜索、置顶和删除笔记
- 敏感笔记在列表中隐藏正文
- 启动及从后台返回时使用生物识别或设备锁屏凭据解锁
- 所有笔记使用 Android Keystore 中的 AES-256-GCM 密钥加密后保存
- 禁止系统备份、禁止截图和最近任务预览
- 不申请网络权限，数据不会离开设备

## 构建

使用 Android Studio 打开项目，安装 Android SDK 35 和 JDK 17，然后运行 `app` 配置即可。

GitHub Actions 会在推送 `v*` 标签时构建已签名 APK、创建 Release，并把 APK 附加到 Release 下载项。也可以在 Actions 页面手动运行，只生成构建产物。

命令行构建：

```text
./gradlew assembleDebug
```

最低系统版本为 Android 12（API 31），目标 SDK 为 35。

> 提醒：它能降低笔记明文泄露的风险，但不是经过审计的专业密码管理器。长期保存重要密码，仍建议使用成熟的密码管理器。

## 自托管同步

1.2.0 起支持自动同步：新增、修改、置顶或删除后会自动上传，应用启动时也会拉取。VPS 数据卷只保存 AES-GCM 密文；加密使用 `.env` 中由服务器管理的 `DATA_KEY`。项目根目录包含 `docker-compose.yml`，使用 Go API + Caddy 自动 HTTPS。

完整部署步骤见 [`server/README.md`](server/README.md)。Android 应用只需填写 HTTPS 服务器地址和 `.env` 的访问令牌。

> 必须离线备份 `.env`。丢失或更改 `DATA_KEY` 后无法恢复服务器密文；拥有 `.env` 的管理员理论上可以解密数据。
## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 发布。修改或再分发时须遵守 GPL-3.0 的相关要求。