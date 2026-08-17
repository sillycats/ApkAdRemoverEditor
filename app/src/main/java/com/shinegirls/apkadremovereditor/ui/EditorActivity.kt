package com.shinegirls.apkadremovereditor.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

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
            UiUtils.info(this@EditorActivity, "二进制文件仅支持查看")
        }
    }

    private fun buildBinaryPreview(file: File): String {
        val bytes = file.readBytes()
        val sb = StringBuilder()
        sb.appendLine("文件大小: ${bytes.size} bytes")
        sb.appendLine("\n十六进制预览 (前512字节):\n")

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
            sb.appendLine("\n... (${bytes.size - 512} bytes 未显示)")
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
            val msg = if (ok) "保存成功" else "保存失败: ${File(path).let { it.name }}"
            if (ok) UiUtils.success(this@EditorActivity, msg)
            else UiUtils.error(this@EditorActivity, msg)
        }
    }

    private fun findAndReplace() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val findEdit = dialogView.findViewById<EditText>(R.id.editFind)
        val replaceEdit = dialogView.findViewById<EditText>(R.id.editReplace)

        AlertDialog.Builder(this)
            .setTitle("查找替换")
            .setView(dialogView)
            .setPositiveButton("替换全部") { _, _ ->
                val find = findEdit.text.toString()
                val replace = replaceEdit.text.toString()
                if (find.isNotEmpty()) {
                    val content = editText.text.toString()
                    // 用 split 计算出现次数，避免 find/replace 长度相等时除零
                    val count = content.split(find).size - 1
                    val newContent = content.replace(find, replace)
                    editText.setText(newContent)
                    UiUtils.success(this, "替换了 $count 处")
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
