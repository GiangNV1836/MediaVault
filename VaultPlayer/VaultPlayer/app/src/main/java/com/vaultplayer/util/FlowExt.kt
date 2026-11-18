package com.vaultplayer.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import java.util.concurrent.Executors

/** Phát giá trị đầu ngay, sau đó tối đa một lần mỗi [ms] (giữ giá trị mới nhất). Chống tính lại dồn dập khi nhập/quét hàng loạt. */
fun <T> Flow<T>.throttleLatest(ms: Long): Flow<T> = conflate().transform { emit(it); delay(ms) }

/** Luồng nền độ ưu tiên thấp cho quét, nhập, backup, ghi gói thumbnail (không tranh CPU với giao diện/phát video). */
object LowPrio {
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "vp-bg")
    }.asCoroutineDispatcher()
}
