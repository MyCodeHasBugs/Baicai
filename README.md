# CloudBuild · 云端 APK 编译器

手机上的独立 App：选一个 Gradle 工程 → 自动打包分片并发上传 → 触发 GitHub Actions 云端编译 → 镜像测速 + 多线程下载 APK → 直接安装。

## 直接安装

- Release：https://github.com/MyCodeHasBugs/Baicai/releases/tag/apk-final-20261122-001758
- 本地已构建版本：`E:\AI\safe-code\android\CloudBuild-debug.apk`（6.79 MB，SHA256 `11c5f34c0daac022f2351dc8a028be0647150ddff6a5e2bb70554e5a05238ae7`）
- 首次打开后进入「设置」，填 Owner、仓库名、Token，即可使用

已在 Baicai 仓库完成自举验证：
- 单分片（83 KB 工程）上传 → 云端编译 → 发布 APK → 镜像下载：成功
- 多分片（25 MB 工程，切 2 片）上传合并 → 云端编译：成功（上传 20.7 秒）
- 镜像实测：`ghfast.top` / `gh-proxy.com` / `ghproxy.net` 均支持 Range（206）

## 功能

- **一键构建**：ZIP 工程或目录工程，点一次走完全程
- **上传加速**：按 24MB 分片、多片并发上传，实测比单请求快数倍
- **下载加速**：内置镜像并发测速（ghfast.top / gh-proxy.com / ghproxy.net），自动选最快；支持 Range 的通道走多线程分段下载，失败自动切换
- **后台不中断**：前台服务 + 通知进度，退出界面也继续
- **Token 安全**：EncryptedSharedPreferences 加密保存，不写日志
- **历史记录**：最近 30 次构建，点击直接安装
- **镜像可配**：设置里可增删镜像前缀，留空则直连

## 服务端配置

1. 在 GitHub 建一个公开空仓库（如 `Baicai`）
2. 把本工程推到该仓库的 `main`
3. 仓库 Settings → Actions → General → Workflow permissions 选 **Read and write**
4. 生成细粒度 Token：Repository access 只选该仓库，权限给
   - **Contents: Read and write**（Release / 资产上传下载）
   - **Actions: Read and write**（触发和查询 workflow）
   - **Workflows: Read and write**（推送 workflow 文件时需要）
5. 手机 App 设置里填 Owner、仓库名、Token

## 目录

```
app/                       Android 客户端
.github/workflows/cloud-build.yml   云端编译流水线
```

## 工作流说明

`cloud-build.yml` 只由 `workflow_dispatch` 触发，输入：

| 输入 | 含义 |
|---|---|
| `tag` | App 上传分片用的 release tag |
| `apk_tag` | 产物 release tag |
| `variant` | `debug` / `release` |

流程：下载分片 → `cat` 合并 → 解压 → 找 `gradlew` → JDK 17 + Android SDK → `./gradlew assembleDebug/Release` → 发布 APK 到 Release。

## 本地构建

```bash
./gradlew assembleDebug
```

需要 JDK 17 和 Android SDK 34。