package com.vaultplayer.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.vaultplayer.data.SourceRoot
import java.io.FileNotFoundException
import java.io.IOException

data class ChildDoc(val docId: String, val name: String, val mime: String, val size: Long, val lastMod: Long) {
    val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
}

object DocUtil {
    private val COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    )

    private fun row(c: android.database.Cursor) = ChildDoc(
        c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "",
        if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)
    )

    fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)
    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** Liệt kê một thư mục bằng MỘT truy vấn (nhanh hơn DocumentFile.listFiles). Ném lỗi nếu không đọc được. */
    fun list(cr: ContentResolver, tree: Uri, parentDocId: String): List<ChildDoc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val c = cr.query(uri, COLS, null, null, null) ?: throw IOException("null cursor")
        val out = ArrayList<ChildDoc>()
        c.use { while (it.moveToNext()) out.add(row(it)) }
        return out
    }

    /** Metadata của chính một tài liệu/thư mục (một dòng). Trả null nếu không tồn tại. */
    fun meta(cr: ContentResolver, tree: Uri, docId: String): ChildDoc? {
        val c = cr.query(docUri(tree, docId), COLS, null, null, null) ?: throw IOException("null cursor")
        c.use { return if (it.moveToFirst()) row(it) else null }
    }

    fun open(cr: ContentResolver, uri: Uri, mode: String = "r"): ParcelFileDescriptor? =
        try { cr.openFileDescriptor(uri, mode) } catch (e: Exception) { null }

    fun isGone(e: Exception) = e is FileNotFoundException || e is IllegalArgumentException
}

/** Thao tác file ở thư mục gốc của nguồn (chỉ dùng cho file backup ".vp_*" và gói thumbnail). */
object RootFiles {
    private fun treeOf(root: SourceRoot): Uri = Uri.parse(root.treeUri)
    private fun rootUri(root: SourceRoot): Uri = DocUtil.docUri(treeOf(root), DocUtil.rootDocId(treeOf(root)))

    fun listRoot(cr: ContentResolver, root: SourceRoot): List<ChildDoc> =
        try { DocUtil.list(cr, treeOf(root), DocUtil.rootDocId(treeOf(root))) } catch (e: Exception) { emptyList() }

    fun uriOf(root: SourceRoot, c: ChildDoc): Uri = DocUtil.docUri(treeOf(root), c.docId)

    fun find(cr: ContentResolver, root: SourceRoot, name: String): Uri? =
        listRoot(cr, root).firstOrNull { !it.isDir && it.name == name }?.let { uriOf(root, it) }

    fun create(cr: ContentResolver, root: SourceRoot, name: String): Uri? =
        try { DocumentsContract.createDocument(cr, rootUri(root), "application/octet-stream", name) } catch (e: Exception) { null }

    fun delete(cr: ContentResolver, uri: Uri): Boolean =
        try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }

    fun rename(cr: ContentResolver, uri: Uri, newName: String): Uri? =
        try { DocumentsContract.renameDocument(cr, uri, newName) } catch (e: Exception) { null }

    fun writeAll(cr: ContentResolver, uri: Uri, bytes: ByteArray): Boolean = try {
        cr.openFileDescriptor(uri, "wt")?.use { pfd ->
            java.io.FileOutputStream(pfd.fileDescriptor).use { os ->
                os.write(bytes); os.flush(); pfd.fileDescriptor.sync()
            }
            true
        } ?: false
    } catch (e: Exception) { false }

    fun readAll(cr: ContentResolver, uri: Uri): ByteArray? =
        try { cr.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }

    /** Dung lượng trống của volume (null nếu không biết). */
    fun availableBytes(cr: ContentResolver, root: SourceRoot): Long? {
        return try {
            val auth = treeOf(root).authority ?: return null
            val uri = DocumentsContract.buildRootsUri(auth)
            val c = cr.query(
                uri,
                arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
                null, null, null
            ) ?: return null
            c.use {
                while (it.moveToNext()) {
                    if (it.getString(0) == root.volumeId) return if (it.isNull(1)) null else it.getLong(1)
                }
            }
            null
        } catch (e: Exception) { null }
    }
}
