from edit import read,write,replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
p=C+'core/designsystem/Toast.kt';s=read(p)
s=s.replace('import androidx.compose.animation.core.animateFloatAsState','import androidx.compose.animation.core.MutableTransitionState\nimport androidx.compose.animation.core.animateFloatAsState')
s=s.replace('var visible by mutableStateOf(false)','var visible by mutableStateOf(true)',1)
s=s.replace('''        entry.visible = false
        return latest === entry && entry in entries''','''        val wasVisible = entry.visible
        entry.visible = false
        return wasVisible && latest === entry && entry in entries''')
s=s.replace('                LaunchedEffect(entry) { entry.visible = true }\n','')
s=s.replace('    var appeared by remember { mutableStateOf(false) }','    val visibility = remember(entry) { MutableTransitionState(false) }\n    visibility.targetState = entry.visible')
s=s.replace('LaunchedEffect(entry.visible, dragging, accessibility)', 'LaunchedEffect(entry.visible, dragging, accessibility, duration)')
s=s.replace('            appeared = true\n','').replace('} else if (appeared) {','} else {')
s=s.replace('visible = entry.visible,\n        enter =', 'visibleState = visibility,\n        enter =')
write(p,s)

p='composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/ToastQueueTest.kt'
s=read(p).replace('assertTrue(queue.dismiss(queue.entries.last()))\n    }','assertTrue(queue.dismiss(queue.entries.last()))\n        assertFalse(queue.dismiss(queue.entries.last()))\n    }',1);write(p,s)

p=C+'feature/library/LibraryHomeScreen.kt';s=read(p)
start=s.index('        Text(\n            text = if (error == null) "缓存内容"');end=s.index('        if (error != null && !loading)',start)
s=s[:start]+'''        MotionSwap((error == null) to detail, Modifier.fillMaxWidth()) { (cached, message) ->
            Column(Modifier.fillMaxWidth()) {
                Text(if (cached) "缓存内容" else "离线内容", style = AppTypography.body.strong, color = palette.text)
                Text(message, style = AppTypography.caption.regular, color = palette.sub, modifier = Modifier.padding(top = 3.dp))
            }
        }
'''+s[end:]
start=s.index('    Text(\n        text = "电影 $movieCount');end=s.index('\n}\n',start)
chunk=s[start:end].replace('$movieCount','$movies').replace('$seriesCount','$series')
s=s[:start]+'    MotionSwap(movieCount to seriesCount, Modifier.fillMaxWidth()) { (movies, series) ->\n'+chunk+'\n    }'+s[end:]
write(p,s)

# animateBounds owns positions and dimensions; lazy parent data owns only add/remove alpha.
p=C+'feature/servers/ServersTabScreen.kt';s=read(p)
start=s.index('                    items(otherVisibleServers,');end=s.index('        // A new request',start)
chunk=s[start:end]
chunk=chunk.replace('''                        ServerCard(''','''                        val moving = !LocalAccessibilityOptions.current.reduceMotion && routeVisible
                        val cardMotion = Modifier.animateItem(
                            fadeInSpec = if (moving) tween(Motion.QUICK) else null,
                            placementSpec = null,
                            fadeOutSpec = if (moving) tween(Motion.STANDARD) else null,
                        ).then(if (moving) Modifier.animateBounds(cardLookahead, boundsTransform = { _, _ -> Motion.settle() }) else Modifier)
                        ServerCard(''',1)
modstart=chunk.index('                            modifier =');modend=chunk.index('\n                        )',modstart)
chunk=chunk[:modstart]+'                            modifier = cardMotion,'+chunk[modend:]
s=s[:start]+chunk+s[end:];write(p,s)
