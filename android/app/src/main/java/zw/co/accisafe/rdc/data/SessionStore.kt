package zw.co.accisafe.rdc.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import zw.co.accisafe.rdc.BuildConfig
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.appJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("accisafe_rdc")

/** Monitoring and testing preferences kept on this device. */
data class DeviceSettings(
    val impactG: Float = 4.0f,
    val cooldownSeconds: Int = 10,
    val countdownSeconds: Int = 30,
    val smsToPersonalContacts: Boolean = true,
    val simulationMode: Boolean = false,
)

/**
 * Session and device settings. Tokens are encrypted with an Android Keystore key that never
 * leaves the device; nothing secret is stored in plain text.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val access = stringPreferencesKey("access_enc")
        val refresh = stringPreferencesKey("refresh_enc")
        val me = stringPreferencesKey("me_json")
        val councilCode = stringPreferencesKey("last_council_code")
        val impactG = floatPreferencesKey("impact_g")
        val cooldown = intPreferencesKey("cooldown_s")
        val countdown = intPreferencesKey("countdown_s")
        val smsPersonal = booleanPreferencesKey("sms_personal")
        val simulation = booleanPreferencesKey("simulation_mode")
        val privacyAccepted = booleanPreferencesKey("privacy_accepted")
    }

    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me
    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile var serverUrl: String = BuildConfig.DEFAULT_SERVER_URL
        private set

    /** Loads the stored session once at start-up. */
    fun restore() = runBlocking {
        val p = context.dataStore.data.first()
        serverUrl = p[Keys.serverUrl] ?: BuildConfig.DEFAULT_SERVER_URL
        accessToken = p[Keys.access]?.let(::decrypt)
        refreshToken = p[Keys.refresh]?.let(::decrypt)
        _me.value = p[Keys.me]?.let { runCatching { appJson.decodeFromString<Me>(it) }.getOrNull() }
        if (refreshToken == null) _me.value = null
    }

    fun accessToken() = accessToken
    fun refreshToken() = refreshToken

    suspend fun saveSession(access: String, refresh: String, me: Me) {
        accessToken = access
        refreshToken = refresh
        _me.value = me
        context.dataStore.edit {
            it[Keys.access] = encrypt(access)
            it[Keys.refresh] = encrypt(refresh)
            it[Keys.me] = appJson.encodeToString(me)
            it[Keys.councilCode] = me.councilCode
        }
    }

    suspend fun updateMe(me: Me) {
        _me.value = me
        context.dataStore.edit { it[Keys.me] = appJson.encodeToString(me) }
    }

    suspend fun clearSession() {
        accessToken = null
        refreshToken = null
        _me.value = null
        context.dataStore.edit {
            it.remove(Keys.access); it.remove(Keys.refresh); it.remove(Keys.me)
        }
    }

    suspend fun setServerUrl(url: String) {
        serverUrl = url
        context.dataStore.edit { it[Keys.serverUrl] = url }
    }

    val lastCouncilCode: Flow<String> = context.dataStore.data.map { it[Keys.councilCode] ?: "" }
    val privacyAccepted: Flow<Boolean> = context.dataStore.data.map { it[Keys.privacyAccepted] ?: false }
    suspend fun acceptPrivacy() = context.dataStore.edit { it[Keys.privacyAccepted] = true }

    val deviceSettings: Flow<DeviceSettings> = context.dataStore.data.map(::toSettings)
    suspend fun currentDeviceSettings(): DeviceSettings = toSettings(context.dataStore.data.first())

    suspend fun saveDeviceSettings(s: DeviceSettings) = context.dataStore.edit {
        it[Keys.impactG] = s.impactG.coerceIn(2f, 15f)
        it[Keys.cooldown] = s.cooldownSeconds.coerceIn(5, 600)
        it[Keys.countdown] = s.countdownSeconds.coerceIn(10, 120)
        it[Keys.smsPersonal] = s.smsToPersonalContacts
        it[Keys.simulation] = s.simulationMode
    }

    private fun toSettings(p: Preferences) = DeviceSettings(
        impactG = p[Keys.impactG] ?: 4.0f,
        cooldownSeconds = p[Keys.cooldown] ?: 10,
        countdownSeconds = p[Keys.countdown] ?: 30,
        smsToPersonalContacts = p[Keys.smsPersonal] ?: true,
        simulationMode = p[Keys.simulation] ?: false,
    )

    // --- Keystore encryption -------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12))
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "accisafe_rdc_session"
    }
}
