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

data class Preset(val name: String, val url: String)

class MainActivity : AppCompatActivity() {

    private val prefsName = "goto_firefox_prefs"
    private val presetsKey = "presets"
    private val lineDelimiter = "\n"
    private val fieldDelimiter = "\t"

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
                    val resolved = resolveUrl(raw)
                    addPreset(resolved, resolved) // name defaults to the URL; rename later via Edit
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
        val presets = loadPresets()
        for ((index, preset) in presets.withIndex()) {
            val button = Button(this).apply {
                text = preset.name.ifBlank { preset.url }
                isAllCaps = false // preserve the case exactly as entered/saved
                isFocusable = true
                setOnClickListener { launchUrl(preset.url) }
                setOnLongClickListener {
                    showPresetMenu(index)
                    true
                }
            }
            presetsContainer.addView(button)
        }
    }

    private fun showPresetMenu(index: Int) {
        val presets = loadPresets()
        if (index !in presets.indices) return
        val preset = presets[index]
        val options = arrayOf("Edit", "Rename", "Move Up", "Move Down", "Delete", "Cancel")
        AlertDialog.Builder(this)
            .setTitle(preset.name.ifBlank { preset.url })
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Edit" -> showEditDialog(index)
                    "Rename" -> showRenameDialog(index)
                    "Move Up" -> movePreset(index, -1)
                    "Move Down" -> movePreset(index, 1)
                    "Delete" -> confirmDeletePreset(index)
                    // "Cancel" -> dialog just closes, nothing to do
                }
            }
            .show()
    }

    private fun showRenameDialog(index: Int) {
        val presets = loadPresets()
        if (index !in presets.indices) return
        val preset = presets[index]

        val nameField = EditText(this).apply {
            hint = "Name"
            setText(preset.name)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(nameField)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameField.text.toString().trim()
                replacePreset(index, if (newName.isEmpty()) preset.url else newName, preset.url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(index: Int) {
        val presets = loadPresets()
        if (index !in presets.indices) return
        val preset = presets[index]

        val nameLabel = android.widget.TextView(this).apply { text = "Name" }
        val nameField = EditText(this).apply {
            setText(preset.name)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val urlLabel = android.widget.TextView(this).apply { text = "URL" }
        val urlField = EditText(this).apply {
            setText(preset.url)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val fieldsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(nameLabel)
            addView(nameField)
            addView(urlLabel)
            addView(urlField)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit preset")
            .setView(fieldsLayout)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = normalizeUrl(urlField.text.toString())
                if (newUrl.isEmpty()) return@setPositiveButton
                val newName = nameField.text.toString().trim()
                replacePreset(index, if (newName.isEmpty()) newUrl else newName, newUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePreset(index: Int) {
        val presets = loadPresets()
        if (index !in presets.indices) return
        val preset = presets[index]
        AlertDialog.Builder(this)
            .setTitle("Delete preset?")
            .setMessage(preset.name.ifBlank { preset.url })
            .setPositiveButton("Delete") { _, _ -> removePreset(index) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPresets(): List<Preset> {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val stored = prefs.getString(presetsKey, "") ?: ""
        if (stored.isEmpty()) return emptyList()
        return stored.split(lineDelimiter).map { line ->
            val parts = line.split(fieldDelimiter, limit = 2)
            val url = parts.getOrElse(1) { parts[0] } // old entries (pre-rename) have no name field
            Preset(name = parts[0], url = url)
        }
    }

    private fun savePresets(list: List<Preset>) {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val serialized = list.joinToString(lineDelimiter) { "${it.name}$fieldDelimiter${it.url}" }
        prefs.edit().putString(presetsKey, serialized).apply()
    }

    private fun addPreset(name: String, url: String) {
        if (url.isEmpty()) return
        val current = loadPresets().toMutableList()
        if (current.none { it.url == url }) {
            current.add(Preset(name, url))
            savePresets(current)
            rebuildPresetButtons()
        }
    }

    private fun removePreset(index: Int) {
        val current = loadPresets().toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        savePresets(current)
        rebuildPresetButtons()
    }

    private fun replacePreset(index: Int, newName: String, newUrl: String) {
        val current = loadPresets().toMutableList()
        if (index !in current.indices) return
        val duplicateIndex = current.indexOfFirst { it.url == newUrl }
        if (duplicateIndex != -1 && duplicateIndex != index) {
            // Another entry already has this URL — drop this one instead of duplicating.
            current.removeAt(index)
        } else {
            current[index] = Preset(newName, newUrl)
        }
        savePresets(current)
        rebuildPresetButtons()
    }

    private fun movePreset(index: Int, delta: Int) {
        val current = loadPresets().toMutableList()
        val newIndex = index + delta
        if (index !in current.indices || newIndex < 0 || newIndex >= current.size) return
        val item = current.removeAt(index)
        current.add(newIndex, item)
        savePresets(current)
        rebuildPresetButtons()
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    // Domain-shaped: no spaces, and either has a scheme already or looks like
    // word.word (with an optional port and/or path) — e.g. "evdemon.org",
    // "localhost:8080/app", "192.168.1.1". Anything else is treated as a
    // search query rather than an attempted destination.
    private val domainPattern =
        Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?(/.*)?$")

    private fun looksLikeUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(" ")) return false
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
        return domainPattern.matches(trimmed)
    }

    private fun resolveUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (looksLikeUrl(trimmed)) {
            normalizeUrl(trimmed)
        } else {
            "https://search.brave.com/search?q=" + Uri.encode(trimmed)
        }
    }

    private fun launchUrl(raw: String) {
        val url = resolveUrl(raw)
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.mozilla.firefox")
            })
        } catch (e: Exception) {
            return
        }
        finish()
    }
}