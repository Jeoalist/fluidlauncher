package dev.jeo.pencere

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Mailbox the shell process uses to reach the launcher; every other caller is ignored. */
class DisplayServiceProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_REGISTER || Binder.getCallingUid() != SHELL_UID) return null
        val service = extras?.getBinder(KEY_SERVICE) ?: return null
        return Bundle().apply { putBinder(KEY_CLIENT, ShizukuBridge.attach(service)) }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val METHOD_REGISTER = "register"
        const val KEY_SERVICE = "service"
        const val KEY_CLIENT = "client"
        private const val SHELL_UID = 2000
    }
}
