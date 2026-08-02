package com.feldman.ha.widgets

import android.os.Bundle
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.feldman.ha.api.provideHAApi
import com.feldman.ha.api.getStoredApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuthenticationActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val entityId = intent.getStringExtra("entity_id") ?: ""
        val serviceCall = intent.getStringExtra("service_call") ?: ""
        val value = intent.getStringExtra("value") ?: ""
        val domain = intent.getStringExtra("domain") ?: ""
        val service = intent.getStringExtra("service") ?: ""
        
        showBiometricPrompt(domain, service, entityId, value)
    }

    private fun showBiometricPrompt(domain: String, service: String, entityId: String, value: String) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    performAction(domain, service, entityId, value)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authentication Required")
            .setSubtitle("Confirm to perform action")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun performAction(domain: String, service: String, entityId: String, value: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = getStoredApi(applicationContext) ?: provideHAApi("", "")
                val body = mutableMapOf<String, Any>("entity_id" to entityId)
                
                // Rebuild params from extras starting with "param_"
                intent.extras?.keySet()?.forEach { key ->
                    if (key.startsWith("param_")) {
                        val paramKey = key.substring(6)
                        val v = intent.getStringExtra(key) ?: ""
                        body[paramKey] = v.toIntOrNull() ?: v
                    }
                }
                
                api.callService(domain, service, body)
                Log.d("AuthAction", "Action performed: $domain/$service on $entityId with value=$value")
                
                val widgetKey = intent.getStringExtra("widget_key")
                if (widgetKey != null) {
                    WidgetRefreshScheduler.requestImmediate(applicationContext, widgetKey, refreshFromApi = true)
                }
            } catch (e: Exception) {
                Log.e("AuthAction", "Action failed", e)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }
}
