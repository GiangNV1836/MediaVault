package com.vaultplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.vaultplayer.data.MediaRow

private val TAB_LABELS = listOf("Tất cả", "Tag", "Playlist", "Yêu thích")
private fun tabIcon(i: Int) = when (i) {
    0 -> Icons.Default.VideoLibrary
    1 -> Icons.AutoMirrored.Filled.Label
    2 -> Icons.AutoMirrored.Filled.PlaylistPlay
    else -> Icons.Default.Favorite
}

private enum class MediaLayout { GRID, LIST }

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
                    title = {                     Text("${sel.size} đã chọn", style = MaterialTheme.typography.titleLarge) },
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
                TAB_LABELS.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { vm.tab.value = i; vm.clearSelection() },
                        icon = { Icon(tabIcon(i), null) }, label = { Text(label, maxLines = 1) })
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
                    1 -> TagTab(vm)
                    2 -> PlaylistTab(vm)
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
    var layout by remember {
        mutableStateOf(if (vm.vault.prefs.mediaListMode) MediaLayout.LIST else MediaLayout.GRID)
    }

    val renderItem: @Composable (MediaRow) -> Unit = { row ->
        MediaCard(
            vm, row, admin, selectionMode = sel.isNotEmpty(), selected = row.id in sel,
            shown = row.id in shown, autoShow = autoShow, listMode = layout == MediaLayout.LIST,
            onOpen = { onOpen(row.id) },
            onFavorite = { vm.toggleFavorite(row.id, row.isFavorite) },
            onEditAlias = { aliasEdit = row }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Bố cục",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = {
                            layout = MediaLayout.GRID
                            vm.vault.prefs.mediaListMode = false
                        },
                        shape = RoundedCornerShape(11.dp),
                        color = if (layout == MediaLayout.GRID) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.size(width = 44.dp, height = 38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.GridView,
                                "Hiển thị dạng lưới",
                                tint = if (layout == MediaLayout.GRID) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            layout = MediaLayout.LIST
                            vm.vault.prefs.mediaListMode = true
                        },
                        shape = RoundedCornerShape(11.dp),
                        color = if (layout == MediaLayout.LIST) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.size(width = 44.dp, height = 38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ViewList,
                                "Hiển thị dạng danh sách",
                                tint = if (layout == MediaLayout.LIST) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
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
            if (layout == MediaLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) {
                        val row = items[it]
                        if (row != null) renderItem(row)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) {
                        val row = items[it]
                        if (row != null) renderItem(row)
                    }
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
fun TagTab(vm: MainViewModel) {
    val tags by vm.tagCounts.collectAsStateWithLifecycle()
    val sel by vm.selTags.collectAsStateWithLifecycle()
    val all by vm.matchAll.collectAsStateWithLifecycle()
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    var saveDlg by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Thư viện tag", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Chọn một hoặc nhiều tag để lọc nội dung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = { manage = true }) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(6.dp))
                Text("Quản lý")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (tags.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Filled.Label,
                "Chưa có tag",
                "Tạo tag để sắp xếp và lập playlist theo chủ đề.",
                Modifier.weight(1f)
            )
        } else {
            FlowRow(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in sel,
                        onClick = {
                            vm.selTags.value = if (tag.id in sel) sel - tag.id else sel + tag.id
                        },
                        label = { Text("${tag.name} · ${tag.cnt}") },
                        leadingIcon = if (tag.id in sel) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            if (sel.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("${sel.size} tag được chọn", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !all, onClick = { vm.matchAll.value = false }, label = { Text("Khớp bất kỳ") })
                            FilterChip(selected = all, onClick = { vm.matchAll.value = true }, label = { Text("Khớp tất cả") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.playTags(false) }) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Phát")
                            }
                            OutlinedButton(onClick = { vm.playTags(true) }) {
                                Icon(Icons.Default.Shuffle, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Ngẫu nhiên")
                            }
                            OutlinedButton(onClick = { saveDlg = true }) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Tạo playlist")
                            }
                        }
                    }
                }
            }
        }
    }

    if (saveDlg) {
        TextInputDialog(
            "Tạo playlist theo tag", "Tên playlist", "", "Tạo",
            onDismiss = { saveDlg = false }
        ) { vm.savePlaylistFromTags(it); saveDlg = false }
    }
    if (manage) TagManageDialog(vm, admin) { manage = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistTab(vm: MainViewModel) {
    val pls by vm.playlists.collectAsStateWithLifecycle()
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<PlaylistUi?>(null) }
    var delete by remember { mutableStateOf<PlaylistUi?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    val selectedPlaylist = pls.firstOrNull { it.p.id == selectedPlaylistId }
    LaunchedEffect(pls, selectedPlaylistId) {
        if (selectedPlaylistId != null && selectedPlaylist == null) selectedPlaylistId = null
    }
    BackHandler(enabled = selectedPlaylistId != null) { selectedPlaylistId = null }

    if (selectedPlaylist != null) {
        PlaylistContents(vm, selectedPlaylist, admin) { selectedPlaylistId = null }
    } else if (pls.isEmpty()) {
        EmptyState(
            Icons.AutoMirrored.Filled.PlaylistPlay,
            "Chưa có playlist",
            "Chọn video trong Tất cả rồi nhấn biểu tượng playlist để tạo playlist thủ công."
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("Playlist", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${pls.size} danh sách phát",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(pls, key = { it.p.id }) { playlist ->
                Surface(
                    onClick = { selectedPlaylistId = playlist.p.id },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                if (playlist.p.type == "TAG_FILTER") Icons.AutoMirrored.Filled.Label else Icons.AutoMirrored.Filled.PlaylistPlay,
                                null,
                                Modifier.padding(10.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                "${playlist.count} mục · ${if (playlist.p.type == "TAG_FILTER") "Theo tag" else "Tùy chỉnh"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { selectedPlaylistId = playlist.p.id }) {
                            Icon(Icons.Default.ChevronRight, "Xem nội dung playlist")
                        }
                        IconButton(onClick = { vm.playPlaylist(playlist.p) }) {
                            Icon(Icons.Default.PlayArrow, "Phát playlist", tint = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton(onClick = { menuFor = playlist.p.id }) { Icon(Icons.Default.MoreVert, "Tùy chọn") }
                            DropdownMenu(expanded = menuFor == playlist.p.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(
                                    text = { Text("Phát ngẫu nhiên") },
                                    leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                                    onClick = { vm.playPlaylist(playlist.p, true); menuFor = null }
                                )
                                DropdownMenuItem(
                                    text = { Text("Đổi tên") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { rename = playlist; menuFor = null }
                                )
                                DropdownMenuItem(
                                    text = { Text("Xóa playlist") },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { delete = playlist; menuFor = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    rename?.let { playlist ->
        TextInputDialog("Đổi tên playlist", "Tên mới", playlist.p.name, "Lưu", onDismiss = { rename = null }) {
            vm.renamePlaylist(playlist.p.id, it)
            rename = null
        }
    }
    delete?.let { playlist ->
        ConfirmDialog(
            "Xóa playlist",
            "Xóa playlist \"${playlist.p.name}\"? (Không xóa video/ảnh)",
            "Xóa",
            { delete = null }
        ) {
            vm.deletePlaylist(playlist.p.id)
            delete = null
        }
    }
}

@Composable
private fun PlaylistContents(
    vm: MainViewModel,
    playlist: PlaylistUi,
    admin: Boolean,
    onBack: () -> Unit
) {
    val pagingFlow = remember(playlist.p) { vm.playlistPaging(playlist.p) }
    val items = pagingFlow.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại playlist") }
            Column(Modifier.weight(1f)) {
                Text(playlist.p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(
                    "${playlist.count} mục · ${if (playlist.p.type == "TAG_FILTER") "Theo tag" else "Tùy chỉnh"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (maxWidth < 360.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.playPlaylist(playlist.p) },
                        enabled = playlist.count > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Phát tất cả")
                    }
                    OutlinedButton(
                        onClick = { vm.playPlaylist(playlist.p, true) },
                        enabled = playlist.count > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Ngẫu nhiên")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.playPlaylist(playlist.p) },
                        enabled = playlist.count > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Phát tất cả")
                    }
                    OutlinedButton(
                        onClick = { vm.playPlaylist(playlist.p, true) },
                        enabled = playlist.count > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Ngẫu nhiên")
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            MediaGrid(
                vm,
                items,
                admin,
                onOpen = { vm.openFromPlaylist(playlist.p, it) },
                emptyText = "Playlist chưa có mục nào"
            )
        }
    }
}
