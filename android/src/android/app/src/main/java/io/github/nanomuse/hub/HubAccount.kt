package io.github.nanomuse.hub

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Optional account credential used only by the existing Hub transport. */
object HubAccount {
    const val DEFAULT_RELAY = "https://relay.palimpsestai.xyz"
    private const val PREFS = "kestrel_hub_account"
    private const val KEY_RELAY = "relay"
    private const val KEY_TOKEN = "token"
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun relay(context: Context): String = prefs(context).getString(KEY_RELAY, DEFAULT_RELAY)!!
        .trim().trimEnd('/')

    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)?.takeIf(String::isNotBlank)

    fun saveRelay(context: Context, value: String) {
        val clean = value.trim().trimEnd('/')
        require(clean.startsWith("https://")) { "Relay URL must use HTTPS" }
        prefs(context).edit().putString(KEY_RELAY, clean).apply()
        Hub.restart(context.applicationContext)
    }

    suspend fun requestCode(context: Context, relay: String, identifier: String) = withContext(Dispatchers.IO) {
        request(relay, "/v1/auth/code", JSONObject().put("identifier", identifier.trim()))
    }

    suspend fun signIn(context: Context, relay: String, identifier: String, code: String) = withContext(Dispatchers.IO) {
        val id = Hub.deviceId(context.applicationContext)
        val body = JSONObject()
            .put("identifier", identifier.trim())
            .put("code", code.trim())
            .put("device", Hub.name(context.applicationContext))
            .put("device_id", id)
            .put("device_kind", "phone")
        val reply = request(relay, "/v1/auth/verify", body)
        val token = reply.optString("api_key").takeIf(String::isNotBlank)
            ?: throw IOException("Relay response did not include an account credential")
        prefs(context).edit().putString(KEY_RELAY, relay.trim().trimEnd('/')).putString(KEY_TOKEN, token).apply()
        Hub.restart(context.applicationContext)
    }

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        token(app)?.let { key -> runCatching { request(relay(app), "/v1/auth/sign-out", null, key) } }
        prefs(app).edit().remove(KEY_TOKEN).apply()
        Hub.stop(app)
    }

    private fun request(base: String, path: String, body: JSONObject?, bearer: String? = null): JSONObject {
        val clean = base.trim().trimEnd('/')
        require(clean.startsWith("https://")) { "Relay URL must use HTTPS" }
        val builder = Request.Builder().url(clean + path)
            .header("Accept", "application/json")
            .method(if (body == null) "POST" else "POST", body?.toString()?.toRequestBody(json) ?: "".toRequestBody(json))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val value = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = value?.optJSONObject("error")?.optString("message")
                    ?: value?.optString("message")
                    ?: "Relay request failed (${response.code})"
                throw IOException(detail)
            }
            return value ?: JSONObject()
        }
    }
}
