package io.github.perfecticons.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import io.github.perfecticons.R
import io.github.perfecticons.data.custom.CustomizationStore
import io.github.perfecticons.data.repo.RepoPaths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes the customization directory through the Storage Access Framework.
 *
 * Users with an external file manager can keep working with the same real
 * directory tree the app edits, and the app re-scans on resume so external
 * changes are picked up without any synchronization layer.
 */
class CustomizationDocumentsProvider : DocumentsProvider() {

    private lateinit var store: CustomizationStore

    override fun onCreate(): Boolean {
        store = CustomizationStore(RepoPaths.customRoot(requireNotNull(context)))
        store.ensureLayout()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveRootProjection(projection))
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(Root.COLUMN_TITLE, context?.getString(R.string.saf_root_title))
            add(Root.COLUMN_SUMMARY, context?.getString(R.string.saf_root_summary))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, store.root.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        includeFile(cursor, documentId, resolveFile(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val parent = resolveFile(parentDocumentId)
        val children = parent.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        children.forEach { child ->
            val id = buildDocumentId(parentDocumentId, child.name)
            if (isHidden(child)) return@forEach
            includeFile(cursor, id, child)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { resolveFile(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { resolveFile(documentId) }.getOrNull() ?: return false
        return child.canonicalPath.startsWith(parent.canonicalPath + File.separator)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveFile(documentId)
        if (file.isDirectory) throw FileNotFoundException("目录不能直接打开")
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolveFile(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("父节点不是目录")
        val safeName = sanitizeName(displayName)
        val target = File(parent, safeName)
        if (target.exists()) throw IOException("同名文件已存在")
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
        if (!created) throw IOException("无法创建文件")
        return buildDocumentId(parentDocumentId, safeName)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveFile(documentId)
        if (file == store.root) throw IOException("不能删除根目录")
        if (!file.deleteRecursively()) throw IOException("删除失败")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolveFile(documentId)
        if (file == store.root) throw IOException("不能重命名根目录")
        val parent = file.parentFile ?: throw IOException("缺少父目录")
        val target = File(parent, sanitizeName(displayName))
        if (target.exists()) throw IOException("同名文件已存在")
        if (!file.renameTo(target)) throw IOException("重命名失败")
        val parentId = parentDocumentId(documentId)
        return buildDocumentId(parentId, target.name)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        deleteDocument(documentId)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor {
        val file = resolveFile(documentId)
        if (!file.isFile) throw FileNotFoundException("没有缩略图")
        val bitmap = decodeThumbnail(file) ?: throw FileNotFoundException("无法解码缩略图")
        val descriptor = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor[1]).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }.start()
        return android.content.res.AssetFileDescriptor(descriptor[0], 0, -1)
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        if (!file.name.endsWith(".png", ignoreCase = true)) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (options.outWidth / sample > THUMBNAIL_SIZE || options.outHeight / sample > THUMBNAIL_SIZE) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun includeFile(cursor: MatrixCursor, documentId: String, file: File) {
        val mime = if (file.isDirectory) Document.MIME_TYPE_DIR else mimeTypeOf(file)
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_RENAME or
                (if (mime.startsWith("image/")) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "xml" -> "application/xml"
                "json" -> "application/json"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
    }

    private fun resolveFile(documentId: String): File {
        val relative = documentId.removePrefix(ROOT_DOCUMENT_ID).trimStart('/')
        if (relative.isEmpty()) return store.root
        if (relative.split('/').any { it == ".." || it.isEmpty() }) {
            throw FileNotFoundException("非法文档路径")
        }
        val file = File(store.root, relative)
        val canonicalRoot = store.root.canonicalFile
        val canonical = file.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            throw FileNotFoundException("文档越界")
        }
        if (!canonical.exists()) throw FileNotFoundException("文档不存在")
        return canonical
    }

    private fun parentDocumentId(documentId: String): String {
        val parts = documentId.removePrefix(ROOT_DOCUMENT_ID).trimStart('/').split('/')
        return if (parts.size <= 1) {
            ROOT_DOCUMENT_ID
        } else {
            ROOT_DOCUMENT_ID + "/" + parts.dropLast(1).joinToString("/")
        }
    }

    private fun buildDocumentId(parentDocumentId: String, name: String): String =
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            "$ROOT_DOCUMENT_ID/$name"
        } else {
            "$parentDocumentId/$name"
        }

    private fun isHidden(file: File): Boolean =
        file.name.startsWith(".tmp-") || file.name.startsWith(".nomedia")

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") throw IOException("非法文件名")
        return cleaned
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> =
        projection?.let { Array(it.size) { index -> it[index] } } ?: arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> =
        projection?.let { Array(it.size) { index -> it[index] } } ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

    private companion object {
        const val ROOT_ID = "customization"
        const val ROOT_DOCUMENT_ID = "customization"
        const val THUMBNAIL_SIZE = 128
    }
}
