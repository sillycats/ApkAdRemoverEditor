package com.shinegirls.apkadremovereditor.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SmaliEditorActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private var smaliFilePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        editText = findViewById(R.id.editText)
        smaliFilePath = intent.getStringExtra("file_path")

        smaliFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                title = file.name
                // 大文件读取放到 IO 线程，避免阻塞主线程
                lifecycleScope.launch {
                    val text = withContext(Dispatchers.IO) { file.readText() }
                    editText.setText(text)
                }
            }
        }
    }

    private fun saveFile() {
        val path = smaliFilePath ?: return
        // 写入放到 IO 线程
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    File(path).writeText(editText.text.toString())
                    true
                } catch (e: Exception) {
                    false
                }
            }
            val msg = if (ok) "Smali文件已保存" else "保存失败: ${File(path).name}"
            if (ok) UiUtils.success(this@SmaliEditorActivity, msg)
            else UiUtils.error(this@SmaliEditorActivity, msg)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.editor_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveFile()
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
