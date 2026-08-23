package com.shinegirls.apkadremovereditor.ui

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.core.LanguageManager
import com.shinegirls.apkadremovereditor.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    private lateinit var editText: EditText
    private var filePath: String? = null
    private var fileType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editText = findViewById(R.id.editText)
        filePath = intent.getStringExtra("file_path")
        fileType = intent.getStringExtra("type")
        val content = intent.getStringExtra("content")

        if (content != null) {
            editText.setText(content)
        } else if (filePath != null && fileType == "binary") {
            loadBinaryFile(File(filePath!!))
        }

        title = File(filePath ?: "").name
    }

    private fun loadBinaryFile(file: File) {
        // 大文件读取放到 IO 线程，避免阻塞主线程导致 ANR
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { buildBinaryPreview(file) }
            editText.setText(text)
            editText.isEnabled = false
            UiUtils.info(this@EditorActivity, getString(R.string.h_75baf427))
        }
    }

    private fun buildBinaryPreview(file: File): String {
        val bytes = file.readBytes()
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.h_e313c9e9, bytes.size))
        sb.appendLine(getString(R.string.h_65963a23))

        val limit = minOf(bytes.size, 512)
        for (i in 0 until limit step 16) {
            sb.append(String.format("%08X:  ", i))

            for (j in 0 until 16) {
                if (i + j < limit) {
                    sb.append(String.format("%02X ", bytes[i + j]))
                } else {
                    sb.append("   ")
                }
            }

            sb.append(" |")
            for (j in 0 until 16) {
                if (i + j < limit) {
                    val b = bytes[i + j].toInt() and 0xFF
                    sb.append(if (b in 32..126) b.toChar() else '.')
                }
            }
            sb.appendLine("|")
        }

        if (bytes.size > 512) {
            sb.appendLine(getString(R.string.h_2b8b4e3c, bytes.size - 512))
        }
        return sb.toString()
    }

    private fun saveFile() {
        val path = filePath ?: return
        // 写入放到 IO 线程，避免大文件写入阻塞主线程
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    File(path).writeText(editText.text.toString())
                    true
                } catch (e: Exception) {
                    false
                }
            }
            val msg = if (ok) getString(R.string.h_3b108349) else getString(R.string.h_9094d392, File(path).let { it.name })
            if (ok) UiUtils.success(this@EditorActivity, msg)
            else UiUtils.error(this@EditorActivity, msg)
        }
    }

    private fun findAndReplace() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val findEdit = dialogView.findViewById<EditText>(R.id.editFind)
        val replaceEdit = dialogView.findViewById<EditText>(R.id.editReplace)

        val frDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_5f7707b4))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.h_0502db8e)) { _, _ ->
                val find = findEdit.text.toString()
                val replace = replaceEdit.text.toString()
                if (find.isNotEmpty()) {
                    val content = editText.text.toString()
                    // 用 split 计算出现次数，避免 find/replace 长度相等时除零
                    val count = content.split(find).size - 1
                    val newContent = content.replace(find, replace)
                    editText.setText(newContent)
                    UiUtils.success(this, getString(R.string.h_c4726818, count))
                }
            }
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()
        frDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(frDialog)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        if (fileType == "binary") {
            menu?.findItem(R.id.action_save)?.isVisible = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveFile()
                true
            }
            R.id.action_find_replace -> {
                findAndReplace()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
