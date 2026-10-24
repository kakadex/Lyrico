package com.lonx.lyrico.data.repository

import android.util.Log
import com.lonx.lyrico.BuildConfig
import com.lonx.lyrico.data.dto.GitHubReleaseDTO
import com.lonx.lyrico.data.dto.ReleaseInfo
import com.lonx.lyrico.data.model.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

class UpdateRepositoryImpl(
    private val json: Json,
    private val okHttpClient: OkHttpClient
) : UpdateRepository {

    private val TAG = "UpdateRepositoryImpl"

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun checkForUpdate(
        owner: String,
        repo: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "开始检查更新（Gitee raw 优先，GitHub 兜底）")

        // ① Gitee 优先：读 release-assets 分支的 latest-version.txt（raw 静态文件，
        //    国内直连、免令牌、无 API 限流），命中则直接给 APK 附件直链。
        //    404（尚无元数据）/超时/解析失败都静默回落 GitHub。
        val gitee = runCatching { fetchGiteeRaw(owner, repo) }
        gitee.getOrNull()?.let {
            Log.d(TAG, "Gitee 命中，使用 Gitee 结果")
            return@withContext it
        }
        Log.d(TAG, "Gitee 未命中（${gitee.exceptionOrNull()}），回落 GitHub")

        // ② GitHub 兜底（错误语义与原实现一致）
        try {
            fetchRelease("https://api.github.com/repos/$owner/$repo/releases/latest")
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "连接超时", e)
            UpdateCheckResult.TimeoutError

        } catch (e: IOException) {
            Log.e(TAG, "更新检查网络错误", e)
            UpdateCheckResult.NetworkError(e)

        } catch (e: SerializationException) {
            Log.e(TAG, "JSON 解析错误", e)
            UpdateCheckResult.NetworkError(IOException("数据解析失败"))
        }
    }

    /** 读 Gitee release-assets 分支的 latest-version.txt / notes.md（raw 静态文件）。 */
    private fun fetchGiteeRaw(owner: String, repo: String): UpdateCheckResult {
        val base = "https://gitee.com/$owner/$repo/raw/release-assets"

        val meta = mutableMapOf<String, String>()
        getText("$base/latest-version.txt").lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) meta[line.take(idx).trim()] = line.substring(idx + 1).trim()
        }
        val remoteCode = meta["versionCode"]?.toIntOrNull()
            ?: throw IOException("Gitee 元数据缺少 versionCode")
        val apkName = meta["apk"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("Gitee 元数据缺少 apk 文件名")
        val versionName = meta["versionName"] ?: remoteCode.toString()

        Log.d(TAG, "Gitee 最新: $versionName (versionCode $remoteCode)，当前 ${BuildConfig.VERSION_CODE}")

        if (remoteCode <= BuildConfig.VERSION_CODE) {
            return UpdateCheckResult.NoUpdateAvailable
        }

        // 更新说明直接读同分支的 notes.md（读不到就给空说明，不阻断）
        val notes = runCatching { getText("$base/notes.md") }.getOrDefault("")

        // 下载地址：元数据里的 url 字段（发版脚本写入，指向 Gitee Release 页面/附件直链）；
        // 缺省时按 Gitee Release 页面规则拼。注意 Gitee raw 对 >1MB 文件强制登录，
        // 所以 APK 本体不能走 raw，必须走 Release 附件。
        val downloadUrl = meta["url"]?.takeIf { it.isNotBlank() }
            ?: "https://gitee.com/$owner/$repo/releases/tag/v$versionName"

        return UpdateCheckResult.NewVersion(
            ReleaseInfo(
                versionName = versionName,
                releaseNotes = notes,
                url = downloadUrl
            )
        )
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lyrico-App")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    /** GitHub Releases API 兜底路径（保留原有错误语义）。 */
    private fun fetchRelease(url: String): UpdateCheckResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lyrico-App")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val release = json.decodeFromStream<GitHubReleaseDTO>(
                response.body.byteStream()
            )

            val latestVersionName = release.tag_name
            val releaseNotes = release.body.orEmpty()
            val downloadUrl = release.html_url

            Log.d(
                TAG,
                "最新版本: $latestVersionName 当前版本: ${BuildConfig.VERSION_NAME}"
            )

            val hasUpdate = isNewerVersion(
                latestVersionName,
                BuildConfig.VERSION_NAME
            )

            if (hasUpdate) {
                UpdateCheckResult.NewVersion(
                    ReleaseInfo(
                        versionName = latestVersionName,
                        releaseNotes = releaseNotes,
                        url = downloadUrl
                    )
                )
            } else {
                UpdateCheckResult.NoUpdateAvailable
            }
        }
    }

    /**
     * 版本比较
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun normalize(version: String): List<Int> {
            return version
                .trim()
                .removePrefix("v")
                .substringBefore("-")
                .substringBefore("+")
                .split(".")
                .map { it.toIntOrNull() ?: 0 }
        }

        val lParts = normalize(latest)
        val cParts = normalize(current)

        Log.d(TAG, "比较版本: $lParts vs $cParts")

        val max = maxOf(lParts.size, cParts.size)

        for (i in 0 until max) {
            val lv = lParts.getOrElse(i) { 0 }
            val cv = cParts.getOrElse(i) { 0 }

            when {
                lv > cv -> return true
                lv < cv -> return false
            }
        }
        return false
    }
}