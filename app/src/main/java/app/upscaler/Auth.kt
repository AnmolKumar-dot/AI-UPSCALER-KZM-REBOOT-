package app.upscaler

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

class NeedsSignIn : Exception("Sign-in required")

/** Official Google Identity Authorization API. No passwords/cookies; access tokens are short-lived
 *  and re-requested on demand (never persisted by this app). */
object Auth {
    // Full Drive scope is required so the app can read files the Colab worker creates.
    private const val DRIVE = "https://www.googleapis.com/auth/drive"
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE))).build()

    suspend fun authorize(ctx: Context): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).authorize(request()).await()

    suspend fun silentToken(ctx: Context): String {
        val r = authorize(ctx)
        if (r.hasResolution()) throw NeedsSignIn()
        return r.accessToken ?: throw NeedsSignIn()
    }

    fun fromIntent(ctx: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
}
