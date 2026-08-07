package com.moonlight.matrixmessenger

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RingtoneSettingsActivity : AppCompatActivity() {

    private val kv = CloudflareKvClient()
    private val ringtoneService = RingtoneService(kv)
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var currentUsername: String
    private lateinit var ringtoneList: ListView
    private var previewPlayer: MediaPlayer? = null

    // Built-in ringtones — resolved dynamically from RingtoneService's
    // single shared list (name -> raw resource file name), so this can
    // never drift out of sync with CallActivity's copy.
    private val builtInRingtones: List<Pair<String, Int>> by lazy {
        RingtoneService.BUILT_IN_RINGTONES.map { (name, resFileName) ->
            name to resources.getIdentifier(resFileName, "raw", packageName)
        }
    }

    // Rows: builtInRingtones first (position = index into builtInRingtones),
    // then customRingtones after (position - builtInRingtones.size).
    private var customRingtones: List<RingtoneMeta> = emptyList()

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) promptForNameAndUpload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ringtones)

        currentUsername = intent.getStringExtra("username") ?: "unknown"
        ringtoneList = findViewById(R.id.ringtoneList)

        findViewById<Button>(R.id.addRingtoneButton).setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        findViewById<TextView>(R.id.attributionText).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:moonhpro318@gmail.com")
                putExtra(Intent.EXTRA_SUBJECT, "Ringtone copyright concern")
            }
            startActivity(Intent.createChooser(intent, "Send email"))
        }

        loadRingtones()
    }

    private fun loadRingtones() {
        scope.launch {
            customRingtones = withContext(Dispatchers.IO) {
                ringtoneService.listCustomRingtones(currentUsername)
            }
            val selectedId = withContext(Dispatchers.IO) {
                ringtoneService.getSelectedRingtoneId(currentUsername)
            }
            renderList(selectedId)
        }
    }

    private fun renderList(selectedId: String?) {
        val labels = mutableListOf<String>()

        builtInRingtones.forEachIndexed { index, (name, _) ->
            // Position 0 (Pulse) is selected when selectedId is null; others are never "selected by default"
            val isSelected = (index == 0 && selectedId == null) || selectedId == "builtin:$index"
            labels.add((if (isSelected) "✓ " else "") + name)
        }
        customRingtones.forEach { meta ->
            val prefix = if (meta.id == selectedId) "✓ " else ""
            labels.add(prefix + meta.name)
        }

        ringtoneList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        ringtoneList.setOnItemClickListener { _, _, position, _ ->
            if (position < builtInRingtones.size) {
                val selectionId = if (position == 0) null else "builtin:$position"
                previewAndSelectBuiltIn(selectionId, builtInRingtones[position].second)
            } else {
                val meta = customRingtones[position - builtInRingtones.size]
                previewAndSelectCustom(meta.id)
            }
        }

        ringtoneList.setOnItemLongClickListener { _, _, position, _ ->
            if (position < builtInRingtones.size) {
                Toast.makeText(this, "Built-in ringtones can't be deleted", Toast.LENGTH_SHORT).show()
            } else {
                confirmDelete(customRingtones[position - builtInRingtones.size])
            }
            true
        }
    }

    private fun previewAndSelectBuiltIn(selectionId: String?, resId: Int) {
        previewPlayer?.release()
        previewPlayer = null

        scope.launch {
            withContext(Dispatchers.IO) {
                ringtoneService.setSelectedRingtone(currentUsername, selectionId)
            }
            renderList(selectionId)

            previewPlayer = MediaPlayer.create(this@RingtoneSettingsActivity, resId)
            previewPlayer?.start()
        }
    }

    private fun previewAndSelectCustom(ringtoneId: String) {
        previewPlayer?.release()
        previewPlayer = null

        scope.launch {
            withContext(Dispatchers.IO) {
                ringtoneService.setSelectedRingtone(currentUsername, ringtoneId)
            }
            renderList(ringtoneId)

            val audioBytes = withContext(Dispatchers.IO) {
                ringtoneService.getCustomRingtoneAudio(currentUsername, ringtoneId)
            }
            if (audioBytes != null) {
                val tempFile = java.io.File.createTempFile("preview", ".mp3", cacheDir)
                tempFile.writeBytes(audioBytes)
                previewPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener { tempFile.delete() }
                }
            }
        }
    }

    private fun promptForNameAndUpload(uri: Uri) {
        val input = EditText(this)
        input.hint = "Name this ringtone"

        AlertDialog.Builder(this)
            .setTitle("Add ringtone")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Custom Ringtone" }
                uploadRingtone(uri, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadRingtone(uri: Uri, name: String) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: run {
                    Toast.makeText(this@RingtoneSettingsActivity, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    ringtoneService.addCustomRingtone(currentUsername, name, bytes)
                }

                if (result.success) {
                    Toast.makeText(this@RingtoneSettingsActivity, "Added \"$name\"", Toast.LENGTH_SHORT).show()
                    loadRingtones()
                } else {
                    Toast.makeText(this@RingtoneSettingsActivity, result.errorMessage, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RingtoneSettingsActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(meta: RingtoneMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${meta.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ringtoneService.deleteCustomRingtone(currentUsername, meta.id)
                    }
                    loadRingtones()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPlayer?.release()
    }
}
