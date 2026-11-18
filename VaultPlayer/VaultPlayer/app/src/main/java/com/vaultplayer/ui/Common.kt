package com.vaultplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vaultplayer.DbState
import com.vaultplayer.Vault

@Composable
fun EmptyState(icon: ImageVector, text: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Ô nhập tag/alias: tắt tự sửa. Giới hạn: Compose không đặt được cờ no-personalized-learning cho IME;
 * không dùng kiểu Password vì sẽ làm hỏng gõ tiếng Việt có dấu. Bàn phím bên thứ ba vẫn có thể tự học.
 */
@Composable
fun TextInputDialog(
    title: String, label: String, initial: String = "", confirmLabel: String = "OK",
    secret: Boolean = false, numeric: Boolean = false, error: String? = null,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true,
                isError = error != null,
                supportingText = if (error != null) ({ Text(error) }) else null,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.NumberPassword else if (secret) KeyboardType.Password else KeyboardType.Text,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(v) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Đồng ý", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

/** Xác nhận bằng cách gõ đúng một từ (ví dụ "XOA"). */
@Composable
fun TypeConfirmDialog(title: String, text: String, word: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = t, onValueChange = { t = it }, singleLine = true, label = { Text("Gõ $word để xác nhận") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = t.trim().equals(word, ignoreCase = true), onClick = onConfirm) { Text("Xác nhận") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

/** Bắt buộc đặt PIN lần chạy đầu (không đóng được, không cho giữ 1234). Dẫn xuất khóa chạy ở luồng nền. */
@Composable
fun SetPinDialog(onSet: (String) -> Boolean, onDone: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Đặt mã PIN Admin") },
        text = {
            Column {
                Text("PIN tối thiểu 4 chữ số, không được dùng 1234.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(a, { a = it.filter(Char::isDigit).take(12) }, label = { Text("PIN mới") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(b, { b = it.filter(Char::isDigit).take(12) }, label = { Text("Nhập lại PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = err != null, supportingText = err?.let { m -> ({ Text(m) }) },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (a != b) {
                    err = "Hai lần nhập không khớp"
                } else {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { onSet(a) }
                        busy = false
                        if (ok) onDone() else err = "PIN không hợp lệ (≥ 4 số, không phải 1234)"
                    }
                }
            }) { Text(if (busy) "Đang lưu…" else "Lưu") }
        }
    )
}

@Composable
fun AdminUnlockDialog(vault: Vault, canBio: Boolean, onBio: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mở khóa Admin") },
        text = {
            Column {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = err != null, supportingText = err?.let { m -> ({ Text(m) }) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (canBio) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { onBio { vault.admin.unlock(); onDismiss() } }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Dùng vân tay")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val lock = vault.pin.lockRemainingMs()
                if (lock > 0) {
                    err = "Tạm khóa, thử lại sau ${(lock / 1000) + 1} giây"
                } else {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { vault.pin.verify(pin) }   // PBKDF2 không chạy trên luồng chính
                        busy = false
                        if (ok) { vault.admin.unlock(); onDismiss() }
                        else err = if (vault.pin.lockRemainingMs() > 0) "Nhập sai nhiều lần, tạm khóa" else "PIN sai"
                    }
                }
            }) { Text(if (busy) "Đang kiểm tra…" else "Mở khóa") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
fun RootContent(vault: Vault, canBio: Boolean, onBio: (() -> Unit) -> Unit) {
    val st by vault.state.collectAsStateWithLifecycle()
    when (st) {
        is DbState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is DbState.Error -> DbErrorScreen(vault)
        is DbState.Ready -> {
            val vm: MainViewModel = viewModel()
            MainScreen(vm, canBio, onBio)
        }
    }
}

@Composable
fun DbErrorScreen(vault: Vault) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("Không mở được dữ liệu", style = MaterialTheme.typography.titleMedium)
        Text("Dữ liệu được giữ nguyên, chưa bị xóa.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vault.state.value = DbState.Loading; vault.start() }) { Text("Thử lại") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirm = true }) { Text("Tạo mới (xóa file hỏng)") }
    }
    if (confirm) TypeConfirmDialog(
        "Tạo cơ sở dữ liệu mới", "Dữ liệu hiện tại sẽ bị xóa. Nếu có backup trên USB/thẻ, bạn có thể khôi phục sau.",
        "XOA", { confirm = false }) { vault.recreateAfterError() }
}
