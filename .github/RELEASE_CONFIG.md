# GitHub Actions Release 配置说明

## 配置步骤

### 1. 生成签名密钥

> ⚠️ 必须指定 `-storetype JKS`。Java 9+ 的 `keytool` 默认生成 PKCS12 格式，
> 会导致 CI 签名报错：`Tag number over 30 is not supported`。
> 文件命名为 `screen-keystore.jks`（与 workflow 和 keystore.properties 中的 `storeFile` 保持一致）。

```bash
keytool -genkey -v -keystore screen-keystore.jks -storetype JKS -keyalg RSA -keysize 2048 -validity 10000 -alias screen-pulse
```

按提示输入密码和相关信息。请记住密钥库密码、key 别名、key 密码三个值。

> 生成 PKCS12 格式同样可用的备用命令（如用 JKS 失败时）：
> `keytool -genkey -v -keystore screen-keystore.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias screen-pulse`

### 2. 配置 GitHub Secrets

在 GitHub 仓库中，进入 **Settings** → **Secrets and variables** → **Actions**，添加以下 Secrets：

| Secret 名称 | 说明 |
|------------|------|
| `KEYSTORE_BASE64` | Keystore 文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | Keystore 密码 |
| `KEY_ALIAS` | Key 别名 |
| `KEY_PASSWORD` | Key 密码 |

### 3. 生成 Base64 编码

**Linux/macOS:**
```bash
base64 -w0 -i screen-keystore.jks
```

**Windows PowerShell:**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("screen-keystore.jks"))
```

将输出内容（单行、无换行）复制到 `KEYSTORE_BASE64` Secret 中。

### 4. 使用 Release 工作流

1. 进入 GitHub 仓库 → **Actions**
2. 选择 **Release Build** 工作流
3. 点击 **Run workflow**
4. 填写参数：
   - **tag**: 版本号（如 `v1.1.0`），留空则只构建不发布
   - **release_name**: 发布名称（可选）
   - **prerelease**: 是否标记为预发布

### 5. 本地构建配置（可选）

如果需要在本地构建 release 版本，在项目根目录创建 `keystore.properties` 文件：

```properties
storeFile=screen-keystore.jks
storePassword=your_keystore_password
keyAlias=screen-pulse
keyPassword=your_key_password
```

将 `screen-keystore.jks` 文件放在 `app/` 目录下。

## 安全建议

- **不要**将 `keystore.properties` 和 `.jks` 文件提交到 Git
- 确保 `.gitignore` 包含这些文件：
  ```
  keystore.properties
  *.jks
  app/screen-keystore.jks
  ```
- 定期备份 keystore 文件，丢失后无法更新已发布的应用