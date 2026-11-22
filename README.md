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

## 编译能力

### 模式一：标准 Gradle 工程（有 `gradlew`）

| 类型 | 支持 |
|---|---|
| Java / Kotlin | ✅ Gradle 全流程 |
| XML 资源 | ✅ AAPT2 → `resources.arsc` + `R.java` |
| C / C++ | ✅ 工程自带 CMake/ndkBuild 时可用（runner 自带 NDK） |
| DEX | ✅ D8/R8 自动生成 |
| 产物 | debug / release APK；未签名会自动补 debug 签名 |

### 模式二：裸源码工程（没有 `gradlew`）

自动找 `AndroidManifest.xml`、`res/`、`java/`、`cpp|jni/`，直接用 runner 自带 build-tools + NDK 编：

- XML → `aapt2` → `resources.arsc` + `R.java`
- Java → `javac` → `d8` → `classes.dex`
- C/C++ → `clang` → `lib/arm64-v8a/lib*.so`（C++ 自动带 `libc++_shared.so`）
- 最后 `zipalign` + `apksigner` 出可安装 APK

实测产物包含：`AndroidManifest.xml`、`resources.arsc`、`classes.dex`、`lib/arm64-v8a/libnative.so`，v2 签名有效。

局限：裸源码模式固定 `arm64-v8a` / minSdk 21，不支持第三方 aar/jar 依赖；需要依赖请用 Gradle 模式。

## 功能

- **一键构建**：ZIP 工程或目录工程，点一次走完全程
- **上传加速**：按 24MB 分片、多片并发上传，实测比单请求快数倍
- **下载加速**：内置镜像并发测速（ghfast.top / gh-proxy.com / ghproxy.net），自动选最快；支持 Range 的通道走多线程分段下载，失败自动切换
- **后台不中断**：前台服务 + 通知进度，退出界面也继续
- **Token 安全**：EncryptedSharedPreferences 加密保存，不写日志
- **历史记录**：最近 30 次构建，点击直接安装
- **镜像可配**：设置里可增删镜像前缀，留空则直连

## 构建客户端（只做一次）

1. 仓库 Settings → Secrets and variables → Actions → **New repository secret**
   - Name：`CLOUD_TOKEN`
   - Value：只授权该仓库的细粒度 Token（Contents / Actions / Workflows 读写）
   - Name：`SIGNING_KEY_B64`
   - Value：固定签名 keystore 的 Base64（保证后续版本能覆盖安装；密码 `cloudbuild`，别名 `cloudbuild`）
2. Actions → **build-app** → Run workflow
3. 构建完成后在 Release 里下载 `cloudbuild.apk`，直接安装

App 内不提供仓库/Token 输入，安装后选 ZIP 即可编译。

## 安全说明

- Token 不写入仓库源码，只在构建时由 Secret 注入。
- APK 内的 Token 仍然可以被反编译提取（任何客户端都无法避免），所以这个 Token 必须：
  - 只授权 `Baicai` 这一个仓库
  - 设置过期时间
  - 泄露、换人、或不再使用时立即吊销重建

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