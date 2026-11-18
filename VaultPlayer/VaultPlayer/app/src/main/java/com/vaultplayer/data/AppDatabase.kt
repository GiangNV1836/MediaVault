package com.vaultplayer.data

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vaultplayer.security.KeystoreAes
import com.vaultplayer.util.hex
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

@Database(
    entities = [
        SourceRoot::class, MediaEntry::class, PendingEntry::class, DirState::class, Tag::class, MediaTag::class,
        Playlist::class, PlaylistItem::class, DeviceInfo::class, Counter::class, ThumbIndex::class,
        PackInfo::class, RestoreStaging::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao
}

object DbKey {
    private const val FILE = "vp_keys"

    /** Khóa thô 256-bit dạng x'hex' (bỏ qua bước dẫn xuất khóa chậm của SQLCipher), bọc bằng Keystore. */
    fun get(ctx: Context): ByteArray {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = sp.getString("db", null)
        val raw: ByteArray
        if (stored == null) {
            raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val wrapped = Base64.encodeToString(KeystoreAes.encrypt(KeystoreAes.MASTER, raw), Base64.NO_WRAP)
            sp.edit().putString("db", wrapped).commit()
        } else {
            raw = KeystoreAes.decrypt(KeystoreAes.MASTER, Base64.decode(stored, Base64.NO_WRAP))
        }
        return ("x'" + hex(raw) + "'").toByteArray()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

fun buildDb(ctx: Context): AppDatabase {
    System.loadLibrary("sqlcipher")
    val factory = SupportOpenHelperFactory(DbKey.get(ctx))
    return Room.databaseBuilder(ctx, AppDatabase::class.java, "vault.db")
        .openHelperFactory(factory)
        .build()
}
