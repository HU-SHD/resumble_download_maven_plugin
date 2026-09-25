# Resumble Download Maven Plugin

[![GitHub Repo](https://img.shields.io/badge/GitHub-HU--SHD-blue.svg)](https://github.com/HU-SHD/resumble_download_maven_plugin)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-23-orange.svg)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-red.svg)](https://maven.apache.org/)

> **Resumble Download Maven Plugin** 是一款专为 Maven 构建过程设计的断点续传下载插件。它能够在构建生命周期中可靠地下载大文件（如测试数据集、机器学习模型、资源包等），并在网络中断后自动从上次断点处继续下载，避免重复传输，显著提升构建效率与稳定性。

---

## 目录

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [参数说明](#参数说明)
- [使用示例](#使用示例)
- [源码构建](#源码构建)
- [项目结构](#项目结构)
- [核心实现原理](#核心实现原理)
- [许可证](#许可证)

---

## 项目简介

`Resumble Download Maven Plugin` 是一个基于 HTTP `Range` 请求和 `RandomAccessFile` 的 Maven 插件，用于在 Maven 构建过程中下载大文件。它支持断点续传、SHA‑256 完整性校验、文件锁并发控制以及服务器不支持断点续传时的优雅降级。插件可绑定到 `initialize` 阶段，确保在编译前完成资源下载。

---

## 核心特性

- **断点续传**：利用 HTTP `Range` 请求和 `.part` 临时文件记录下载进度，网络恢复后从断点继续下载。
- **SHA‑256 校验**：支持流式计算大文件哈希值，避免内存溢出，确保文件完整性。
- **文件锁并发控制**：使用 `FileLock` 防止多个 Maven 进程同时下载同一文件，避免资源竞争。
- **服务器兼容性**：自动检测服务器是否支持 `206 Partial Content`，若不支持则降级为全量下载（可通过 `forceResume` 强制要求断点续传）。
- **原子性重命名**：下载完成后通过 `Files.move` 原子性重命名 `.part` 文件，避免暴露不完整文件。
- **可配置超时**：支持自定义连接超时与读取超时，适应不同网络环境。
- **线程安全**：Mojo 标注为 `threadSafe = true`，支持 Maven 并行构建。

---

## 快速开始

> **⚠️ 重要提示**：本插件托管在 **GitHub Packages** 上。即使本仓库是公开的，GitHub Packages 仍然要求认证访问。因此，使用者**必须**生成一个 GitHub Personal Access Token 才能拉取依赖。

### 1. 生成 GitHub Personal Access Token

1. 登录 GitHub，点击右上角头像 → **Settings**。
2. 在左侧边栏底部点击 **Developer settings**。
3. 点击 **Personal access tokens** → **Tokens (classic)**。
4. 点击 **Generate new token (classic)**。
5. 填写 Note（如 `maven-read-packages`），勾选权限：**`read:packages`**。
6. 点击生成，并**立即复制** Token（格式类似 `ghp_xxxxxxxxxxxx`）。

### 2. 配置 `~/.m2/settings.xml`

在你的本地 Maven 配置文件（Windows 下路径通常为 `C:\Users\你的用户名\.m2\settings.xml`）中，添加以下内容：

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>你的GitHub用户名</username>
      <password>你的GitHub Token</password>
    </server>
  </servers>
</settings>
```

### 3. 在项目中引入仓库和插件

在你的项目 `pom.xml` 中添加 GitHub Packages 仓库：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/HU-SHD/resumble_download_maven_plugin</url>
    </repository>
</repositories>
```

然后在 `<build><plugins>` 中引入插件（注意坐标已变更为 `com.github.HU-SHD`）：

```xml
<plugin>
    <groupId>com.github.HU-SHD</groupId>
    <artifactId>resumble_download_maven_plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <id>download-large-file</id>
            <phase>initialize</phase>
            <goals>
                <goal>download</goal>
            </goals>
            <configuration>
                <url>https://example.com/datasets/large-dataset.zip</url>
                <outputDirectory>${project.build.directory}/downloads</outputDirectory>
                <fileName>dataset.zip</fileName>
                <skipIfExists>true</skipIfExists>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 4. 触发下载

在你的项目根目录执行 `mvn initialize`，Maven 会自动从 GitHub Packages 下载插件并执行断点续传下载。

---

## 参数说明

所有参数均通过 `@Parameter` 注解声明，可在 `<configuration>` 中配置，也可通过命令行 `-Dresumble.xxx=...` 传递。

| 参数名 | 属性 (property) | 描述 | 默认值 | 必填 |
|--------|----------------|------|--------|------|
| `url` | `resumble.url` | 要下载的文件 URL（支持 HTTP/HTTPS） | — | ✅ 是 |
| `outputDirectory` | `resumble.outputDirectory` | 文件保存目录，基于项目构建目录 | `${project.build.directory}/download` | 否 |
| `fileName` | `resumble.fileName` | 保存的文件名，若不指定则从 URL 自动解析 | — | 否 |
| `expectedSha256` | `resumble.sha256` | 期望的 SHA‑256 校验和，下载完成后自动校验 | — | 否 |
| `skipIfExists` | `resumble.skipIfExists` | 若目标文件已存在且校验通过，是否跳过下载 | `true` | 否 |
| `connectTimeout` | `resumble.connectTimeout` | HTTP 连接超时时间（毫秒） | `30000` | 否 |
| `readTimeout` | `resumble.readTimeout` | HTTP 读取超时时间（毫秒） | `60000` | 否 |
| `forceResume` | `resumble.forceResume` | 是否强制要求服务器支持断点续传（若返回 200 则报错） | `false` | 否 |

> **参数详细行为**
> - `skipIfExists=true` 时，如果文件已存在且未指定 `expectedSha256`，则直接跳过；若指定了 `expectedSha256` 且校验失败，会删除旧文件并重新下载。
> - `forceResume=true` 时，若服务器不支持 Range 请求（返回 200 而非 206），插件会抛出异常，避免全量下载。
> - `connectTimeout` 与 `readTimeout` 可根据网络环境调整。

---

## 使用示例

以下是一个完整的 `<plugin>` 配置示例，涵盖了常用参数：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.github.HU-SHD</groupId>
            <artifactId>resumble_download_maven_plugin</artifactId>
            <version>1.0.0</version>
            <executions>
                <execution>
                    <id>download-model</id>
                    <phase>initialize</phase>
                    <goals>
                        <goal>download</goal>
                    </goals>
                    <configuration>
                        <!-- 必填：远程文件 URL -->
                        <url>https://example.com/models/bert-base-chinese.zip</url>
                        <!-- 保存目录（默认 ${project.build.directory}/download） -->
                        <outputDirectory>${project.build.directory}/models</outputDirectory>
                        <!-- 自定义文件名（不指定则从 URL 解析） -->
                        <fileName>bert-base-chinese.zip</fileName>
                        <!-- SHA-256 校验和（可选，用于验证文件完整性） -->
                        <expectedSha256>a1b2c3d4e5f6...</expectedSha256>
                        <!-- 文件已存在且校验通过时跳过下载（默认 true） -->
                        <skipIfExists>true</skipIfExists>
                        <!-- 连接超时 30 秒，读取超时 60 秒 -->
                        <connectTimeout>30000</connectTimeout>
                        <readTimeout>60000</readTimeout>
                        <!-- 若服务器不支持断点续传则报错（默认 false，自动降级为全量下载） -->
                        <forceResume>false</forceResume>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 命令行直接调用

```bash
mvn com.github.HU-SHD:resumble_download_maven_plugin:1.0.0:download \
    -Dresumble.url=https://example.com/large-file.zip \
    -Dresumble.outputDirectory=./downloads \
    -Dresumble.fileName=large-file.zip
```

---

## 源码构建

### 环境要求

- JDK 23（或更高）
- Maven 3.9+
- Git

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/HU-SHD/resumble_download_maven_plugin.git
cd resumble_download_maven_plugin

# 2. 执行完整构建与验证（包含单元测试和集成测试）
mvn clean verify
```

`mvn clean verify` 会自动执行以下阶段：

1. **编译**：编译主代码与测试代码。
2. **单元测试**：运行 `ResumableDownloaderTest` 和 `ResumableDownloadMojoTest`。
3. **打包**：生成插件 JAR 和 `plugin.xml` 描述符。
4. **集成测试**：通过 `maven-invoker-plugin` 在独立环境中调用插件下载真实文件，并运行 `verify.groovy` 校验结果。
5. **安装**：将插件安装到本地仓库（仅 `install` 阶段）。

构建成功后，终端会显示 `BUILD SUCCESS`，插件 JAR 位于 `target/resumble_download_maven_plugin-1.0.0.jar`。

---

## 项目结构

```
resumble_download_maven_plugin/
├── pom.xml
├── src/
│   ├── main/java/org/stone/maven/plugin/
│   │   ├── ResumableDownloadMojo.java      # 插件 Mojo 入口
│   │   └── ResumableDownloader.java        # 断点续传下载引擎
│   ├── test/java/org/stone/maven/plugin/
│   │   ├── ResumableDownloadMojoTest.java  # Mojo 配置测试
│   │   └── ResumableDownloaderTest.java    # 下载逻辑单元测试
│   └── it/basic-download/
│       ├── pom.xml                         # 集成测试项目 POM
│       └── verify.groovy                   # 集成测试验证脚本
└── README.md
```

---

## 核心实现原理

### 1. 断点续传机制

- 下载前检查 `.part` 临时文件是否存在及其大小，作为已下载字节数。
- 若已下载字节数大于 0，则在 HTTP 请求头中设置 `Range: bytes=<已下载字节数>-`。
- 服务器返回 `206 Partial Content` 时，使用 `RandomAccessFile.seek()` 定位到断点位置继续写入。
- 若服务器返回 `200 OK`，表示不支持 Range 请求，则根据 `forceResume` 决定是降级为全量下载还是抛出异常。

### 2. 文件锁并发控制

- 使用 `RandomAccessFile` 打开 `.part` 文件并获取 `FileLock`。
- 若锁被占用，则进入重试循环（默认最多等待 30 秒），避免多个 Maven 进程同时下载同一文件。
- 下载完成后释放锁，并通过 `Files.move` 原子性重命名为目标文件。

### 3. SHA‑256 完整性校验

- 使用 `DigestInputStream` 流式读取文件，避免大文件导致内存溢出。
- 将计算出的十六进制哈希值与 `expectedSha256` 比较（忽略大小写）。
- 校验失败时抛出 `MojoExecutionException`，并删除损坏文件。

### 4. 服务器兼容性处理

- 自动识别 `206` 与 `200` 响应码。
- 当服务器不支持断点续传时，记录警告日志并全量下载（除非 `forceResume=true`）。

---

## 许可证

本项目采用 [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可协议。

---

**© 2026 HU-SHD. 保留所有权利。**