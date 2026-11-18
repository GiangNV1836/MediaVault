package com.vaultplayer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaultplayer.backup.BackupFile
import com.vaultplayer.backup.PeekInfo
import com.vaultplayer.backup.RestoreMode
import com.vaultplayer.data.SourceRoot
import com.vaultplayer.storage.ChildDoc
import com.vaultplayer.storage.ScanOutcome
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Chọn thư mục gốc với quyền đọc + ghi + bền vững (cần ghi để lưu backup/gói thumbnail). */
private class OpenTreeRw : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun fmtTime(ms: Long): String = if (ms <= 0) "chưa có" else DateFormat.getDateTimeInstance().format(Date(ms))
private fun fmtSize(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, Int>>, current: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (l, v) -> FilterChip(selected = current == v, onClick = { onSelect(v) }, label = { Text(l) }) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val v = vm.vault
    val prefs = v.prefs
    val admin by vm.admin.isAdmin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showUnlock by remember { mutableStateOf(false) }

    // cài đặt chung
    var autoShow by remember { mutableStateOf(prefs.autoShowThumbs) }
    var cacheMb by remember { mutableIntStateOf(prefs.cacheMb) }
    var slide by remember { mutableIntStateOf(prefs.slideSec) }
    // Admin
    var idle by remember { mutableIntStateOf(prefs.adminIdleMin) }
    var packCap by remember { mutableIntStateOf(prefs.packCapMb) }
    var backupAuto by remember { mutableStateOf(prefs.backupAuto) }
    var pinDlg by remember { mutableStateOf(false) }
    var passDlg by remember { mutableStateOf(false) }
    var wipeDlg by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(OpenTreeRw()) { uri -> if (uri != null) vm.addRoot(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = { IconButton(onClick = { vm.screen.value = Screen.MAIN }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Section("Hiển thị") {
                    SwitchRow("Tự hiện hình xem trước", autoShow) { autoShow = it; prefs.autoShowThumbs = it }
                    ChoiceRow("Bộ nhớ đệm hình xem trước (RAM)", listOf("32 MB" to 32, "64 MB" to 64, "96 MB" to 96), cacheMb) {
                        cacheMb = it; prefs.cacheMb = it; v.thumbs.applyCacheSize()
                    }
                    ChoiceRow("Thời gian chuyển ảnh khi trình chiếu", listOf("3 giây" to 3, "5 giây" to 5, "10 giây" to 10, "Tắt" to 0), slide) {
                        slide = it; prefs.slideSec = it
                    }
                }

                if (!admin) {
                    Section("Quản trị") {
                        Text("Mở khóa Admin để thêm nguồn, quét, backup và các thiết lập bảo mật.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showUnlock = true }) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("Mở khóa Admin") }
                    }
                } else {
                    Section("Bảo mật") {
                        OutlinedButton(onClick = { pinDlg = true }) { Text("Đổi PIN Admin") }
                        ChoiceRow("Tự khóa Admin sau", listOf("1 phút" to 1, "2 phút" to 2, "5 phút" to 5), idle) { idle = it; prefs.adminIdleMin = it }
                    }
                    Section("Nguồn lưu trữ (USB / thẻ nhớ)") {
                        Button(onClick = { launcher.launch(Unit) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Thêm nguồn") }
                        Spacer(Modifier.height(8.dp))
                        SourcesSection(vm)
                    }
                    Section("Gói thumbnail trên nguồn") {
                        ChoiceRow("Dung lượng tối đa của gói", listOf("256 MB" to 256, "512 MB" to 512, "1 GB" to 1024), packCap) { packCap = it; prefs.packCapMb = it }
                        Text("Hình xem trước được lưu mã hóa trên chính USB/thẻ, không lưu trên điện thoại.", style = MaterialTheme.typography.bodySmall)
                    }
                    Section("Backup") {
                        BackupSection(vm, backupAuto, { backupAuto = it; prefs.backupAuto = it }, { passDlg = true })
                    }
                    Section("Dữ liệu") {
                        Button(
                            onClick = { wipeDlg = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Xóa dữ liệu ứng dụng") }
                        Text("Không đụng backup trên USB/thẻ. Ứng dụng sẽ đóng sau khi xóa.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showUnlock) AdminUnlockDialog(v, false, {}) { showUnlock = false }
    if (pinDlg) TextInputDialog("Đổi PIN Admin", "PIN mới (≥ 4 số, khác 1234)", "", "Lưu", secret = true, numeric = true, onDismiss = { pinDlg = false }) {
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { v.pin.set(it) }
            vm.toast(if (ok) "Đã đổi PIN" else "PIN không hợp lệ"); pinDlg = false
        }
    }
    if (passDlg) PasswordSetDialog({ passDlg = false }) { p ->
        scope.launch {
            if (v.backup.setPassword(p.toCharArray())) { vm.toast("Đã đặt mật khẩu backup. Hãy ghi nhớ: mất mật khẩu = không khôi phục được"); passDlg = false }
            else vm.toast("Mật khẩu tối thiểu 8 ký tự")
        }
    }
    if (wipeDlg) TypeConfirmDialog(
        "Xóa dữ liệu ứng dụng", "Xóa toàn bộ dữ liệu trong máy (tên ảo, tag, playlist, khóa mã hóa, quyền thư mục). File trên USB/thẻ và backup không bị đụng.",
        "XOA", { wipeDlg = false }
    ) { scope.launch { v.wipeAll() } }
}

@Composable
private fun PasswordSetDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBackTitle("Mật khẩu backup", onDismiss) },
        text = {
            Column {
                Text("Tối thiểu 8 ký tự, riêng với PIN Admin. Mất mật khẩu = không khôi phục được backup.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(a, { a = it }, label = { Text("Mật khẩu") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(b, { b = it }, label = { Text("Nhập lại") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = a.length >= 8 && a == b, onClick = { onSet(a) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesSection(vm: MainViewModel) {
    val roots by vm.sources.roots.collectAsStateWithLifecycle()
    val avail by vm.sources.availableRoots.collectAsStateWithLifecycle()
    val progress by vm.sources.progress.collectAsStateWithLifecycle()
    val outcomes by vm.sources.outcomes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var cleanup by remember { mutableStateOf<SourceRoot?>(null) }
    var remove by remember { mutableStateOf<SourceRoot?>(null) }
    var packRoot by remember { mutableStateOf<SourceRoot?>(null) }
    var packs by remember { mutableStateOf<List<ChildDoc>>(emptyList()) }
    var delPack by remember { mutableStateOf<ChildDoc?>(null) }

    if (roots.isEmpty()) Text("Chưa có nguồn nào.", style = MaterialTheme.typography.bodySmall)
    roots.forEach { r ->
        val on = r.id in avail
        val missing by produceState(0, r.id, r.lastScanAt, r.lastFileCount) { value = vm.dao.countMissing(r.id) }
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Usb, null)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.sources.label(r.id), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (on) "đang kết nối" else "không kết nối", style = MaterialTheme.typography.labelSmall,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
                // Khi nguồn không khả dụng chỉ hiện SỐ LƯỢNG.
                Text("${r.lastFileCount} mục · ${r.pendingNewCount} chờ nhập · $missing mất file", style = MaterialTheme.typography.bodySmall)
                if (r.lastScanAt > 0) Text(
                    "Lần quét gần nhất: ${r.lastFileCount} tệp, ${r.lastDirCount} thư mục, %.1f giây, chế độ ${r.lastScanMode}".format(r.lastScanMs / 1000.0),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                )
                progress[r.id]?.takeIf { it.running }?.let {
                    Text("Đang xử lý… ${it.dirs} (${it.mode})", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (outcomes[r.id] is ScanOutcome.Failed) Text("Quét không áp dụng được (không đọc được nội dung nguồn).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = on, onClick = { vm.sources.scanNow(r.id, false) }) { Text("Quét nhanh") }
                    OutlinedButton(enabled = on, onClick = { vm.sources.scanNow(r.id, true) }) { Text("Quét đầy đủ") }
                    OutlinedButton(enabled = on && r.pendingNewCount > 0, onClick = { vm.sources.importNow(r.id) }) { Text("Nhập ${r.pendingNewCount} mục mới") }
                    OutlinedButton(enabled = on && missing > 0, onClick = { cleanup = r }) { Text("Dọn liên kết cũ ($missing)") }
                    OutlinedButton(enabled = on, onClick = {
                        scope.launch { packs = vm.vault.packs.otherPacks(r); packRoot = r }
                    }) { Text("Gói thumbnail…") }
                    OutlinedButton(onClick = { remove = r }) { Text("Xóa nguồn") }
                }
            }
        }
    }
    cleanup?.let { r -> ConfirmDialog("Dọn liên kết cũ", "Xóa hẳn các mục không còn file (tên ảo, tag, playlist liên quan)?", "Dọn", { cleanup = null }) {
        scope.launch { val n = vm.vault.cleanup(r.id); vm.toast("Đã dọn $n mục") }; cleanup = null
    } }
    remove?.let { r -> ConfirmDialog("Xóa nguồn", "Xóa nguồn và toàn bộ dữ liệu (tên ảo, tag, playlist) của các mục thuộc nguồn này? File trên USB/thẻ không bị đụng.", "Xóa", { remove = null }) {
        scope.launch { vm.sources.removeRoot(r.id) }; remove = null
    } }
    packRoot?.let { r ->
        AlertDialog(
            onDismissRequest = { packRoot = null },
            title = { DialogBackTitle("Gói thumbnail", { packRoot = null }) },
            text = {
                Column {
                    OutlinedButton(onClick = { scope.launch { vm.vault.packs.deleteOwn(r); vm.toast("Đã xóa gói thumbnail của nguồn này") }; packRoot = null }) { Text("Xóa gói của thiết bị này") }
                    Spacer(Modifier.height(8.dp))
                    if (packs.isEmpty()) Text("Không có gói không dùng.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text("Gói không dùng (thiết bị khác hoặc cũ):", style = MaterialTheme.typography.bodySmall)
                        packs.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${fmtSize(c.size)}", Modifier.weight(1f))
                                IconButton(onClick = { delPack = c }) { Icon(Icons.Default.Delete, "Xóa") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { packRoot = null }) { Text("Đóng") } }
        )
        delPack?.let { c -> ConfirmDialog("Xóa gói không dùng", "Xóa file gói thumbnail (${fmtSize(c.size)})?", "Xóa", { delPack = null }) {
            vm.vault.packs.deleteDoc(r, c); packs = packs - c; delPack = null
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupSection(vm: MainViewModel, auto: Boolean, onAuto: (Boolean) -> Unit, onPassword: () -> Unit) {
    val b = vm.vault.backup
    val status by b.status.collectAsStateWithLifecycle()
    val warnings by b.warnings.collectAsStateWithLifecycle()
    val ownNewer by b.ownNewer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var list by remember { mutableStateOf<List<BackupFile>?>(null) }
    var forDelete by remember { mutableStateOf(false) }
    var delFile by remember { mutableStateOf<BackupFile?>(null) }
    var pickFile by remember { mutableStateOf<BackupFile?>(null) }
    var ctx by remember { mutableStateOf<Triple<BackupFile, ByteArray, String>?>(null) }
    var info by remember { mutableStateOf<PeekInfo?>(null) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    SwitchRow("Tự động backup (5 phút khi có thay đổi)", auto, onAuto)
    Text("Lần backup gần nhất: ${fmtTime(status.lastAt)}", style = MaterialTheme.typography.bodySmall)
    warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (ownNewer) {
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Bản backup của máy này trên nguồn mới hơn dữ liệu trong máy.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { list = b.listBackups().filter { it.own }; forDelete = false } }) { Text("Khôi phục") }
                    OutlinedButton(onClick = { scope.launch { b.ackOwnNewer() } }) { Text("Giữ bản trong máy") }
                    TextButton(onClick = { b.ownNewer.value = false }) { Text("Bỏ qua") }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPassword) { Text(if (vm.vault.prefs.backupPassSet) "Đổi mật khẩu backup" else "Đặt mật khẩu backup") }
        Button(enabled = !status.running && vm.vault.prefs.backupPassSet, onClick = {
            scope.launch { vm.toast(if (b.runBackup(true)) "Đã backup" else "Backup chưa thực hiện được") }
        }) { Text(if (status.running) "Đang backup…" else "Backup ngay") }
        OutlinedButton(onClick = { scope.launch { list = b.listBackups(); forDelete = false } }) { Text("Khôi phục…") }
        OutlinedButton(onClick = { scope.launch { list = b.listBackups(); forDelete = true } }) { Text("Xóa backup trên nguồn…") }
    }

    // 1) danh sách file backup
    list?.let { l ->
        AlertDialog(
            onDismissRequest = { list = null },
            title = { DialogBackTitle(if (forDelete) "Chọn backup để xóa" else "Chọn backup để khôi phục") { list = null } },
            text = {
                if (l.isEmpty()) Text("Không tìm thấy backup trên các nguồn đang kết nối.")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(l, key = { it.uri.toString() }) { f ->
                        ListItem(
                            headlineContent = { Text(if (f.own) "Thiết bị này (${f.kind})" else "Thiết bị khác (${f.kind})") },
                            supportingContent = { Text("${fmtTime(f.lastModified)} · ${fmtSize(f.size)} · ${vm.sources.label(f.rootId)}") },
                            trailingContent = {
                                TextButton(onClick = { if (forDelete) delFile = f else pickFile = f; list = null }) { Text(if (forDelete) "Xóa" else "Chọn") }
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { list = null }) { Text("Đóng") } }
        )
    }
    delFile?.let { f -> TypeConfirmDialog("Xóa backup trên nguồn", "Chỉ xóa file backup của ứng dụng đã chọn. Không thể hoàn tác.", "XOA BACKUP", { delFile = null }) {
        scope.launch { vm.toast(if (b.deleteBackup(f)) "Đã xóa backup" else "Không xóa được"); delFile = null }
    } }

    // 2) nhập mật khẩu
    pickFile?.let { f -> TextInputDialog("Mật khẩu backup", "Mật khẩu", "", "Tiếp tục", secret = true, onDismiss = { pickFile = null }) { pass ->
        scope.launch {
            val bytes = b.readFile(f)
            val pi = if (bytes != null) b.peek(bytes, pass.toCharArray()) else null
            if (bytes == null || pi == null) vm.toast("Sai mật khẩu hoặc file không hợp lệ")
            else { ctx = Triple(f, bytes, pass); info = pi }
            pickFile = null
        }
    } }

    // 3) chọn kiểu khôi phục
    val c = ctx
    val pi = info
    if (c != null && pi != null) {
        AlertDialog(
            onDismissRequest = { ctx = null; info = null },
            title = { DialogBackTitle("Khôi phục backup") { ctx = null; info = null } },
            text = {
                Column {
                    Text("Thiết bị: ${pi.label}")
                    Text("Tạo lúc: ${fmtTime(pi.createdAt)}")
                    Spacer(Modifier.height(8.dp))
                    if (busy) { Text("Đang khôi phục…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val ok = b.restore(c.second, c.third.toCharArray(), RestoreMode.MERGE, false)
                        vm.toast(if (ok) "Đã nhập dữ liệu (gộp)" else "Khôi phục thất bại")
                        busy = false; ctx = null; info = null
                    }
                }) { Text("Nhập dữ liệu (Gộp)") }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !busy, onClick = { replaceConfirm = true }) { Text("Thay thế thiết bị cũ") }
                    TextButton(onClick = { ctx = null; info = null }) { Text("Hủy") }
                }
            }
        )
        if (replaceConfirm) TypeConfirmDialog(
            "Thay thế thiết bị cũ",
            "Thay toàn bộ tag/playlist/yêu thích bằng bản backup và nhận lại mã thiết bị của backup. Chỉ dùng khi máy cũ không còn dùng nữa.",
            "XAC NHAN", { replaceConfirm = false }
        ) {
            replaceConfirm = false
            busy = true
            scope.launch {
                val ok = b.restore(c.second, c.third.toCharArray(), RestoreMode.REPLACE, true)
                vm.toast(if (ok) "Đã khôi phục (thay thế)" else "Khôi phục thất bại")
                busy = false; ctx = null; info = null
            }
        }
    }
}
