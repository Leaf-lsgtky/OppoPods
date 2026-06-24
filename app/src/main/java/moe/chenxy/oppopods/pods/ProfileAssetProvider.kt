package moe.chenxy.oppopods.pods

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * 只读供图 Provider：让被 hook 的系统进程（com.android.bluetooth 等）能读取当前配置档的
 * 资源文件（岛图/通知图）。URI: content://moe.chenxy.oppopods.assets/<profileId>/<fileName>。
 * 仅暴露 filesDir/profiles/ 下的文件，[ProfileAssets.resolve] 做目录穿越防护。
 */
class ProfileAssetProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val file = ProfileAssets.resolve(ctx, uri) ?: return null
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
