package com.nexttodo.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AppRelease(val version: String, val url: String)

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    @Suppress("DEPRECATION")
    val currentVersion = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
    var checking by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var pendingRelease by mutableStateOf<AppRelease?>(null)
        private set

    init { checkForUpdates(manual = false) }

    fun dismiss() { pendingRelease = null }

    fun checkForUpdates(manual: Boolean = true) {
        if (checking) return
        checking = true
        status = "正在检查更新…"
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                if (release == null) {
                    status = "暂无正式发布版本"
                } else {
                    val installed = requireNotNull(ReleaseVersion.parse(currentVersion)) { "无法识别当前版本" }
                    val latest = requireNotNull(ReleaseVersion.parse(release.version)) { "无法识别发布版本" }
                    if (latest > installed) {
                        pendingRelease = release
                        status = "有新版本 ${release.version}"
                    } else status = "已是最新版本"
                }
            } catch (exception: CancellationException) { throw exception
            } catch (_: Exception) {
                status = if (manual) "检查失败，请稍后重试或查看 GitHub" else "暂时无法检查更新"
            } finally { checking = false }
        }
    }
}

internal fun fetchLatestRelease(): AppRelease? {
    val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "NextTodo-Android")
        return when (connection.responseCode) {
            404 -> null
            200 -> {
                val bytes = connection.inputStream.use { it.readBytesLimited(1024 * 1024) }
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                require(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
                val version = json.getString("tag_name")
                requireNotNull(ReleaseVersion.parse(version))
                val url = json.getString("html_url")
                require(isProjectReleaseUrl(url))
                AppRelease(version, url)
            }
            else -> error("GitHub returned ${connection.responseCode}")
        }
    } finally { connection.disconnect() }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
}

@Composable
internal fun UpdateSettings(model: AppUpdateViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("当前版本 ${model.currentVersion}", Modifier.weight(1f))
            TextButton(onClick = { model.checkForUpdates() }, enabled = !model.checking) {
                Text(if (model.checking) "检查中…" else "检查更新")
            }
        }
        if (model.status.isNotEmpty()) Text(model.status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { openRelease(context, RELEASES_URL) }) { Text("GitHub 发布页") }
    }
}

@Composable
internal fun UpdatePrompt(model: AppUpdateViewModel) {
    val release = model.pendingRelease ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text("发现新版本 ${release.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${model.currentVersion}，是否前往 GitHub 下载更新？")
                SelectionContainer { Text(release.url, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = {
            if (openRelease(context, release.url)) model.dismiss()
        }) { Text("去更新") } },
        dismissButton = { TextButton(onClick = model::dismiss) { Text("稍后") } },
    )
}

private fun openRelease(context: android.content.Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "无法打开浏览器，请复制链接访问 GitHub", Toast.LENGTH_LONG).show()
    false
}
