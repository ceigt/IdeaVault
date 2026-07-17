# 灵感笔记（IdeaVault）

一个面向 Android 12 及以上版本的隐私记事本，适合记录灵感、临时信息和需要额外保护的账号资料。应用名称跟随系统语言：中文显示“灵感笔记”，英文显示“IdeaVault”。

## 功能

- 新建、编辑、搜索、置顶和删除笔记
- 敏感笔记在列表中隐藏正文
- 启动及从后台返回时使用生物识别或设备锁屏凭据解锁
- 本地笔记使用 Android Keystore 中的 AES-256-GCM 密钥加密保存
- 禁止系统备份、截图和最近任务预览
- 自托管多用户自动同步；每个账户的令牌、密钥和笔记完全隔离
- 新增、修改、置顶或删除后自动同步，应用启动时自动拉取
- 右上角显示用户名；账户详情中绿色勾表示服务器正常且最近一次同步完成

> 提醒：它能降低笔记明文泄露风险，但不是经过审计的专业密码管理器。长期保存重要密码，仍建议使用成熟的密码管理器。

## 构建

使用 Android Studio 打开项目，安装 Android SDK 35 和 JDK 17，然后运行 `app` 配置。最低系统版本为 Android 12（API 31），目标 SDK 为 35。

```text
./gradlew assembleDebug
```

GitHub Actions 会测试服务端、验证 Compose 配置、构建已签名 APK，并在推送 `v*` 标签时创建 GitHub Release。

## 自托管同步

2.0.0 支持多人共用同一台 VPS。管理员通过独立的 `ADMIN_TOKEN` 创建用户，每位用户获得自己的访问令牌；Android 应用仍只需填写 HTTPS 服务器地址和个人令牌。旧版服务器中的账户和笔记会自动迁移为 `OWNER_USERNAME`（默认 `owner`）。

服务端数据卷只保存 AES-GCM 密文和账户元数据。每个用户使用独立的数据密钥；拥有 VPS、数据卷或管理员权限的人理论上仍可取得密钥，因此这不是零知识服务。

完整部署、Nginx 反代、升级和创建朋友账户步骤见 [`server/README.md`](server/README.md)。请离线备份 `.env` 和 Docker 数据卷。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 发布。