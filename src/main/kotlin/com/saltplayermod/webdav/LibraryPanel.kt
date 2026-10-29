package com.saltplayermod.webdav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal data class SettingsPalette(
    val highlight: Color, val text: Color, val subText: Color,
    val background: Color, val card: Color, val stroke: Color, val onHighlight: Color,
    val success: Color, val warning: Color, val error: Color,
    val main: TextStyle, val sub: TextStyle, val small: Shape, val medium: Shape, val padding: Dp,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryPanel(controller: LibraryController, insets: PaddingValues, palette: SettingsPalette) {
    val ui by controller.state.collectAsState()
    val password by controller.password.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val visible = remember(ui.tracks, ui.query) {
        ui.tracks.filter { it.path.contains(ui.query.trim(), ignoreCase = true) }
    }
    val contentPadding = PaddingValues(
        start = insets.calculateStartPadding(direction) + palette.padding,
        end = insets.calculateEndPadding(direction) + palette.padding,
        top = insets.calculateTopPadding() + 8.dp,
        bottom = insets.calculateBottomPadding() + 24.dp,
    )
    if (ui.closed) {
        BasicText("模组已停用，重新启用后可继续设置。", Modifier.padding(contentPadding), style = palette.main.copy(color = palette.subText))
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        key("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("WebDAV 音乐库", style = palette.main.copy(color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
                Hint("连接云端音乐，在这里浏览、播放与双向同步。", palette)
            }
        }
        key("connection") {
            SettingsSection("服务器连接", palette) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    FieldLabel("服务类型", palette)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Choice("WebDAV", !ui.settings.alist, !ui.busy, palette) { controller.editSettings { it.copy(alist = false) } }
                        Choice("AList / OpenList", ui.settings.alist, !ui.busy, palette) { controller.editSettings { it.copy(alist = true) } }
                    }
                }
                InlineField("服务器地址", ui.settings.address, "https://music.example.com", !ui.busy, palette,
                    onValueChange = { value -> controller.editSettings { it.copy(address = value) } })
                if (ui.settings.alist) Hint("可填写 AList 站点地址，WebDAV 入口会自动补全。", palette)
                InlineField("远端音乐目录", ui.settings.folder, "/音乐", !ui.busy, palette,
                    onValueChange = { value -> controller.editSettings { it.copy(folder = value) } })
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val account: @Composable () -> Unit = {
                        InlineField("用户名", ui.settings.username, "匿名访问可留空", !ui.busy, palette,
                            onValueChange = { value -> controller.editSettings { it.copy(username = value) } })
                    }
                    val secret: @Composable () -> Unit = {
                        InlineField("密码", password, "输入 WebDAV 密码", !ui.busy, palette,
                            onValueChange = controller::editPassword, password = true, revealed = showPassword,
                            trailing = {
                                SettingsButton(if (showPassword) "隐藏" else "显示", !ui.busy, palette, compact = true) { showPassword = !showPassword }
                            })
                    }
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.weight(1f)) { account() }
                            Box(Modifier.weight(1f)) { secret() }
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { account(); secret() }
                }
                Hint("连接时保存设置；密码仅在本次启用期间保留。", palette)
            }
        }
        key("local-library") {
            SettingsSection("本地同步", palette) {
                InlineField("音乐文件夹", ui.settings.directory, "粘贴本地音乐文件夹路径", !ui.busy, palette,
                    onValueChange = { value -> controller.editSettings { it.copy(directory = value) } })
                Hint("新增与修改会双向同步；两端都修改时保留文件并提示冲突。单端缺少的音乐会从另一端补回。", palette)
            }
        }
        key("actions") {
            SettingsSection("连接与同步", palette) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (ui.connected) palette.success else palette.subText))
                    BasicText(if (ui.connected) "已连接 · ${ui.tracks.size} 首音乐" else "尚未连接", style = palette.main.copy(color = palette.text))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton(if (ui.connected) "重新扫描" else "连接并扫描", !ui.busy, palette, primary = !ui.connected, onClick = controller::connect)
                    SettingsButton("双向同步", ui.connected && !ui.busy, palette, primary = ui.connected, onClick = controller::sync)
                    if (ui.busy) SettingsButton(if (ui.cancelling) "正在取消" else "取消", !ui.cancelling, palette, onClick = controller::cancel)
                }
                if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = palette.highlight, backgroundColor = palette.stroke)
                Hint(ui.status, palette)
                ui.error?.let { message ->
                    SelectionContainer { BasicText(message, style = palette.sub.copy(color = palette.error)) }
                }
                ui.result?.let { result ->
                    if (result.downloaded > 0) Hint("将同步目录添加到播放器音乐库并刷新，即可在本地列表中看到下载的歌曲。", palette)
                    val count = result.conflicts.size + result.failures.size
                    if (count > 0) SettingsButton(
                        if (ui.detailsExpanded) "收起 $count 个冲突与失败项" else "查看 $count 个冲突与失败项",
                        true, palette, onClick = controller::toggleDetails,
                    )
                }
            }
        }
        if (ui.detailsExpanded) {
            ui.result?.let { result ->
                result.conflicts.take(100).forEach { issue -> key("conflict:$issue") { InlineIssue("已保留冲突文件", issue, palette.warning, palette) } }
                result.failures.take(100).forEach { issue -> key("failure:$issue") { InlineIssue("同步未完成", issue, palette.error, palette) } }
                if (result.conflicts.size > 100 || result.failures.size > 100) Hint("每类展示前 100 项，处理后可重新同步查看。", palette)
            }
        }
        key("remote-header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("远端音乐", palette)
                    Hint(if (ui.query.isBlank()) "${ui.tracks.size} 首" else "${visible.size} / ${ui.tracks.size} 首", palette)
                }
                InlineField("筛选", ui.query, "按文件名或目录搜索", ui.connected, palette, labelVisible = false, onValueChange = controller::editQuery)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton("播放选中${if (ui.selected.isEmpty()) "" else "（${ui.selected.size}）"}",
                        ui.connected && !ui.busy && ui.selected.isNotEmpty() && controller.canPlay, palette, onClick = { controller.play() })
                    if (ui.selected.isNotEmpty()) SettingsButton("清除选择", !ui.busy, palette, onClick = controller::clearSelection)
                }
                if (!controller.canPlay) Hint(controller.playbackUnavailable, palette)
            }
        }
        if (visible.isEmpty()) key("empty") {
            Column(Modifier.fillMaxWidth().border(1.dp, palette.stroke, palette.medium).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(if (ui.connected) "没有找到音乐" else "连接后在这里浏览音乐", style = palette.main.copy(color = palette.text))
                Hint(if (ui.connected) "可以调整筛选条件或检查远端目录。" else "选择歌曲可在线播放，也可以同步到本地音乐文件夹。", palette)
            }
        }
        if (visible.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().height((visible.size * 76).coerceIn(76, 420).dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.path }) { track -> TrackRow(track, ui, controller, palette) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: RemoteTrack, ui: LibraryState, controller: LibraryController, palette: SettingsPalette) {
    val selected = track.path in ui.selected
    Row(
        Modifier.fillMaxWidth().clip(palette.medium)
            .background(if (selected) palette.highlight.copy(alpha = 0.10f) else palette.card)
            .border(1.dp, if (selected) palette.highlight.copy(alpha = 0.45f) else palette.stroke, palette.medium)
            .combinedClickable(enabled = ui.connected && !ui.busy, role = Role.Checkbox,
                onClick = { controller.toggleSelection(track.path) },
                onDoubleClick = if (controller.canPlay) ({ controller.play(setOf(track.path)) }) else null)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, { controller.toggleSelection(track.path) }, enabled = ui.connected && !ui.busy,
            colors = CheckboxDefaults.colors(checkedColor = palette.highlight, uncheckedColor = palette.subText,
                checkmarkColor = palette.onHighlight, disabledColor = palette.stroke))
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(track.name, style = palette.main.copy(color = palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parent = track.path.substringBeforeLast('/', "根目录")
            BasicText("$parent · ${formatSize(track.size)}", style = palette.sub.copy(color = palette.subText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SettingsButton("播放", controller.canPlay && ui.connected && !ui.busy, palette, compact = true) { controller.play(setOf(track.path)) }
    }
}

@Composable
private fun SettingsSection(title: String, palette: SettingsPalette, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(title, Modifier.padding(start = 12.dp), style = palette.sub.copy(color = palette.subText, fontWeight = FontWeight.Medium))
        Column(
            Modifier.fillMaxWidth().clip(palette.medium).background(palette.card)
                .border(1.dp, palette.stroke, palette.medium).padding(palette.padding),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
        )
    }
}

@Composable
private fun InlineField(
    label: String, value: String, placeholder: String, enabled: Boolean, palette: SettingsPalette,
    onValueChange: (String) -> Unit, password: Boolean = false, revealed: Boolean = false,
    labelVisible: Boolean = true, trailing: (@Composable () -> Unit)? = null,
) {
    val input: @Composable () -> Unit = {
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = palette.main.copy(color = palette.text), cursorBrush = SolidColor(palette.highlight),
            visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(palette.small)
                        .background(palette.background.copy(alpha = 0.6f))
                        .border(1.dp, palette.stroke, palette.small).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) BasicText(placeholder, style = palette.main.copy(color = palette.subText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (labelVisible && maxWidth >= 420.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(116.dp)) { FieldLabel(label, palette) }
                Box(Modifier.weight(1f)) { input() }
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (labelVisible) FieldLabel(label, palette)
            input()
        }
    }
}

@Composable
private fun SettingsButton(
    text: String, enabled: Boolean, palette: SettingsPalette,
    primary: Boolean = false, compact: Boolean = false, onClick: () -> Unit,
) {
    val color = if (primary) palette.onHighlight else palette.text
    Box(
        Modifier.alpha(if (enabled) 1f else 0.42f).clip(palette.small)
            .background(if (primary) palette.highlight else palette.card)
            .border(1.dp, if (primary) palette.highlight else palette.stroke, palette.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = palette.main.copy(color = color), maxLines = 1)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean, palette: SettingsPalette, onClick: () -> Unit) {
    Box(
        Modifier.alpha(if (enabled) 1f else 0.42f).clip(palette.small)
            .background(if (selected) palette.highlight.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) palette.highlight else palette.stroke, palette.small)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        BasicText(text, style = palette.main.copy(color = if (selected) palette.highlight else palette.text), maxLines = 1)
    }
}

@Composable
private fun FieldLabel(text: String, palette: SettingsPalette) {
    BasicText(text, style = palette.main.copy(color = palette.text, fontWeight = FontWeight.Medium))
}

@Composable
private fun Hint(text: String, palette: SettingsPalette) {
    BasicText(text, style = palette.sub.copy(color = palette.subText))
}

@Composable
private fun InlineIssue(title: String, issue: String, color: Color, palette: SettingsPalette) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.3f), palette.medium).padding(palette.padding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(title, style = palette.sub.copy(color = color, fontWeight = FontWeight.Medium))
        SelectionContainer { BasicText(issue, style = palette.sub.copy(color = palette.text)) }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
