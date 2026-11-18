package com.vaultplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
private fun dialogInputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
)

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

@Composable
fun DialogBackTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(38.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Quay lại",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2
        )
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
    var v by remember(title, initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBackTitle(title, onDismiss) },
        text = {
            OutlinedTextField(
                value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true,
                isError = error != null,
                supportingText = if (error != null) ({ Text(error) }) else null,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.NumberPassword else if (secret) KeyboardType.Password else KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(v) }),
                shape = RoundedCornerShape(16.dp),
                colors = dialogInputColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(v) },
                shape = RoundedCornerShape(14.dp)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Đồng ý", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBackTitle(title, onDismiss) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm, shape = RoundedCornerShape(14.dp)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    )
}

/** Xác nhận bằng cách gõ đúng một từ (ví dụ "XOA"). */
@Composable
fun TypeConfirmDialog(title: String, text: String, word: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBackTitle(title, onDismiss) },
        text = {
            Column {
                Text(text)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = t, onValueChange = { t = it }, singleLine = true, label = { Text("Gõ $word để xác nhận") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    shape = RoundedCornerShape(16.dp),
                    colors = dialogInputColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(enabled = t.trim().equals(word, ignoreCase = true), onClick = onConfirm, shape = RoundedCornerShape(14.dp)) { Text("Xác nhận") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.Lock, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text("Đặt mã PIN Admin", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column {
                Text("PIN tối thiểu 4 chữ số, không được dùng 1234.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(a, { a = it.filter(Char::isDigit).take(12) }, label = { Text("PIN mới") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(16.dp), colors = dialogInputColors(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(b, { b = it.filter(Char::isDigit).take(12) }, label = { Text("Nhập lại PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(16.dp), colors = dialogInputColors(),
                    isError = err != null, supportingText = err?.let { m -> ({ Text(m) }) },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
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
            }, shape = RoundedCornerShape(14.dp)) { Text(if (busy) "Đang lưu…" else "Lưu") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    )
}

@Composable
fun AdminUnlockDialog(vault: Vault, canBio: Boolean, onBio: (() -> Unit) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pinFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        pinFocus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogBackTitle("Mở khóa Admin", onDismiss) },
        text = {
            Column {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(16.dp),
                    colors = dialogInputColors(),
                    isError = err != null, supportingText = err?.let { m -> ({ Text(m) }) },
                    modifier = Modifier.fillMaxWidth().focusRequester(pinFocus)
                )
                if (canBio) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onBio { vault.admin.unlock(); onDismiss() } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Dùng vân tay")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
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
            }, shape = RoundedCornerShape(14.dp)) { Text(if (busy) "Đang kiểm tra…" else "Mở khóa") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
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
