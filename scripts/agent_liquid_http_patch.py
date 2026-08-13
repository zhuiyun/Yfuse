from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_in_tail(text: str, marker: str, old: str, new: str, label: str) -> str:
    idx = text.find(marker)
    if idx < 0:
        raise RuntimeError(f"{label}: marker not found: {marker}")
    head, tail = text[:idx], text[idx:]
    tail = replace_once(tail, old, new, label)
    return head + tail


# 1) Emby HTTP is allowed anywhere after explicit cleartext acknowledgement.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/core/network/ServerEndpointPolicy.kt"
text = read(rel)
text = replace_once(
    text,
    " * Emby credentials and bearer tokens may use cleartext only on an explicitly trusted LAN.\n"
    " * HTTPS is valid everywhere; HTTP to a public address is rejected even when the generic\n"
    " * Android network security config has to remain open for user-managed local servers.\n",
    " * Emby endpoints may use HTTP when the user explicitly acknowledges the cleartext risk.\n"
    " * HTTPS is valid without acknowledgement; HTTP is supported for both LAN and public\n"
    " * self-hosted servers because users may expose Emby on arbitrary addresses and ports.\n",
    "server endpoint policy comment",
)
text = replace_once(text, 'message = "请输入完整的 HTTPS 地址",', 'message = "请输入完整的 HTTP 或 HTTPS 地址",', "server endpoint invalid copy")
text = replace_once(
    text,
    "    if (!host.isLocalServiceHost()) {\n"
    "        return ServiceEndpointValidation(\n"
    "            normalizedEndpoint = normalized,\n"
    "            decision = EndpointTransportDecision.PublicCleartextRejected,\n"
    "            message = \"公网 Emby 服务器必须使用 HTTPS\",\n"
    "        )\n"
    "    }\n",
    "",
    "remove public Emby HTTP rejection",
)
text = replace_once(
    text,
    'message = "局域网 HTTP 会暴露账号与令牌，请确认风险后继续",',
    'message = "HTTP 未加密，会暴露账号与令牌，请确认风险后继续",',
    "generic Emby HTTP risk copy",
)
write(rel, text)

# 2) Update endpoint tests to reflect explicit-confirmation public HTTP support.
rel = "composeApp/src/commonTest/kotlin/com/yfuse/core/network/ServerEndpointPolicyTest.kt"
text = read(rel)
old_test = '''    @Test
    fun embyPublicCleartextIsRejectedEvenAfterRiskConfirmation() {
        val result =
            validateEmbyServerEndpoint(
                "http://media.example.com:8096",
                localCleartextConfirmed = true,
            )

        assertFalse(result.allowed)
        assertEquals(EndpointTransportDecision.PublicCleartextRejected, result.decision)
    }
'''
new_test = '''    @Test
    fun embyPublicCleartextRequiresExplicitConfirmation() {
        val endpoint = "http://media.example.com:8096"
        val pending = validateEmbyServerEndpoint(endpoint)

        assertFalse(pending.allowed)
        assertTrue(pending.requiresCleartextConfirmation)
        assertEquals(EndpointTransportDecision.LocalCleartextConfirmationRequired, pending.decision)

        val confirmed =
            validateEmbyServerEndpoint(
                endpoint,
                localCleartextConfirmed = true,
            )
        assertTrue(confirmed.allowed)
        assertEquals(EndpointTransportDecision.LocalCleartextConfirmed, confirmed.decision)
    }
'''
text = replace_once(text, old_test, new_test, "public HTTP test")
old_test = '''    @Test
    fun cleartextClassifierRejectsPublicBoundariesAndHostSpoofing() {
        listOf(
            "http://100.63.255.255:8096",
            "http://100.128.0.1:8096",
            "http://8.8.8.8:8096",
            "http://127.0.0.1.evil.example:8096",
            "http://2130706433:8096",
            "http://0x7f000001:8096",
        ).forEach { endpoint ->
            assertFalse(validateEmbyServerEndpoint(endpoint, true).allowed, endpoint)
        }
    }
'''
new_test = '''    @Test
    fun embyCleartextAllowsPublicAddressesAfterConfirmation() {
        listOf(
            "http://100.63.255.255:8096",
            "http://100.128.0.1:8096",
            "http://8.8.8.8:8096",
            "http://media.example.com:8096",
        ).forEach { endpoint ->
            assertTrue(validateEmbyServerEndpoint(endpoint).requiresCleartextConfirmation, endpoint)
            assertTrue(validateEmbyServerEndpoint(endpoint, true).allowed, endpoint)
        }
    }
'''
text = replace_once(text, old_test, new_test, "public address boundary test")
write(rel, text)

