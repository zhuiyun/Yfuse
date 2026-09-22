from edit import read,write
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
p=C+'feature/servers/ServersTabScreen.kt';s=read(p)
start=s.index('                        items(otherVisibleServers,');end=s.index('        // A new request',start)
chunk=s[start:end].replace('''                            ServerCard(''','''                            val moving = !LocalAccessibilityOptions.current.reduceMotion && routeVisible
                            val cardMotion = Modifier.animateItem(
                                fadeInSpec = if (moving) tween(Motion.QUICK) else null,
                                placementSpec = null,
                                fadeOutSpec = if (moving) tween(Motion.STANDARD) else null,
                            ).then(
                                if (moving) Modifier.animateBounds(cardLookahead, boundsTransform = { _, _ -> Motion.settle() })
                                else Modifier,
                            )
                            ServerCard(''',1)
modstart=chunk.index('                                modifier =');modend=chunk.index('\n                            )',modstart)
chunk=chunk[:modstart]+'                                modifier = cardMotion,'+chunk[modend:]
s=s[:start]+chunk+s[end:];write(p,s)

p=A+'feature/player/PlayerActivity.kt';s=read(p)
start=s.index('    override fun onUserLeaveHint()');end=s.index('    @RequiresApi',start)
s=s[:start]+'''    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (activeState.playing && !isFinishing && !stopRequested) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Auto-enter is already configured; remove controls before Android captures the transition.
                pictureInPicture.value = true
            } else {
                enterPlayerPictureInPicture()
            }
        }
    }

'''+s[end:]
start=s.index('    private fun enterPlayerPictureInPicture()');end=s.index('    private fun stopPlaybackAndFinish()',start)
chunk=s[start:end].replace('        enterPictureInPictureMode(','''        val previousVisibility = pictureInPicture.value
        pictureInPicture.value = true
        var entered = false
        try {
        entered = enterPictureInPictureMode(''',1)
pos=chunk.rfind('    }')
chunk=chunk[:pos]+'''        } finally {
            if (!entered) pictureInPicture.value = previousVisibility
        }
'''+chunk[pos:]
s=s[:start]+chunk+s[end:];write(p,s)
