package com.vaultplayer.util

import android.content.Context

/** Cài đặt nhẹ. KHÔNG chứa thông tin xem nội dung. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vp_prefs", Context.MODE_PRIVATE)

    var autoShowThumbs: Boolean
        get() = sp.getBoolean("autoShow", false)
        set(v) { sp.edit().putBoolean("autoShow", v).apply() }
    var gridMinCellDp: Int
        get() = sp.getInt("gridMinCellDp", 180).coerceIn(140, 300)
        set(v) { sp.edit().putInt("gridMinCellDp", v.coerceIn(140, 300)).apply() }
    var mediaListMode: Boolean
        get() = sp.getBoolean("mediaListMode", false)
        set(v) { sp.edit().putBoolean("mediaListMode", v).apply() }
    var cacheMb: Int
        get() = sp.getInt("cacheMb", 32)
        set(v) { sp.edit().putInt("cacheMb", v).apply() }
    var slideSec: Int
        get() = sp.getInt("slideSec", 5)
        set(v) { sp.edit().putInt("slideSec", v).apply() }
    var adminIdleMin: Int
        get() = sp.getInt("adminIdleMin", 2)
        set(v) { sp.edit().putInt("adminIdleMin", v).apply() }
    var packCapMb: Int
        get() = sp.getInt("packCapMb", 512)
        set(v) { sp.edit().putInt("packCapMb", v).apply() }
    var backupAuto: Boolean
        get() = sp.getBoolean("backupAuto", true)
        set(v) { sp.edit().putBoolean("backupAuto", v).apply() }
    var backupPassSet: Boolean
        get() = sp.getBoolean("backupPassSet", false)
        set(v) { sp.edit().putBoolean("backupPassSet", v).apply() }
    var backupSalt: String
        get() = sp.getString("backupSalt", "") ?: ""
        set(v) { sp.edit().putString("backupSalt", v).apply() }
    var backupKeyWrapped: String
        get() = sp.getString("backupKeyWrapped", "") ?: ""
        set(v) { sp.edit().putString("backupKeyWrapped", v).apply() }
    var lastBackupAt: Long
        get() = sp.getLong("lastBackupAt", 0L)
        set(v) { sp.edit().putLong("lastBackupAt", v).apply() }
    var lastChangeAt: Long
        get() = sp.getLong("lastChangeAt", 0L)
        set(v) { sp.edit().putLong("lastChangeAt", v).apply() }
    var lastBackupSize: Long
        get() = sp.getLong("lastBackupSize", 0L)
        set(v) { sp.edit().putLong("lastBackupSize", v).apply() }
    var pinSalt: String
        get() = sp.getString("pinSalt", "") ?: ""
        set(v) { sp.edit().putString("pinSalt", v).apply() }
    var pinHash: String
        get() = sp.getString("pinHash", "") ?: ""
        set(v) { sp.edit().putString("pinHash", v).apply() }
    var pinFails: Int
        get() = sp.getInt("pinFails", 0)
        set(v) { sp.edit().putInt("pinFails", v).apply() }
    var pinLockUntil: Long
        get() = sp.getLong("pinLockUntil", 0L)
        set(v) { sp.edit().putLong("pinLockUntil", v).apply() }

    var backupAckedMtime: Long
        get() = sp.getLong("backupAckedMtime", 0L)
        set(v) { sp.edit().putLong("backupAckedMtime", v).apply() }

    fun clearAll() { sp.edit().clear().commit() }
}