# 3) Add-server dialog: all its interactive glass controls use liquid material and HTTP copy is generic.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt"
text = read(rel)
text = replace_once(
    text,
    "import com.yfuse.core.designsystem.flatGlass as glass",
    "import com.yfuse.core.designsystem.liquidGlass as glass",
    "add server liquid glass alias",
)
text = replace_once(
    text,
    'if (publicCleartextRejected) "公网 HTTP 已禁用" else "局域网 HTTP 未加密"',
    '"HTTP 未加密"',
    "add server HTTP title",
)
text = replace_once(
    text,
    '"我确认这是可信局域网，继续使用 HTTP"',
    '"我已了解明文传输风险，继续使用 HTTP"',
    "add server HTTP acknowledgement",
)
write(rel, text)

# 4) Dialog action buttons default to liquid glass, while callers can explicitly preserve flat buttons.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Dialogs.kt"
text = read(rel)
text = replace_once(
    text,
    "fun GlassDialog(\n"
    "    onDismiss: () -> Unit,\n"
    "    modifier: Modifier = Modifier,\n"
    "    scrollable: Boolean = true,\n"
    "    content: @Composable ColumnScope.() -> Unit,\n"
    ") {",
    "fun GlassDialog(\n"
    "    onDismiss: () -> Unit,\n"
    "    modifier: Modifier = Modifier,\n"
    "    scrollable: Boolean = true,\n"
    "    liquidButtons: Boolean = true,\n"
    "    content: @Composable ColumnScope.() -> Unit,\n"
    ") {",
    "GlassDialog liquidButtons parameter",
)
text = replace_once(
    text,
    "        CompositionLocalProvider(LocalOverlayDismiss provides requestDismiss) {",
    "        CompositionLocalProvider(\n"
    "            LocalOverlayDismiss provides requestDismiss,\n"
    "            LocalOverlayLiquidButtons provides liquidButtons,\n"
    "        ) {",
    "GlassDialog button material provider",
)
text = replace_once(
    text,
    "private val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }",
    "private val LocalOverlayDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }\n"
    "private val LocalOverlayLiquidButtons = staticCompositionLocalOf { true }",
    "overlay liquid material local",
)
ink_block = '''    val ink =
        when (tone) {
            OverlayButtonTone.Primary -> if (enabled) accent.onAccent else accent.accent
            OverlayButtonTone.Destructive -> palette.error
            OverlayButtonTone.Plain -> palette.text
        }
    Box(
'''
ink_block_new = '''    val ink =
        when (tone) {
            OverlayButtonTone.Primary -> if (enabled) accent.onAccent else accent.accent
            OverlayButtonTone.Destructive -> palette.error
            OverlayButtonTone.Plain -> palette.text
        }
    val surface =
        if (LocalOverlayLiquidButtons.current) {
            Modifier.liquidGlass(
                shape = shape,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (tone == OverlayButtonTone.Plain) 0.62f else 0.82f,
            )
        } else {
            Modifier.flatGlass(shape, fill, border)
        }
    Box(
'''
text = replace_once(text, ink_block, ink_block_new, "OverlayButton liquid surface")
text = replace_once(text, ").flatGlass(shape, fill, border),", ").then(surface),", "OverlayButton surface application")
marker = "fun ConfirmDialog("
idx = text.find(marker)
if idx < 0:
    raise RuntimeError("ConfirmDialog marker not found")
head, tail = text[:idx], text[idx:]
tail = replace_once(
    tail,
    "    dismissLabel: String = \"取消\",\n"
    "    destructive: Boolean = false,\n"
    ") {",
    "    dismissLabel: String = \"取消\",\n"
    "    destructive: Boolean = false,\n"
    "    liquidButtons: Boolean = true,\n"
    ") {",
    "ConfirmDialog liquidButtons parameter",
)
tail = replace_once(
    tail,
    "    GlassDialog(onDismiss = onDismiss) {",
    "    GlassDialog(onDismiss = onDismiss, liquidButtons = liquidButtons) {",
    "ConfirmDialog liquid propagation",
)
text = head + tail
write(rel, text)

# 5) Settings/link actions use the same liquid button material instead of bare text links.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/FormControls.kt"
text = read(rel)
start = text.find("@Composable\nfun YfLinkButton(")
if start < 0:
    raise RuntimeError("YfLinkButton not found")
