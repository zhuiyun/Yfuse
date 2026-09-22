from pathlib import Path

out = Path(__file__).resolve().parent
previous = out.parent / '20260910-motion-optimization'
for name in ('build-release.ps1', 'verify-release.ps1', 'source-manifest.py', 'verify-package.py'):
    source = (previous / name).read_text(encoding='utf-8-sig')
    source = source.replace('1.0.51', '1.0.52').replace('213', '214')
    if name == 'source-manifest.py':
        source = source.replace("'watchTogetherProtocol/src', 'gradle'", "'watchTogetherProtocol/src', 'gradle', 'composeApp/libs', 'mdkAndroid/libs'")
        source = source.replace("'version.properties', 'release-notes.txt'", "'version.properties', 'release-notes.txt', 'gradle.properties'")
    if name == 'verify-package.py':
        source = source.replace('motion-optimization-20260910/RESULTS.md', 'render-isolation-20260910/RESULTS.md')
        start = source.index("    '包含加载光球/")
        end = source.index("    '构建使用版本参数覆盖", start)
        source = source[:start] + '''    '包含播放控制栏电量与充电标记、主题消费点颜色过渡、播放运行区实时状态隔离、ASS 绘制隔离，'
    '以及上一安装包之后的动效收尾和下一集预加载／片尾适配改动。\\n\\n'
    '打包前本地手机与 TV 单元测试合计 2548 项通过。'
    '验证详情见 [优化记录](../../render-isolation-20260910/RESULTS.md)。'
    '本次另通过 release 构建与设计系统检查；没有连接手机、设备测试或上传发布。\\n\\n'
''' + source[end:]
    (out / name).write_text(source, encoding='utf-8', newline='\n')
print('Prepared release 1.0.52 (214); existing signing configuration will be used.')
