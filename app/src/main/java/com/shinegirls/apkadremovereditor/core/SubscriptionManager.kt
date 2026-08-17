package com.shinegirls.apkadremovereditor.core

import android.content.Context
import android.util.Base64
import com.shinegirls.apkadremovereditor.utils.Format
import com.shinegirls.apkadremovereditor.utils.PathPreferences
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 广告特征配置订阅管理器。
 *
 * 提供"订阅源口令"机制，实现广告特征配置的分享与订阅：
 * - 分享：把当前配置编码成订阅源口令（支持 URL 型与内嵌型两种）
 * - 添加订阅：输入别人分享的口令，解析并拉取/应用远程配置
 * - 编辑 / 删除订阅：管理已保存的订阅列表
 * - 应用订阅：把订阅的配置应用到当前配置并保存
 *
 * 口令格式：`ADSUB:<Base64(JSON)>`，JSON 结构如下：
 * {
 *   "v": 1,                          // 口令版本
 *   "name": "订阅源名称",             // 订阅源名称
 *   "type": "url" | "content",       // url=远程地址型，content=内嵌配置型
 *   "url": "https://...",            // type=url 时的远程配置地址
 *   "content": { ...完整配置... },   // type=content 时内嵌的完整配置
 *   "ts": 1234567890                 // 生成时间戳
 * }
 *
 * 订阅列表存储于独立 JSON 文件：/storage/emulated/0/APKEditor/subscriptions.json
 */
object SubscriptionManager {

    /** 口令前缀，用于识别合法口令。 */
    private const val TOKEN_PREFIX = "ADSUB:"

    /** 口令版本。 */
    private const val TOKEN_VERSION = 1

    private const val CONFIG_DIR = Format.EXPORT_DIR
    private const val SUBSCRIPTIONS_FILE = "subscriptions.json"

    /** 网络超时（毫秒）。 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 订阅源类型。 */
    enum class Type { URL, CONTENT }

