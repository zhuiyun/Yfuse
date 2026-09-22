from pathlib import Path
p=Path('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvSettingsScreen.kt')
s=p.read_text(encoding='utf-8').replace('import com.yfuse.core.account.canUseMediaDiscovery\n','')
a=s.index('        TvSettingsPage.MediaDiscovery ->')
b=s.index('\n    }\n}',a)
s=s[:a]+s[b:]
s=s.replace('searchTvSettings(query, includeMediaDiscovery = account.canUseMediaDiscovery())','searchTvSettings(query)')
a=s.index('        if (account.canUseMediaDiscovery()) {')
b=s.index('\n        if (state.servers.size > 1)',a)
s=s[:a]+s[b:]
s=s.replace('        TvSettingsPage.MediaDiscovery to "资源 转存 站点 123 tgto",\n','').replace('    includeMediaDiscovery: Boolean,\n','').replace('        .filter { includeMediaDiscovery || it != TvSettingsPage.MediaDiscovery }\n','')
p.write_text(s,encoding='utf-8')
p=Path('tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvSettingsScaffold.kt')
s=p.read_text(encoding='utf-8').replace('    MediaDiscovery("影视发现", "资源站点与转存目录"),\n','')
p.write_text(s,encoding='utf-8')
p=Path('tvApp/src/androidUnitTest/kotlin/com/yfuse/tv/ui/TvSettingsSearchTest.kt')
s=p.read_text(encoding='utf-8').replace(', includeMediaDiscovery = true','').replace(', includeMediaDiscovery = false','')
a=s.index('    @Test\n    fun `media discovery')
b=s.index('\n    @Test',a+8)
s=s[:a]+'''    @Test
    fun `unimplemented resource integration is not searchable`() {
        assertTrue(searchTvSettings("转存").isEmpty())
        assertTrue(searchTvSettings("tgto").isEmpty())
    }
'''+s[b:]
p.write_text(s,encoding='utf-8')
