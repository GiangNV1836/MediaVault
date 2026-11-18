package com.vaultplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaultplayer.data.TagCount

/** Gắn/gỡ tag cho một hoặc nhiều mục. Ô nhập vừa lọc tag có sẵn vừa tạo tag mới. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagSheet(vm: MainViewModel, ids: List<Long>, onDismiss: () -> Unit) {
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    var checked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(ids) { checked = vm.commonTags(ids) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = 640.dp) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(if (ids.size > 1) "Gắn tag cho ${ids.size} mục" else "Gắn tag", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Tìm hoặc tạo tag") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(enabled = text.isNotBlank(), onClick = {
                        vm.createTag(text, ids) { t -> checked = checked + t.id }
                        text = ""
                    }) { Icon(Icons.Default.Add, "Tạo tag") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.filter { text.isBlank() || it.name.contains(text.trim().lowercase()) }.forEach { t ->
                        FilterChip(
                            selected = t.id in checked,
                            onClick = {
                                val on = t.id !in checked
                                vm.setTag(ids, t.id, on)
                                checked = if (on) checked + t.id else checked - t.id
                            },
                            label = { Text(t.name) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Xong") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(vm: MainViewModel, ids: List<Long>, onDismiss: () -> Unit) {
    val pls by vm.playlists.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = 640.dp) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Thêm vào playlist", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Tạo playlist mới") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(enabled = name.isNotBlank(), onClick = { vm.createManual(name, ids); onDismiss() }) { Icon(Icons.Default.Add, "Tạo") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(pls.filter { it.p.type == "MANUAL" }, key = { it.p.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.p.name) },
                        supportingContent = { Text("${p.count} mục") },
                        modifier = Modifier.fillMaxWidth().then(Modifier),
                        trailingContent = { TextButton(onClick = { vm.addToPlaylist(p.p.id, ids); onDismiss() }) { Text("Thêm") } }
                    )
                }
            }
        }
    }
}

/** Quản lý tag: mọi người dùng xóa được tag; Admin thêm đổi tên/gộp. */
@Composable
fun TagManageDialog(vm: MainViewModel, admin: Boolean, onDismiss: () -> Unit) {
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    var del by remember { mutableStateOf<TagCount?>(null) }
    var ren by remember { mutableStateOf<TagCount?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quản lý tag") },
        text = {
            if (tags.isEmpty()) Text("Chưa có tag")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(tags, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${t.name} (${t.cnt})", Modifier.weight(1f))
                        if (admin) IconButton(onClick = { ren = t }) { Icon(Icons.Default.Edit, "Đổi tên/gộp") }
                        IconButton(onClick = { del = t }) { Icon(Icons.Default.Delete, "Xóa") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
    del?.let { t -> ConfirmDialog("Xóa tag", "Xóa tag \"${t.name}\" khỏi ${t.cnt} mục?", "Xóa", { del = null }) { vm.deleteTag(t.id); del = null } }
    ren?.let { t -> TextInputDialog("Đổi tên / gộp tag", "Tên mới (trùng tên tag khác sẽ gộp)", t.name, "Lưu", onDismiss = { ren = null }) { vm.renameTag(t.id, it); ren = null } }
}