    /**
     * 订阅源数据类。
     *
     * @param id          唯一标识
     * @param name        订阅源名称
     * @param type        订阅源类型
     * @param url         远程配置地址（type=URL 时有效）
     * @param contentJson 内嵌配置 JSON 字符串（type=CONTENT 时有效）
     * @param enabled     是否已开启（开启即应用该订阅配置）
     * @param createdAt   创建时间戳
     */
    data class Subscription(
        val id: String,
        val name: String,
        val type: Type,
        val url: String = "",
        val contentJson: String = "",
        val enabled: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 解析后的口令内容。
     */
    data class Token(
        val name: String,
        val type: Type,
        val url: String = "",
        val contentJson: String = ""
    )

    // ========== 订阅列表存储 ==========

    private fun getSubscriptionsFile(context: Context): File {
        // 订阅列表文件与配置文件同目录
        val configPath = PathPreferences.getConfigFilePath(context)
        val dir = File(configPath).parentFile ?: File(CONFIG_DIR)
        return File(dir, SUBSCRIPTIONS_FILE)
    }

    /**
     * 读取订阅列表。
     */
    fun loadSubscriptions(context: Context): List<Subscription> {
        val file = getSubscriptionsFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            val arr = json.optJSONArray("subscriptions") ?: return emptyList()
            val result = mutableListOf<Subscription>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    Subscription(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "未命名订阅"),
                        type = if (obj.optString("type") == "url") Type.URL else Type.CONTENT,
                        url = obj.optString("url", ""),
                        contentJson = obj.optString("content", ""),
                        enabled = obj.optBoolean("enabled", false),
                        createdAt = obj.optLong("createdAt", 0L)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 保存订阅列表。
     */
    private fun saveSubscriptions(list: List<Subscription>, context: Context): Boolean {
        return try {
            val configPath = PathPreferences.getConfigFilePath(context)
            val dir = File(configPath).parentFile ?: File(CONFIG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val root = JSONObject()
            val arr = org.json.JSONArray()
            for (sub in list) {
                val obj = JSONObject()
                obj.put("id", sub.id)
                obj.put("name", sub.name)
                obj.put("type", if (sub.type == Type.URL) "url" else "content")
                obj.put("url", sub.url)
                obj.put("content", sub.contentJson)
                obj.put("enabled", sub.enabled)
                obj.put("createdAt", sub.createdAt)
                arr.put(obj)
            }
            root.put("subscriptions", arr)
            getSubscriptionsFile(context).writeText(root.toString(2), StandardCharsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 添加订阅。
     */
    fun addSubscription(sub: Subscription, context: Context): Boolean {
        val list = loadSubscriptions(context).toMutableList()
        list.add(sub)
        return saveSubscriptions(list, context)
    }

    /**
     * 更新订阅。
     */
    fun updateSubscription(updated: Subscription, context: Context): Boolean {
        val list = loadSubscriptions(context).toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        list[idx] = updated
        return saveSubscriptions(list, context)
    }

    /**
     * 删除订阅。
     */
    fun deleteSubscription(id: String, context: Context): Boolean {
        val list = loadSubscriptions(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        return if (removed) saveSubscriptions(list, context) else false
    }

    /**
     * 设置订阅的开启/关闭状态。
     */
    fun setSubscriptionEnabled(id: String, enabled: Boolean, context: Context): Boolean {
        val list = loadSubscriptions(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(enabled = enabled)
        return saveSubscriptions(list, context)
    }

    /**
     * 获取所有已开启的订阅。
     */
    fun getEnabledSubscriptions(context: Context): List<Subscription> {
        return loadSubscriptions(context).filter { it.enabled }
    }

    // ========== 口令编解码 ==========

    /**
     * 生成订阅源口令。
     *
     * @param name 订阅源名称
     * @param type 订阅源类型
     * @param url  远程配置地址（type=URL 时）
     * @param contentJson 内嵌配置 JSON（type=CONTENT 时）
     */
    fun encodeToken(name: String, type: Type, url: String = "", contentJson: String = ""): String {
        val json = JSONObject()
        json.put("v", TOKEN_VERSION)
        json.put("name", name)
        json.put("type", if (type == Type.URL) "url" else "content")
        if (type == Type.URL && url.isNotBlank()) json.put("url", url)
        if (type == Type.CONTENT && contentJson.isNotBlank()) {
            json.put("content", JSONObject(contentJson))
        }
        json.put("ts", System.currentTimeMillis())
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        return TOKEN_PREFIX + encoded
    }

    /**
     * 解析订阅源口令。
     *
     * @return 解析成功返回 Token，非法口令返回 null。
     */
    fun decodeToken(token: String): Token? {
        val trimmed = token.trim()
        if (!trimmed.startsWith(TOKEN_PREFIX)) return null
        return try {
            val base64 = trimmed.removePrefix(TOKEN_PREFIX)
            val jsonBytes = Base64.decode(base64, Base64.NO_WRAP or Base64.NO_PADDING)
            val json = JSONObject(String(jsonBytes, StandardCharsets.UTF_8))
            val name = json.optString("name", "未命名订阅")
            val type = if (json.optString("type") == "url") Type.URL else Type.CONTENT
            val url = json.optString("url", "")
            val contentJson = if (json.has("content")) json.getJSONObject("content").toString() else ""
            Token(name = name, type = type, url = url, contentJson = contentJson)
        } catch (_: Exception) {
            null
        }
    }

    // ========== 远程拉取 ==========

    /**
     * 从远程地址拉取配置 JSON 字符串（同步调用，需在子线程执行）。
     *
     * @return 拉取成功返回 JSON 字符串，失败返回 null。
     */
    fun fetchRemoteConfig(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "APKAdRemoverEditor/2.0")
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                }
                sb.toString()
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 校验远程返回的字符串是否为合法配置 JSON。
     */
    fun isValidConfigJson(jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            // 至少包含一个已知配置字段才算合法
            json.has("sdk_packages") || json.has("class_keywords") ||
                json.has("method_patterns") || json.has("url_patterns")
        } catch (_: Exception) {
            false
        }
    }
}
