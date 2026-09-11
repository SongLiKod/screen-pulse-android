# GitHub Actions Release 配置说明

## 配置步骤

### 1. 生成签名密钥

```bash
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias screen-pulse
```

按提示输入密码和相关信息。

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
base64 -i release-key.jks | tr -d '\n'
```

**Windows PowerShell:**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))
```

将输出内容复制到 `KEYSTORE_BASE64` Secret 中。

### 4. 使用 Release 工作流

1. 进入 GitHub 仓库 → **Actions**
2. 选择 **Release Build** 工作流
3. 点击 **Run workflow**
4. 填写参数：
   - **tag**: 版本号（如 `v1.1.0`）
   - **release_name**: 发布名称（可选）
   - **prerelease**: 是否标记为预发布

### 5. 本地构建配置（可选）

如果需要在本地构建 release 版本，在项目根目录创建 `keystore.properties` 文件：

```properties
storeFile=release-key.jks
storePassword=your_keystore_password
keyAlias=screen-pulse
keyPassword=your_key_password
```

将 `release-key.jks` 文件放在 `app/` 目录下。

## 安全建议

- **不要**将 `keystore.properties` 和 `.jks` 文件提交到 Git
- 确保 `.gitignore` 包含这些文件：
  ```
  keystore.properties
  *.jks
  ```
- 定期备份 keystore 文件，丢失后无法更新已发布的应用