new_link = '''@Composable
fun YfLinkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val fill = if (destructive) palette.errorContainer else palette.card2
    val border = if (destructive) palette.error else palette.border
    val content = if (destructive) palette.error else accent.accent
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pressable(
                enabled = enabled,
                haptic = HapticSignal.Confirm.takeIf { destructive },
                focusShape = AppShapes.control,
                onClickLabel = label,
                onClick = onClick,
            )
            .liquidGlass(
                shape = AppShapes.control,
                fill = fill,
                border = border,
                over = palette.background,
                sheen = if (destructive) 0.74f else 0.62f,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTypography.body.strong,
            color = content,
        )
    }
}
'''
text = text[:start] + new_link
write(rel, text)

# 6) Server cards keep a stable height; server-management dialog actions become liquid.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabScreen.kt"
text = read(rel)
start = text.find("private fun ServerCard(")
end = text.find("private fun ServerCountsRow", start)
if start < 0 or end < 0:
    raise RuntimeError("ServerCard segment not found")
segment = text[start:end]
segment = replace_once(
    segment,
    "                            maxLines = 2,\n"
    "                            overflow = TextOverflow.Ellipsis,",
    "                            minLines = 2,\n"
    "                            maxLines = 2,\n"
    "                            overflow = TextOverflow.Ellipsis,",
    "server card reserved title lines",
)
text = text[:start] + segment + text[end:]
text = text.replace("已确认局域网 HTTP 风险", "已确认 HTTP 风险")
text = text.replace("确认局域网 HTTP 风险", "确认 HTTP 风险")
start = text.find("private fun ServerActionRow(")
end = text.find("private fun ServerRoutesDialog", start)
if start < 0 or end < 0:
    raise RuntimeError("ServerActionRow segment not found")
segment = text[start:end]
segment = replace_once(segment, ".glass(\n", ".liquidGlass(\n", "server action row liquid material")
text = text[:start] + segment + text[end:]
start = text.find("private fun ServerRouteRow(")
end = text.find("private fun routeStatusLabel", start)
if start < 0 or end < 0:
    raise RuntimeError("ServerRouteRow segment not found")
segment = text[start:end]
segment = replace_once(segment, ".flatGlass(\n", ".liquidGlass(\n", "server route row liquid material")
segment = replace_once(
    segment,
    ".flatGlass(CircleShape, palette.card, palette.border)",
    ".liquidGlass(CircleShape, palette.card, palette.border)",
    "server route remove button liquid material",
)
text = text[:start] + segment + text[end:]
write(rel, text)

# 7) Detail-page and playback-page dialogs explicitly retain their existing flat action buttons.
for directory in [
    ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail",
    ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/feature/player",
]:
    for path in directory.glob("*.kt"):
        original = path.read_text(encoding="utf-8")
        updated = re.sub(r"GlassDialog\((?!\s*liquidButtons\s*=)", "GlassDialog(liquidButtons = false, ", original)
        if directory.name == "player":
            updated = re.sub(r"ConfirmDialog\((?!\s*liquidButtons\s*=)", "ConfirmDialog(liquidButtons = false, ", updated)
        if updated != original:
            path.write_text(updated, encoding="utf-8")

# The host invitation sheet is launched from the detail page; the guest invite dialog remains liquid.
rel = "composeApp/src/commonMain/kotlin/com/yfuse/feature/watch/WatchInviteSheet.kt"
text = read(rel)
text = replace_in_tail(
    text,
    "fun WatchInviteShareSheet(",
    "    GlassDialog(onDismiss = onDismiss) {",
    "    GlassDialog(onDismiss = onDismiss, liquidButtons = false) {",
    "detail host invite keeps flat buttons",
)
write(rel, text)

# 8) Release metadata. Updating version.properties publishes only after this branch is merged to master.
write(
    "version.properties",
    "# Release metadata; changing this file triggers the production publish workflow.\n"
    "VERSION_CODE=135\n"
    "VERSION_NAME=0.2.56\n",
)
write(
    "release-notes.txt",
    "允许在明确确认风险后连接公网或局域网 HTTP Emby 服务器；统一设置中心和其他页面弹窗的操作按钮为液态玻璃材质，并让服务器卡片保持一致高度。详情页与播放页既有按钮样式保持不变，同时优化添加服务器的 HTTP 风险提示与交互一致性。\n",
)

# Guard against accidentally leaving the old Emby-public-HTTP rejection copy in production code.
remaining = []
for path in (ROOT / "composeApp/src").rglob("*.kt"):
    body = path.read_text(encoding="utf-8")
    if "公网 Emby 服务器必须使用 HTTPS" in body or "公网 HTTP 已禁用" in body:
        remaining.append(str(path.relative_to(ROOT)))
if remaining:
    raise RuntimeError("stale public HTTP rejection copy remains: " + ", ".join(remaining))

print("Applied HTTP, liquid-glass, stable-card-height, and release metadata updates.")
