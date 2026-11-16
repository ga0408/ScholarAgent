package com.jjlee.mynote.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.jjlee.mynote.data.FolderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DriveAuthManager {
    private const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"
    const val DEBUG_SHA1 = "3C:AC:BE:FC:15:AD:CC:01:AF:F7:1B:3A:FB:33:16:9A:53:74:DC:B4"

    fun buildSignInIntent(context: Context): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE_DRIVE))
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    suspend fun getActiveAccessToken(context: Context, settings: FolderSettings): String? {
        val manual = settings.manualDriveToken?.takeIf { it.isNotBlank() }
        if (manual != null) return manual
        val account = getLastSignedInAccount(context) ?: return null
        return fetchAccessToken(context, account)
    }

    suspend fun fetchAccessToken(context: Context, account: GoogleSignInAccount): String? =
        withContext(Dispatchers.IO) {
            val androidAccount = account.account ?: return@withContext null
            runCatching {
                GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$SCOPE_DRIVE")
            }.getOrNull()
        }

    fun signOut(context: Context, onComplete: () -> Unit) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(context, gso).signOut().addOnCompleteListener {
            onComplete()
        }
    }
}
