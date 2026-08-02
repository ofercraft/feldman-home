package com.feldman.ha.widgets

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.feldman.ha.api.getStoredApi
import com.feldman.ha.api.provideHAApi
import com.feldman.motion.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tiny transparent activity that prompts for an alarm PIN, then performs the gated
 * `alarm_control_panel` service call. Glance widgets can't show a dialog inline, so the widget
 * action launches this when the panel requires a code and none is saved in the widget config.
 */
class AlarmCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entityId = intent.getStringExtra("entity_id") ?: run { finish(); return }
        val domain = intent.getStringExtra("domain") ?: run { finish(); return }
        val service = intent.getStringExtra("service") ?: run { finish(); return }
        val widgetKey = intent.getStringExtra("widget_key")
        val codeFormat = intent.getStringExtra("code_format")

        setContent {
            AppTheme {
                var code by remember { mutableStateOf("") }
                val numeric = codeFormat == "number"
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Enter alarm code") },
                    text = {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = code.isNotBlank(),
                            onClick = { performAction(domain, service, entityId, code, widgetKey) }
                        ) { Text("Confirm") }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    }
                )
            }
        }
    }

    private fun performAction(
        domain: String,
        service: String,
        entityId: String,
        code: String,
        widgetKey: String?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = getStoredApi(applicationContext) ?: provideHAApi("", "")
                val body = mutableMapOf<String, Any>("entity_id" to entityId)
                // Rebuild the body params the widget action prepared (entity_id + any others).
                intent.extras?.keySet()?.forEach { key ->
                    if (key.startsWith("param_")) {
                        val paramKey = key.substring(6)
                        val v = intent.getStringExtra(key) ?: ""
                        body[paramKey] = v.toIntOrNull() ?: v
                    }
                }
                body["code"] = code

                api.callService(domain, service, body)
                Log.d("AlarmCode", "Performed $domain/$service on $entityId")

                if (widgetKey != null) {
                    WidgetRefreshScheduler.requestImmediate(applicationContext, widgetKey, refreshFromApi = true)
                }
            } catch (e: Exception) {
                Log.e("AlarmCode", "Action failed", e)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }
}
