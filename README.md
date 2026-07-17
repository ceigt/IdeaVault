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

1.1.0 起支持端到端加密同步。VPS 只保存密文，无法读取笔记标题或正文。项目根目录包含 `docker-compose.yml`，使用 Go API + Caddy 自动 HTTPS。

完整部署步骤见 [`server/README.md`](server/README.md)。Android 应用中点“设置”，填写 HTTPS 服务器地址、`.env` 的访问令牌，以及只保存在设备上的加密口令。

> 所有设备必须使用相同加密口令；丢失口令或 `KDF_SALT` 后无法恢复服务器密文。