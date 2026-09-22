from pathlib import Path
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt');s=p.read_text(encoding='utf-8');s=s.replace('''                        GroupLabel("主字幕")
                        if (subtitleControls.secondarySupported) {''','''                        if (subtitleControls.secondarySupported) {''',1)
s=s.replace('''                            Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            ) {
                                Text("主字幕预览", color = Color.White, fontSize = (18 * subtitleControls.scale).sp)
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    "Secondary subtitle",
                                    color = Color.White,
                                    fontSize = (18 * subtitleControls.secondaryScale).sp,
                                )
                            }
                            GroupLabel("主字幕")
                        }
                        OptionRow(''','''                            GroupLabel("位置与字号预览")
                            androidx.compose.foundation.layout.BoxWithConstraints(
                                Modifier.fillMaxWidth().height(120.dp).background(Color.Black),
                            ) {
                                Column(
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                        .padding(bottom = maxHeight * (1f - subtitleControls.position.coerceIn(0.60f, 0.96f))),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                ) {
                                    Text("主字幕预览", color = Color.White, fontSize = (18 * subtitleControls.scale).sp)
                                    Spacer(Modifier.size(4.dp))
                                    Text("Secondary subtitle", color = Color.White,
                                        fontSize = (18 * subtitleControls.secondaryScale).sp)
                                }
                            }
                        }
                        GroupLabel("主字幕")
                        OptionRow(''',1)
p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackBookmarkPanel.kt');s=p.read_text(encoding='utf-8').replace('import androidx.compose.foundation.layout.fillMaxWidth','import androidx.compose.foundation.layout.heightIn\nimport androidx.compose.foundation.layout.fillMaxWidth').replace('import androidx.compose.ui.graphics.Color','import androidx.compose.ui.graphics.SolidColor\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.graphics.Color');s=s.replace('textStyle = TextStyle(color = Color.White),','textStyle = TextStyle(color = Color.White),\n        cursorBrush = SolidColor(Color.White),');s=s.replace('modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),','modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp)\n            .semantics { contentDescription = "书签名称" },',1);s=s.replace('modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),','modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp)\n            .semantics { contentDescription = "书签备注" },',1);p.write_text(s,encoding='utf-8')
