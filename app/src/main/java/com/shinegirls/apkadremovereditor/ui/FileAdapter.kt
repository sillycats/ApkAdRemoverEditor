package com.shinegirls.apkadremovereditor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.utils.Format
import java.io.File

class FileAdapter(
    private var files: List<File>,
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    fun updateFiles(newFiles: List<File>) {
        val sorted = newFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        val result = DiffUtil.calculateDiff(FileDiffCallback(files, sorted))
        files = sorted
        result.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.iconView)
        private val nameView: TextView = itemView.findViewById(R.id.nameView)
        private val infoView: TextView = itemView.findViewById(R.id.infoView)

        fun bind(file: File) {
            nameView.text = file.name

            if (file.isDirectory) {
                iconView.setImageResource(R.drawable.ic_category_view)
                val childCount = file.listFiles()?.size ?: 0
                infoView.text = itemView.context.getString(R.string.h_71a40f61, childCount)
            } else {
                iconView.setImageResource(getFileIcon(file))
                infoView.text = Format.formatSize(file.length())
            }

            itemView.setOnClickListener {
                onItemClick(file)
            }
        }

        private fun getFileIcon(file: File): Int {
            return when {
                file.name.endsWith(".dex", ignoreCase = true) -> R.drawable.ic_category_lib
                file.name.endsWith(".xml", ignoreCase = true) -> R.drawable.ic_category_layout
                file.name.endsWith(".smali", ignoreCase = true) -> R.drawable.ic_category_method
                file.name.endsWith(".png", ignoreCase = true) ||
                file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".jpeg", ignoreCase = true) -> R.drawable.ic_category_asset
                else -> R.drawable.ic_category_sdk
            }
        }
    }

    private class FileDiffCallback(
        private val oldList: List<File>,
        private val newList: List<File>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition].name == newList[newItemPosition].name
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition] == newList[newItemPosition]
    }
}