package com.lekha.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.lekha.keyboard.ai.Rewriter
import kotlinx.coroutines.launch

/**
 * Onboarding + Gemini setup. "Test & save" asks Google which models the key can use with
 * generateContent, then lists them so the user taps the one to use. No model is guessed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var modelList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            getSystemService<InputMethodManager>()?.showInputMethodPicker()
        }

        keyInput = findViewById(R.id.keyInput)
        keyStatus = findViewById(R.id.keyStatus)
        btnSave = findViewById(R.id.btnSaveKey)
        btnTest = findViewById(R.id.btnTestKey)
        modelList = findViewById(R.id.modelList)

        Rewriter.getApiKey(this)?.let { keyInput.setText(it) }

        btnSave.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) { keyStatus.text = getString(R.string.key_empty); return@setOnClickListener }
            Rewriter.setApiKey(this, k)
            keyStatus.text = getString(R.string.key_saved)
        }

        btnTest.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.isEmpty()) { keyStatus.text = getString(R.string.key_empty); return@setOnClickListener }
            btnTest.isEnabled = false
            btnSave.isEnabled = false
            keyStatus.text = getString(R.string.key_testing)
            modelList.removeAllViews()
            lifecycleScope.launch {
                val models = Rewriter.listModels(this@MainActivity, k)
                btnTest.isEnabled = true
                btnSave.isEnabled = true
                if (models.isEmpty()) {
                    keyStatus.text = getString(R.string.key_fail, Rewriter.lastError() ?: "unknown error")
                    return@launch
                }
                Rewriter.setApiKey(this@MainActivity, k)
                keyStatus.text = getString(R.string.key_ok_count, models.size)
                val saved = Rewriter.getModel(this@MainActivity)
                val chosen = when {
                    models.contains(saved) -> saved
                    models.any { it.contains("flash") } -> models.first { it.contains("flash") }
                    else -> models.first()
                }
                Rewriter.setModel(this@MainActivity, chosen)
                renderModels(models, chosen)
            }
        }

        if (Rewriter.hasApiKey(this)) {
            keyStatus.text = getString(R.string.key_present_model, Rewriter.getModel(this))
        } else {
            keyStatus.text = getString(R.string.key_absent)
        }
    }

    private fun renderModels(models: List<String>, selected: String) {
        modelList.removeAllViews()
        modelList.addView(TextView(this).apply {
            text = getString(R.string.models_label)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        for (m in models) {
            modelList.addView(Button(this).apply {
                text = m
                isAllCaps = false
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(2), 0, dp(2)) }
                if (m == selected) {
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.kb_accent))
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.kb_accent_text))
                }
                setOnClickListener {
                    Rewriter.setModel(this@MainActivity, m)
                    keyStatus.text = getString(R.string.model_now, m)
                    renderModels(models, m)
                }
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
