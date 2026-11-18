package com.vaultplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
    val allTags by vm.tagManagementCounts.collectAsStateWithLifecycle()
    var checked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var text by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(ids) { checked = vm.commonTags(ids) }
    val matchingTags = allTags.filter { text.isBlank() || it.name.contains(text.trim(), ignoreCase = true) }
    val exactMatch = allTags.any { it.name.equals(text.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = 640.dp) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Quay lại") }
                Text(if (ids.size > 1) "Gắn tag cho ${ids.size} mục" else "Gắn tag", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; menuExpanded = true },
                    singleLine = true,
                    label = { Text("Tìm tag đã tạo") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { menuExpanded = !menuExpanded }) {
                            Icon(if (menuExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Chọn tag")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                ) {
                    if (matchingTags.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Chưa có tag phù hợp") },
                            onClick = { menuExpanded = false },
                            enabled = false
                        )
                    } else matchingTags.forEach { t ->
                        DropdownMenuItem(
                            text = { Text("${t.name} · ${t.cnt} mục") },
                            leadingIcon = {
                                Checkbox(checked = t.id in checked, onCheckedChange = null)
                            },
                            onClick = {
                                val on = t.id !in checked
                                vm.setTag(ids, t.id, on)
                                checked = if (on) checked + t.id else checked - t.id
                                text = ""
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
            if (text.isNotBlank() && !exactMatch) {
                TextButton(
                    onClick = {
                        vm.createTag(text, ids) { tag -> checked = checked + tag.id }
                        text = ""
                        menuExpanded = false
                    },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Tạo “${text.trim()}”")
                }
            }
            if (checked.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    allTags.filter { it.id in checked }.forEach { t ->
                        InputChip(
                            selected = true,
                            onClick = {
                                vm.setTag(ids, t.id, false)
                                checked = checked - t.id
                            },
                            label = { Text(t.name) },
                            trailingIcon = { Icon(Icons.Default.Close, "Gỡ tag", Modifier.size(16.dp)) }
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
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Quay lại") }
                Text("Thêm vào playlist", style = MaterialTheme.typography.titleMedium)
            }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageDialog(vm: MainViewModel, admin: Boolean, onDismiss: () -> Unit) {
    val tags by vm.tagManagementCounts.collectAsStateWithLifecycle()
    var del by remember { mutableStateOf<TagCount?>(null) }
    var ren by remember { mutableStateOf<TagCount?>(null) }
    var newTag by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val visibleTags = tags.filter { it.name.contains(search.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = 640.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .navigationBarsPadding().imePadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Quay lại") }
                Column(Modifier.weight(1f)) {
                    Text("Quản lý tag", style = MaterialTheme.typography.titleLarge)
                    Text("${tags.size} tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text("Xong") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    placeholder = { Text("Tạo tag mới") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = newTag.isNotBlank(),
                    onClick = { vm.createTag(newTag, emptyList()) { newTag = "" } }
                ) { Icon(Icons.Default.Add, "Tạo tag") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text("Tìm tag") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                        Icon(Icons.Default.Close, "Xóa tìm kiếm")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (visibleTags.isEmpty()) {
                Text(
                    if (search.isBlank()) "Chưa có tag" else "Không tìm thấy tag",
                    Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(visibleTags, key = { it.id }) { tag ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TagChip(tag.name)
                            Text(
                                "${tag.cnt} mục",
                                Modifier.weight(1f).padding(start = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (admin) IconButton(onClick = { ren = tag }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Edit, "Đổi tên hoặc gộp tag", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { del = tag }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.DeleteOutline, "Xóa tag", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    del?.let { t -> ConfirmDialog("Xóa tag", "Xóa tag \"${t.name}\" khỏi ${t.cnt} mục?", "Xóa", { del = null }) { vm.deleteTag(t.id); del = null } }
    ren?.let { t -> TextInputDialog("Đổi tên / gộp tag", "Tên mới (trùng tên tag khác sẽ gộp)", t.name, "Lưu", onDismiss = { ren = null }) { vm.renameTag(t.id, it); ren = null } }
}
