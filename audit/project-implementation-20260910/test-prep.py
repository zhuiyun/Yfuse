from pathlib import Path
p=Path('audit/project-implementation-20260910/test.ps1');s=p.read_text(encoding='utf-8').replace("':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test',", "':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test',")
p.write_text(s,encoding='utf-8')
