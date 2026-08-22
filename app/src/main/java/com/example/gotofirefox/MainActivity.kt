package com.example.gotofirefox

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val prefsName = "goto_firefox_prefs"
    private val presetsKey = "presets"
    private val delimiter = "\n"

    private lateinit var urlInput: EditText
    private lateinit var saveCheckbox: CheckBox
    private lateinit var presetsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        urlInput = EditText(this).apply {
            hint = "Enter URL"
            isFocusable = true
            isFocusableInTouchMode = true
        }

        saveCheckbox = CheckBox(this).apply {
            text = "Save to presets"
            isFocusable = true
        }

        val goButton = Button(this).apply {
            text = "Go"
            isFocusable = true
            setOnClickListener {
                val raw = urlInput.text.toString()
                if (saveCheckbox.isChecked) {
                    addPreset(normalizeUrl(raw))
                }
                launchUrl(raw)
            }
        }

        presetsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val presetsScroll = ScrollView(this).apply {
            isFillViewport = true
            // Let focus land directly on the preset buttons, not the ScrollView itself,
            // so D-pad up/down moves between buttons and auto-scrolls to keep them visible.
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            addView(presetsContainer)
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlInput)
            addView(saveCheckbox)
            addView(goButton)
            addView(
                presetsScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f // presets list takes remaining vertical space and scrolls
                )
            )
        }
        setContentView(rootLayout)

        rebuildPresetButtons()

        // Pre-fill / auto-launch from a shared link or a go:// shortcut
        val incoming: String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.schemeSpecificPart?.trimStart('/')
            else -> null
        }

        if (incoming != null) {
            urlInput.setText(incoming)
            launchUrl(incoming) // skip the button entirely for shortcuts/shares
        } else {
            urlInput.requestFocus()
        }
    }

    private fun rebuildPresetButtons() {
        presetsContainer.removeAllViews()
        for (url in loadPresets()) {
            val button = Button(this).apply {
                text = url
                isAllCaps = false // preserve the case exactly as entered/saved
                isFocusable = true
                setOnClickListener { launchUrl(url) }
                setOnLongClickListener {
                    confirmDeletePreset(url)
                    true
                }
            }
            presetsContainer.addView(button)
        }
    }

    private fun confirmDeletePreset(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete preset?")
            .setMessage(url)
            .setPositiveButton("Delete") { _, _ -> removePreset(url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPresets(): List<String> {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val stored = prefs.getString(presetsKey, "") ?: ""
        return if (stored.isEmpty()) emptyList() else stored.split(delimiter)
    }

    private fun savePresets(list: List<String>) {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        prefs.edit().putString(presetsKey, list.joinToString(delimiter)).apply()
    }

    private fun addPreset(url: String) {
        if (url.isEmpty()) return
        val current = loadPresets().toMutableList()
        if (!current.contains(url)) {
            current.add(url)
            savePresets(current)
            rebuildPresetButtons()
        }
    }

    private fun removePreset(url: String) {
        val current = loadPresets().toMutableList()
        if (current.remove(url)) {
            savePresets(current)
            rebuildPresetButtons()
        }
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    private fun launchUrl(raw: String) {
        val url = normalizeUrl(raw)
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.mozilla.firefox")
            })
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        finish()
    }
}