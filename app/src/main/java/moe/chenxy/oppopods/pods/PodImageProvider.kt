package moe.chenxy.oppopods.pods

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * 只读供图 Provider：让被 hook 的系统进程（com.android.bluetooth / SystemUI）能读取
 * 用户自定义的耳机素材。URI: content://moe.chenxy.oppopods.assets/<slotKey>。
 * 只暴露 [PodImageSlot] 里的已知槽位，未设置的槽位返回 null 由调用方回退内置资源。
 */
class PodImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val file = PodImageStore.resolve(ctx, uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
