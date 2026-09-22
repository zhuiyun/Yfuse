from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT / 'audit/releases/20260910-runtime-optimization'
NEW = ROOT / 'audit/releases/20260910-motion-optimization'
NEW.mkdir(parents=True, exist_ok=True)
for name in ['build-release.ps1', 'source-manifest.py', 'verify-release.ps1', 'verify-package.py']:
    content = (OLD / name).read_text(encoding='utf-8-sig')
    content = content.replace('1.0.50', '1.0.51').replace('212', '213')
    content = content.replace('../../runtime-optimization-20260910/RESULTS.md', '../../motion-optimization-20260910/RESULTS.md')
    if name == 'verify-package.py':
        start = content.index("(OUT / 'README.md').write_text(")
        end = content.index('\nprint(json.dumps(', start)
        content = content[:start] + '''source_count = len(json.loads(text('source-hashes.json')))
(OUT / 'README.md').write_text(
    '# Yfuse 1.0.51 (213) 正式签名包\\n\\n'
    f'- 安装包：[{APK.name}]({APK.name})\\n'
    '- 包名：`com.yfuse`，ARM64 完整版，非 debuggable。\\n'
    f'- 大小：{len(data):,} 字节（{len(data) / 1_000_000:.2f} MB）。\\n'
    f'- SHA-256：`{report["sha256"]}`。\\n'
    '- 正式证书、APK v2 签名、16 KB ZIP 对齐与 ZIP CRC 校验通过。\\n'
    '- 20 个预期 ARM64 原生库齐全，8 个 YCore 库与当前 AAR 逐字节一致。\\n'
    f'- {source_count:,} 个源码与构建文件在打包前后保持一致。\\n\\n'
    '包含加载光球/详情顶栏绘制阶段读取、ASS 帧提交隔离、主题与强调色订阅缩小、'
    '剧集和进度标记缓存、固定轨道进度绘制、库首页滚动可见性优化。\\n\\n'
    '打包前完成 Android/TV、协议和服务端本地回归。'
    '验证详情见 [优化记录](../../motion-optimization-20260910/RESULTS.md)。'
    '本次另通过 release 构建与设计系统检查；没有连接手机、设备测试或上传发布。\\n\\n'
    '构建使用版本参数覆盖，没有修改 version.properties。'
    '证据：`build.log`、`signature.txt`、`badging.txt`、`alignment.txt`、'
    '`source-hashes.json`、`verification.json`。\\n', encoding='utf-8')
''' + content[end:]
    (NEW / name).write_text(content, encoding='utf-8', newline='\n')
script = (ROOT / 'audit/runtime-optimization-20260910/summarize-tests.py').read_text(encoding='utf-8')
(ROOT / 'audit/motion-optimization-20260910/summarize-tests.py').write_text(
    script.replace('audit/runtime-optimization-20260910/', 'audit/motion-optimization-20260910/'), encoding='utf-8')
print(NEW)
