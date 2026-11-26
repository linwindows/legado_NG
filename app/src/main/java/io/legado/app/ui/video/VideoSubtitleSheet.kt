package io.legado.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.subtitle.SubtitleCue
import io.legado.app.help.subtitle.SubtitleItem
import io.legado.app.help.subtitle.SubtitleSearchHelp
import io.legado.app.model.VideoPlay
import io.legado.app.ui.design.components.compose.NgDrawerDragHandle
import io.legado.app.ui.design.components.compose.NgDrawerDragHandleVariant
import kotlinx.coroutines.launch

/**
 * 播放页在线字幕面板：番号搜索（迅雷字幕 API）→ 选择 → 热挂载到当前播放器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSubtitleSheet(
    initialCode: String?,
    onMount: (List<SubtitleCue>, String) -> Unit,
    onUnmount: () -> Unit,
    onDone: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = videoContentColors()
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf(initialCode.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SubtitleItem>?>(null) }
    var searched by rememberSaveable { mutableStateOf(false) }
    var mountedUrl by remember { mutableStateOf(VideoPlay.subtitleUrl) }

    fun doSearch() {
        val target = code.trim()
        if (target.isEmpty()) {
            error = "请输入番号"
            return
        }
        scope.launch {
            loading = true
            error = null
            searched = true
            results = try {
                SubtitleSearchHelp.search(target.uppercase())
            } catch (e: Exception) {
                error = "搜索失败：${e.message}"
                null
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        if (!searched && initialCode != null && results == null) doSearch()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 480.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = colors.surface,
        contentColor = colors.content,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets.safeDrawing },
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            NgDrawerDragHandle(Modifier.padding(top = 8.dp), variant = NgDrawerDragHandleVariant.COMPACT)
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text("在线字幕", color = colors.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (mountedUrl != null) {
                    TextButton(
                        onClick = {
                            mountedUrl = null
                            onUnmount()
                            onDone("字幕已卸载")
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { Text("卸载", color = colors.accent, fontSize = 13.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("番号，如 HUBLK-070", color = colors.muted, fontSize = 14.sp) },
                )
                TextButton(
                    onClick = { doSearch() },
                    enabled = !loading,
                ) { Text(if (loading) "搜索中" else "搜索", color = colors.accent, fontSize = 14.sp) }
            }
            if (mountedUrl != null) {
                Text("当前字幕：" + mountedUrl!!.substringAfterLast('/'),
                    Modifier.padding(top = 6.dp), color = colors.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            error?.let {
                Text(it, Modifier.padding(top = 10.dp), color = colors.accent, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                }
            }
            results?.let { list ->
                if (list.isEmpty()) {
                    Text("没有找到可用字幕", Modifier.padding(top = 16.dp), color = colors.muted, fontSize = 13.sp)
                } else {
                    Text("共 ${list.size} 条，点击挂载", Modifier.padding(top = 12.dp),
                        color = colors.muted, fontSize = 12.sp)
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { item ->
                            val chinese = item.languages.any { it.contains("简") || it.contains("繁") || it.contains("中") }
                            Column(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.cell)
                                .clickable {
                                    scope.launch {
                                        loading = true
                                        error = null
                                        try {
                                            val cues = SubtitleSearchHelp.download(item)
                                            if (cues.isEmpty()) {
                                                error = "字幕文件为空或格式不受支持"
                                            } else {
                                                onMount(cues, item.name)
                                                mountedUrl = item.url
                                                onDone("已加载 ${cues.size} 条字幕")
                                            }
                                        } catch (e: Exception) {
                                            error = "字幕下载失败：${e.message}"
                                        } finally {
                                            loading = false
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(item.name, color = colors.content, fontSize = 13.sp, lineHeight = 18.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (chinese) "中文 " else "") + item.languages.joinToString(" "),
                                    Modifier.padding(top = 4.dp),
                                    color = if (chinese) colors.accent else colors.muted, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
            if (!loading && results == null && error == null) {
                Text("自动从标题提取番号，也可手动输入后搜索；字幕直接挂载到内置播放器。",
                    Modifier.padding(top = 12.dp), color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
