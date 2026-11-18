package com.vaultplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
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
    LaunchedEffect(ids) { checked = vm.commonTags(ids) }
    val matchingTags = allTags.filter { text.isBlank() || it.name.contains(text.trim(), ignoreCase = true) }
    val exactMatch = allTags.any { it.name.equals(text.trim(), ignoreCase = true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .padding(bottom = 16.dp).navigationBarsPadding().imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ids.size > 1) "Gắn tag cho ${ids.size} mục" else "Gắn tag",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Chọn tag có sẵn hoặc tạo tag mới",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Đóng") }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Tìm tag") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = "" }) {
                        Icon(Icons.Default.Close, "Xóa tìm kiếm")
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (text.isNotBlank() && !exactMatch) {
                FilledTonalButton(
                    onClick = {
                        vm.createTag(text, ids) { tag -> checked = checked + tag.id }
                        text = ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Tạo “${text.trim()}”")
                }
            }
            if (matchingTags.isEmpty()) {
                Text(
                    if (text.isBlank()) "Chưa có tag. Tìm kiếm để tạo tag đầu tiên."
                    else "Không tìm thấy tag phù hợp.",
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matchingTags, key = { it.id }) { tag ->
                        val isChecked = tag.id in checked
                        Surface(
                            onClick = {
                                val shouldAttach = !isChecked
                                vm.setTag(ids, tag.id, shouldAttach)
                                checked = if (shouldAttach) checked + tag.id else checked - tag.id
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isChecked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { shouldAttach ->
                                        vm.setTag(ids, tag.id, shouldAttach)
                                        checked = if (shouldAttach) checked + tag.id else checked - tag.id
                                    }
                                )
                                Text(
                                    tag.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1
                                )
                                Text(
                                    "${tag.cnt}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
            }
            if (checked.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("ĐÃ GẮN · ${checked.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Xong") }
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
    var actionMenu by remember { mutableStateOf<Long?>(null) }
    val visibleTags = tags.filter { it.name.contains(search.trim(), ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .navigationBarsPadding().imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        null,
                        Modifier.padding(11.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Quản lý tag", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Tạo, tìm và sắp xếp tag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        "${tags.size}",
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Đóng") }
            }

            Text("TẠO TAG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    singleLine = true,
                    placeholder = { Text("Tên tag mới") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    enabled = newTag.isNotBlank(),
                    onClick = { vm.createTag(newTag, emptyList()) { newTag = "" } },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Icon(Icons.Default.Add, "Tạo tag")
                    Spacer(Modifier.width(6.dp))
                    Text("Tạo")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("DANH SÁCH TAG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
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
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (visibleTags.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (search.isBlank()) "Chưa có tag nào" else "Không tìm thấy tag",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleTags, key = { it.id }) { tag ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TagChip(tag.name)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${tag.cnt} mục",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box {
                                    IconButton(onClick = { actionMenu = tag.id }) {
                                        Icon(Icons.Default.MoreVert, "Thao tác với tag")
                                    }
                                    DropdownMenu(
                                        expanded = actionMenu == tag.id,
                                        onDismissRequest = { actionMenu = null }
                                    ) {
                                        if (admin) DropdownMenuItem(
                                            text = { Text("Đổi tên hoặc gộp") },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { actionMenu = null; ren = tag }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Xóa tag") },
                                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { actionMenu = null; del = tag }
                                        )
                                    }
                                }
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
