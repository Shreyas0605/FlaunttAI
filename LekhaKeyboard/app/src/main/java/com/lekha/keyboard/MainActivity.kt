package com.Fluent.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.Fluent.keyboard.ai.Rewriter
import kotlinx.coroutines.launch

/**
 * Onboarding + Gemini API key setup. Enabling/switching a keyboard is a system action,
 * so we send the user to the right system screen. The key is tested against Gemini and,
 * on success, saved locally.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button

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
            lifecycleScope.launch {
                val err = Rewriter.testKey(this@MainActivity, k)
                btnTest.isEnabled = true
                btnSave.isEnabled = true
                if (err == null) {
                    Rewriter.setApiKey(this@MainActivity, k)
                    keyStatus.text = getString(R.string.key_ok)
                } else {
                    keyStatus.text = getString(R.string.key_fail, err)
                }
            }
        }

        refreshKeyStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!keyStatus.text.toString().let { it.startsWith("Key") || it.startsWith("Saved") || it.startsWith("Testing") }) {
            refreshKeyStatus()
        }
    }

    private fun refreshKeyStatus() {
        keyStatus.text = if (Rewriter.hasApiKey(this)) getString(R.string.key_present)
        else getString(R.string.key_absent)
    }
}
