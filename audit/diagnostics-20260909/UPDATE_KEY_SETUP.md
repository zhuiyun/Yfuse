# 可选：生成或导出在线更新公钥

按用户后续要求，在线更新已不再强制配置公钥。保持 `yfuse.updateManifestPublicKey=` 为空即可，正式版和调试版均可检查及下载更新；仍校验更新地址、下载大小、SHA-256、APK 包名、实际版本和签名证书。

只有想额外验证更新清单签名时，才需要以下步骤。本项目支持 **Ed25519 公钥的 X.509/SPKI DER Base64**，用于验证 `update-v2.json`。它与 APK 签名证书是两套密钥。配置了公钥的版本仍要求清单由匹配私钥签名。

## 已有更新私钥

从原来发布更新时使用的 `update-manifest.pem` 导出即可。以下命令在安装了 OpenSSL 的 PowerShell 中执行；把路径改成实际私钥位置：

```powershell
openssl pkey -in 'D:\secure\update-manifest.pem' -pubout -outform DER -out 'update-manifest-public.der'
if ($LASTEXITCODE -ne 0) { throw '公钥导出失败' }
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PWD 'update-manifest-public.der')))
```

最后输出的一行就是可选配置所需的公钥。不要发送 `.pem` 私钥文件或它的内容。

## 从未配置过更新签名

先在自己保管的私密目录创建密钥对，再执行上面的公钥导出命令：

```powershell
$updateKeyPath = 'D:\secure\update-manifest.pem'
if (Test-Path -LiteralPath $updateKeyPath) { throw '私钥已存在，请直接导出公钥' }
openssl genpkey -algorithm ed25519 -out $updateKeyPath
if ($LASTEXITCODE -ne 0) { throw '密钥生成失败；请确认目录存在并已安装 OpenSSL' }
```

公钥配置到 `gradle.properties` 的 `yfuse.updateManifestPublicKey=`，或者 CI 仓库变量 `YFUSE_UPDATE_MANIFEST_PUBLIC_KEY`。私钥由发布者保管，配置到仓库 Secret `UPDATE_MANIFEST_SIGNING_KEY`，以后更新清单由同一私钥签名。

如果原私钥遗失，新生成的公钥不能验证旧清单。选择继续使用清单签名时，需要手动安装含新公钥、使用原 APK 证书签名的应用，并使用新私钥发布后续更新清单。已经安装的旧版“缺公钥即阻止更新”客户端，需要手动覆盖安装本次修复包一次；本次版本本身已不受缺公钥限制。

本次没有生成生产私钥或更换更新服务密钥；公钥为空时跳过清单签名验证，APK 自身签名校验仍保留。
