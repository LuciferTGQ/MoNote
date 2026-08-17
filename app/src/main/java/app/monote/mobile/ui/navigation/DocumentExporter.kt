package app.monote.mobile.ui.navigation

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun exportMarkdown(source: File, openOutput: () -> OutputStream?) = withContext(Dispatchers.IO) {
    if (!source.isFile) throw IOException("源文件已不存在")
    val output = openOutput() ?: throw IOException("无法打开导出位置")
    output.use { destination -> source.inputStream().use { input -> input.copyTo(destination) } }
}
