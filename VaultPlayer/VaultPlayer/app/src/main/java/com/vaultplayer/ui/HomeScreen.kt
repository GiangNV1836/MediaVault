package com.vaultplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.vaultplayer.data.MediaRow

private val TAB_LABELS = listOf("Tất cả", "Playlist", "Yêu thích")
private val COMPACT_TAB_LABELS = listOf("Tất cả", "Playlist", "Yêu thích")
private fun tabIcon(i: Int) = when (i) { 0 -> Icons.Default.VideoLibrary; 1 -> Icons.AutoMirrored.Filled.PlaylistPlay; else -> Icons.Default.Favorite }

@Composable
fun MainScreen(vm: MainViewModel, canBio: Boolean, onBio: (() -> Unit) -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    var pinSet by remember { mutableStateOf(vm.vault.pin.isSet) }
    BackHandler(enabled = screen != Screen.MAIN || sel.isNotEmpty()) {
        if (screen != Screen.MAIN) vm.screen.value = Screen.MAIN else vm.clearSelection()
    }
    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.MAIN -> HomeScreen(vm, canBio, onBio)
            Screen.PLAYER -> PlayerHost(vm)
            Screen.SETTINGS -> SettingsScreen(vm, canBio, onBio)
        }
        if (!pinSet) SetPinDialog(onSet = { pin -> vm.vault.pin.set(pin) }, onDone = { pinSet = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, canBio: Boolean, onBio: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val versionInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            "v${it.versionName ?: "?"} · build ${it.longVersionCode}"
        }
    }
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val warnings by vm.vault.backup.warnings.collectAsStateWithLifecycle()
    val staged by vm.vault.scanner.staged.collectAsStateWithLifecycle()
    val compact = isCompactWidth()
    val snack = remember { SnackbarHostState() }
    var showUnlock by remember { mutableStateOf(false) }
    var tagIds by remember { mutableStateOf<List<Long>?>(null) }
    var plIds by remember { mutableStateOf<List<Long>?>(null) }

    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }

    Scaffold(
        topBar = {
            if (sel.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${sel.size} đã chọn", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Bỏ chọn") } },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Default.SelectAll, "Chọn tất cả") }
                        IconButton(onClick = { vm.favoriteSelected(true) }) { Icon(Icons.Default.Favorite, "Yêu thích") }
                        IconButton(onClick = { tagIds = sel.toList() }) { Icon(Icons.AutoMirrored.Filled.Label, "Gắn tag") }
                        IconButton(onClick = { plIds = sel.toList() }) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Thêm vào playlist") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("VaultPlayer", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "$versionInfo · ${if (admin) "Quản trị" else "Thư viện riêng tư"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                maxLines = 1
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (admin) vm.admin.lock() else showUnlock = true }) {
                            Icon(if (admin) Icons.Default.LockOpen else Icons.Default.Lock, "Admin")
                        }
                        IconButton(onClick = { vm.screen.value = Screen.SETTINGS }) {
                            if (admin && warnings.isNotEmpty()) BadgedBox(badge = { Badge() }) { Icon(Icons.Default.Settings, "Cài đặt") }
                            else Icon(Icons.Default.Settings, "Cài đặt")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        },
        bottomBar = {
            if (compact) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 4.dp
            ) {
                TAB_LABELS.forEachIndexed { i, _ ->
                    NavigationBarItem(selected = tab == i, onClick = { vm.tab.value = i; vm.clearSelection() },
                        icon = { Icon(tabIcon(i), null) }, label = { Text(COMPACT_TAB_LABELS[i], maxLines = 1) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (!compact) NavigationRail(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Spacer(Modifier.weight(1f))
                TAB_LABELS.forEachIndexed { i, l ->
                    NavigationRailItem(selected = tab == i, onClick = { vm.tab.value = i; vm.clearSelection() },
                        icon = { Icon(tabIcon(i), null) }, label = { Text(l, maxLines = 1) })
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    0 -> AllTab(vm)
                    1 -> PlaylistTab(vm)
                    else -> FavTab(vm)
                }
            }
        }
    }

    if (showUnlock) AdminUnlockDialog(vm.vault, canBio, onBio) { showUnlock = false }
    tagIds?.let { TagSheet(vm, it) { tagIds = null } }
    plIds?.let { PlaylistSheet(vm, it) { plIds = null } }

    // Thay đổi lớn do quét: chỉ Admin thấy, chỉ hiện SỐ LƯỢNG.
    val st = staged
    if (admin && st != null) ConfirmDialog(
        "Phát hiện thay đổi lớn", "${st.missing.size} mục mất, ${st.newFiles.size} mục mới. Áp dụng?", "Áp dụng",
        onDismiss = { vm.vault.scanner.discardStaged() }
    ) { vm.sources.confirmStaged() }
}

@Composable
private fun MediaGrid(
    vm: MainViewModel, items: LazyPagingItems<MediaRow>, admin: Boolean, onOpen: (Long) -> Unit, emptyText: String
) {
    val sel by vm.selection.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val autoShow = vm.vault.prefs.autoShowThumbs
    var aliasEdit by remember { mutableStateOf<MediaRow?>(null) }
    var minCellDp by remember { mutableIntStateOf(vm.vault.prefs.gridMinCellDp) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    minCellDp = (minCellDp - 20).coerceAtLeast(140)
                    vm.vault.prefs.gridMinCellDp = minCellDp
                },
                enabled = minCellDp > 140
            ) { Icon(Icons.Default.ZoomOut, "Thu nhỏ ô lưới") }
            Slider(
                value = minCellDp.toFloat(),
                onValueChange = {
                    minCellDp = it.toInt()
                    vm.vault.prefs.gridMinCellDp = minCellDp
                },
                valueRange = 140f..300f,
                steps = 7,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    minCellDp = (minCellDp + 20).coerceAtMost(300)
                    vm.vault.prefs.gridMinCellDp = minCellDp
                },
                enabled = minCellDp < 300
            ) { Icon(Icons.Default.ZoomIn, "Phóng to ô lưới") }
        }
        if (items.itemCount == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (items.loadState.refresh) {
                    is LoadState.Loading -> CircularProgressIndicator()
                    is LoadState.Error -> Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Không tải được danh sách", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { items.retry() }) { Text("Thử lại") }
                    }
                    else -> EmptyState(Icons.Default.VideoLibrary, emptyText)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minCellDp.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                    val row = items[i]
                    if (row != null) MediaCard(
                        vm, row, admin, selectionMode = sel.isNotEmpty(), selected = row.id in sel,
                        shown = row.id in shown, autoShow = autoShow, onOpen = { onOpen(row.id) }, onEditAlias = { aliasEdit = row }
                    )
                }
            }
        }
    }
    aliasEdit?.let { r -> TextInputDialog("Sửa tên ảo", "Tên ảo", r.aliasName, "Lưu", onDismiss = { aliasEdit = null }) { vm.editAlias(r.id, it); aliasEdit = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AllTab(vm: MainViewModel) {
    val items = vm.allPaging.collectAsLazyPagingItems()
    val q by vm.query.collectAsStateWithLifecycle()
    val f by vm.filter.collectAsStateWithLifecycle()
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val hasRoot by vm.hasAnyRoot.collectAsStateWithLifecycle()
    val progress by vm.sources.progress.collectAsStateWithLifecycle()
    var confirmImport by remember { mutableStateOf(false) }
    val running = progress.values.firstOrNull { it.running }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { vm.query.value = it }, singleLine = true,
            placeholder = { Text("Tìm theo tên ảo hoặc tag") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Xóa") } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Filter.entries.forEach {
                FilterChip(
                    selected = f == it, onClick = { vm.filter.value = it },
                    label = { Text(when (it) { Filter.ALL -> "Tất cả"; Filter.VIDEO -> "Video"; Filter.IMAGE -> "Ảnh"; Filter.UNTAGGED -> "Chưa gắn tag" }, maxLines = 1) }
                )
            }
            if (admin && pending > 0) AssistChip(onClick = { confirmImport = true }, label = { Text("Có $pending mục mới", maxLines = 1) }, leadingIcon = { Icon(Icons.Default.Add, null) })
        }
        if (admin && running != null) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Đang đồng bộ… ${running.dirs} thư mục ${running.mode}", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.weight(1f)) {
            if (!hasRoot) EmptyState(
                Icons.Default.Usb, "Chưa có nội dung",
                if (admin) "Cài đặt → Thêm nguồn (USB/thẻ nhớ)" else "Mở khóa Admin (biểu tượng ổ khóa) để thêm nguồn"
            )
            else MediaGrid(vm, items, admin, { vm.openFromAll(it) }, if (q.isNotBlank()) "Không có kết quả" else "Chưa có nội dung")
        }
    }
    if (confirmImport) ConfirmDialog("Nhập mục mới", "Nhập $pending mục mới vào danh sách?", "Nhập", { confirmImport = false }) {
        vm.importAll(); confirmImport = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavTab(vm: MainViewModel) {
    val items = vm.favPaging.collectAsLazyPagingItems()
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { vm.playFavorites(false) }, enabled = items.itemCount > 0) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Phát tất cả")
            }
            OutlinedButton(onClick = { vm.playFavorites(true) }, enabled = items.itemCount > 0) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Phát ngẫu nhiên")
            }
        }
        Box(Modifier.weight(1f)) { MediaGrid(vm, items, admin, { vm.openFromFav(it) }, "Chưa có mục yêu thích") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistTab(vm: MainViewModel) {
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    val sel by vm.selTags.collectAsStateWithLifecycle()
    val all by vm.matchAll.collectAsStateWithLifecycle()
    val pls by vm.playlists.collectAsStateWithLifecycle()
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    var saveDlg by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<PlaylistUi?>(null) }
    var delete by remember { mutableStateOf<PlaylistUi?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tag trong thư viện", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { manage = true }) { Text("Quản lý / thêm tag") }
                }
                if (tags.isEmpty()) {
                    Text("Chưa có tag. Mở Quản lý / thêm tag để tạo danh sách.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { t ->
                            FilterChip(
                                selected = t.id in sel,
                                onClick = { vm.selTags.value = if (t.id in sel) sel - t.id else sel + t.id },
                                label = { Text("${t.name} (${t.cnt})") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !all, onClick = { vm.matchAll.value = false }, label = { Text("Một trong các tag") })
                        FilterChip(selected = all, onClick = { vm.matchAll.value = true }, label = { Text("Có tất cả tag") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { vm.playTags(false) }, enabled = sel.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Phát") }
                        OutlinedButton(onClick = { vm.playTags(true) }, enabled = sel.isNotEmpty()) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Ngẫu nhiên") }
                        OutlinedButton(onClick = { saveDlg = true }, enabled = sel.isNotEmpty()) { Text("Lưu thành playlist") }
                    }
                }
            }
        }
        if (pls.isEmpty()) item { Text("Chưa có playlist", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pls.isNotEmpty()) {
            item { Text("Playlist", style = MaterialTheme.typography.titleMedium) }
            items(pls, key = { it.p.id }) { p ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.p.name, style = MaterialTheme.typography.titleSmall)
                            Text("${p.count} mục", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { vm.playPlaylist(p.p) }) { Icon(Icons.Default.PlayArrow, "Phát") }
                        IconButton(onClick = { vm.playPlaylist(p.p, true) }) { Icon(Icons.Default.Shuffle, "Ngẫu nhiên") }
                        IconButton(onClick = { rename = p }) { Icon(Icons.Default.Edit, "Đổi tên") }
                        IconButton(onClick = { delete = p }) { Icon(Icons.Default.Delete, "Xóa") }
                    }
                }
            }
        }
    }
    if (saveDlg) TextInputDialog("Lưu thành playlist", "Tên playlist", "", "Lưu", onDismiss = { saveDlg = false }) { vm.savePlaylistFromTags(it); saveDlg = false }
    if (manage) TagManageDialog(vm, admin) { manage = false }
    rename?.let { p -> TextInputDialog("Đổi tên playlist", "Tên mới", p.p.name, "Lưu", onDismiss = { rename = null }) { vm.renamePlaylist(p.p.id, it); rename = null } }
    delete?.let { p -> ConfirmDialog("Xóa playlist", "Xóa playlist \"${p.p.name}\"? (Không xóa video/ảnh)", "Xóa", { delete = null }) { vm.deletePlaylist(p.p.id); delete = null } }
}
