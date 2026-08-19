package com.shinegirls.apkadremovereditor.utils

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import com.shinegirls.apkadremovereditor.R

/**
 * 统一的美化 UI 工具类。
 *
 * 提供带图标、圆角渐变背景的自定义 Toast，替代系统默认的灰底 Toast，
 * 与 App 的粉紫主题保持一致。所有提示统一走 [toast]，保证视觉一致。
 */
object UiUtils {

    enum class ToastType {
        /** 成功 / 完成类提示（绿色对勾） */
        SUCCESS,

        /** 信息 / 进行中类提示（紫色信息） */
        INFO,

        /** 警告类提示（橙色感叹号） */
        WARNING,

        /** 错误类提示（玫红叉号） */
        ERROR
    }

    private val typeIcons: Map<ToastType, Int> = mapOf(
        ToastType.SUCCESS to R.drawable.ic_check_circle,
        ToastType.INFO to R.drawable.ic_info,
        ToastType.WARNING to R.drawable.ic_help,
        ToastType.ERROR to R.drawable.ic_close
    )

    private val typeColors: Map<ToastType, Int> = mapOf(
        ToastType.SUCCESS to R.color.log_success,
        ToastType.INFO to R.color.accent,
        ToastType.WARNING to R.color.log_warning,
        ToastType.ERROR to R.color.log_error
    )

    /**
     * 显示美化 Toast。
     *
     * @param context 上下文
     * @param message 提示文本
     * @param type 提示类型，决定图标与着色
     * @param duration 显示时长
     */
    fun toast(
        context: Context,
        message: String,
        type: ToastType = ToastType.INFO,
        durationMs: Int = Toast.LENGTH_SHORT
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.view_toast, null)
        val icon = view.findViewById<ImageView>(R.id.ivToastIcon)
        val text = view.findViewById<TextView>(R.id.tvToastText)

        icon.setImageResource(typeIcons[type] ?: R.drawable.ic_info)
        icon.setColorFilter(context.getColor(typeColors[type] ?: R.color.accent))
        text.text = message

        Toast(context).apply {
            duration = durationMs
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 140)
            view?.let { setView(it) }
            show()
        }
    }

    /** 便捷方法：成功提示。 */
    fun success(context: Context, message: String) =
        toast(context, message, ToastType.SUCCESS)

    /** 便捷方法：信息提示。 */
    fun info(context: Context, message: String) =
        toast(context, message, ToastType.INFO)

    /** 便捷方法：警告提示。 */
    fun warning(context: Context, message: String) =
        toast(context, message, ToastType.WARNING)

    /** 便捷方法：错误提示。 */
    fun error(context: Context, message: String) =
        toast(context, message, ToastType.ERROR, Toast.LENGTH_LONG)

    /**
     * 让弹窗自适应屏幕大小，避免内容过多时溢出屏幕导致无法查看或点击。
     *
     * 在 [dialog.show] 之后调用：
     * - 宽度限制为屏幕宽度的 [widthRatio]（默认 90%）
     * - 测量内容高度，若超过屏幕高度的 [maxHeightRatio]（默认 85%）则压缩到该高度，
     *   配合布局中的 ScrollView 即可滚动查看全部内容；未超过则保持 wrap_content。
     *
     * @param dialog 已 show 的 AlertDialog
     * @param maxHeightRatio 最大高度占屏幕高度的比例
     * @param widthRatio 宽度占屏幕宽度的比例
     */
    fun fitDialogToScreen(
        dialog: AlertDialog,
        maxHeightRatio: Float = 0.85f,
        widthRatio: Float = 0.9f
    ) {
        try {
            val win = dialog.window ?: return
            val dm = dialog.context.resources.displayMetrics
            val maxHeight = (dm.heightPixels * maxHeightRatio).toInt()
            val width = (dm.widthPixels * widthRatio).toInt()

            val lp = win.attributes
            lp.width = width
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp

            // 测量内容真实高度，超出则压缩窗口高度（配合 ScrollView 滚动）
            val root = win.decorView
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val contentHeight = root.measuredHeight
            if (contentHeight > maxHeight) {
                lp.height = maxHeight
                win.attributes = lp
            }
        } catch (_: Exception) {
            // 极端情况下保持系统默认行为，不影响弹窗展示
        }
    }
}