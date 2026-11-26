package com.mukimov.salom

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal enum class PreviewKind { PHOTO, PDF, TEXT, MARKDOWN }

internal fun Attachment.previewKind(): PreviewKind? {
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        type.startsWith("image/") && type != "image/svg+xml" -> PreviewKind.PHOTO
        type == "application/pdf" -> PreviewKind.PDF
        type == "text/markdown" || type == "text/x-markdown" -> PreviewKind.MARKDOWN
        type == "text/plain" && extension in setOf("md", "markdown") -> PreviewKind.MARKDOWN
        type == "text/plain" -> PreviewKind.TEXT
        type == "application/octet-stream" -> when (extension) {
            "pdf" -> PreviewKind.PDF
            "txt" -> PreviewKind.TEXT
            "md", "markdown" -> PreviewKind.MARKDOWN
            "png", "jpg", "jpeg", "webp", "gif", "heic", "avif" -> PreviewKind.PHOTO
            else -> null
        }
        else -> null
    }
}

private sealed interface PreviewContent {
    data class Photo(val image: Bitmap) : PreviewContent
    data class Pdf(val path: File, val pages: Int) : PreviewContent
    data class Text(val path: File) : PreviewContent
}

private inline fun <T> File.withPdf(block: (PdfRenderer) -> T): T {
    val descriptor = ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try { PdfRenderer(descriptor) } catch (failure: Exception) {
        descriptor.close()
        throw failure
    }
    return renderer.use(block)
}

@Composable
internal fun AttachmentViewer(attachment: Attachment, media: MediaFiles, onClose: () -> Unit) {
    val kind = attachment.previewKind() ?: return
    var preview by remember(attachment) { mutableStateOf<PreviewContent?>(null) }
    var error by remember(attachment) { mutableStateOf<String?>(null) }
    var attempt by remember(attachment) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attachment, attempt) {
        error = null
        try {
            preview = when (kind) {
                PreviewKind.PHOTO -> PreviewContent.Photo(media.bitmap(attachment, 2400)
                    ?: throw DirectoryException("Не удалось прочитать изображение"))
                PreviewKind.PDF -> withContext(Dispatchers.IO) {
                    val file = media.file(attachment)
                    PreviewContent.Pdf(file, file.withPdf { it.pageCount })
                }
                PreviewKind.TEXT, PreviewKind.MARKDOWN -> PreviewContent.Text(media.file(attachment))
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = (failure as? DirectoryException)?.message ?: "Файл повреждён или защищён паролем"
        }
    }
    PreviewScreen(attachment.name, kind == PreviewKind.PHOTO, onClose) {
        when (val content = preview) {
            is PreviewContent.Photo -> ZoomablePhoto(content.image, attachment.name)
            is PreviewContent.Pdf -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(content.pages) { index -> PdfPage(content.path, index, content.pages) }
            }
            is PreviewContent.Text -> TextPreview(content, kind == PreviewKind.MARKDOWN)
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error == null) CircularProgressIndicator(color = Yellow)
                else Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = if (kind == PreviewKind.PHOTO) Color.White else MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = { attempt++ }) {
                        Text("Повторить", color = if (kind == PreviewKind.PHOTO) Yellow else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try { media.open(attachment) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                error = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл"
                            }
                        }
                    }) {
                        Text("Открыть в другом приложении",
                            color = if (kind == PreviewKind.PHOTO) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PreviewScreen(
    title: String, dark: Boolean, onClose: () -> Unit,
    trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val background = if (dark) Color(0xFF101217) else MaterialTheme.colorScheme.background
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onBackground
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Назад" }) {
                    ChatGlyph("back", foreground)
                }
                Text(title, Modifier.weight(1f), color = foreground, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                trailing?.invoke()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
internal fun ZoomablePhoto(bitmap: Bitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val viewport = with(density) { Offset(maxWidth.toPx(), maxHeight.toPx()) }
        val imageScale = minOf(viewport.x / bitmap.width, viewport.y / bitmap.height)
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val x = ((bitmap.width * imageScale * scale - viewport.x) / 2).coerceAtLeast(0f)
            val y = ((bitmap.height * imageScale * scale - viewport.y) / 2).coerceAtLeast(0f)
            offset = Offset((offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y))
        }
        Box(Modifier.fillMaxSize().transformable(state)) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, description, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun PdfPage(file: File, index: Int, total: Int) {
    var page by remember(file, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file, index) { mutableStateOf(false) }
    var attempt by remember(file, index) { mutableIntStateOf(0) }
    LaunchedEffect(file, index, attempt) {
        failed = false
        try {
            page = withContext(Dispatchers.IO) {
                file.withPdf { pdf ->
                    pdf.openPage(index).use { source ->
                        val scale = minOf(1100f / source.width, 1550f / source.height)
                        val image = Bitmap.createBitmap((source.width * scale).roundToInt().coerceAtLeast(1),
                            (source.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        source.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image
                    }
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { failed = true }
    }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val image = page
            if (image != null) Image(remember(image) { image.asImageBitmap() }, "Страница ${index + 1} из $total",
                Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                if (failed) TextButton(onClick = { attempt++ }) { Text("Повторить загрузку страницы") }
                else CircularProgressIndicator(Modifier.size(24.dp))
            }
            Text("${index + 1} / $total", Modifier.padding(7.dp), fontSize = 12.sp)
        }
    }
}

@Composable
private fun TextPreview(content: PreviewContent.Text, markdown: Boolean) {
    var page by remember(content) { mutableIntStateOf(0) }
    var current by remember(content, page) { mutableStateOf<TextPage?>(null) }
    var failed by remember(content, page) { mutableStateOf(false) }
    var attempt by remember(content, page) { mutableIntStateOf(0) }
    LaunchedEffect(content, page, attempt) {
        failed = false
        try { current = withContext(Dispatchers.IO) { content.path.readTextPage(page) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val textPage = current
    if (textPage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) TextButton(onClick = { attempt++ }) { Text("Повторить загрузку текста") }
            else CircularProgressIndicator()
        }
        return
    }
    val lines = remember(textPage, markdown) {
        var inCode = false
        textPage.lines.mapNotNull { line ->
            if (markdown && line.trimStart().startsWith("```")) {
                inCode = !inCode
                null
            } else {
                val heading = if (markdown && !inCode) MarkdownHeading.find(line) else null
                val display = when {
                    heading != null -> heading.groupValues[2]
                    markdown && !inCode && line.startsWith("- ") -> "• " + line.drop(2)
                    else -> line
                }
                Triple(display, heading?.groupValues?.get(1)?.length ?: 0, inCode || !markdown)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (page > 0 || textPage.hasMore) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Назад") }
                Text("Часть ${page + 1}", fontSize = 13.sp)
                TextButton(onClick = { page++ }, enabled = textPage.hasMore) { Text("Далее") }
            }
            HorizontalDivider()
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
                items(lines) { (text, heading, code) ->
                    Text(text.ifEmpty { " " }, fontSize = (if (heading == 1) 24 else if (heading > 0) 19 else 15).sp,
                        lineHeight = 24.sp, fontWeight = if (heading > 0) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
                }
            }
        }
    }
}

internal data class TextPage(val lines: List<String>, val hasMore: Boolean)
private const val TextPageChars = 96 * 1024

internal fun File.readTextPage(page: Int): TextPage {
    return inputStream().buffered().use { input ->
        input.mark(2)
        val first = input.read()
        val second = input.read()
        input.reset()
        val utf16 = (first == 255 && second == 254) || (first == 254 && second == 255)
        input.reader(if (utf16) Charsets.UTF_16 else Charsets.UTF_8).buffered().use { reader ->
            var skip = if (page > 0) page.toLong() * TextPageChars - 1 else 0
            while (skip > 0) {
                val skipped = reader.skip(skip)
                if (skipped == 0L) break
                skip -= skipped
            }
            val carry = if (page > 0) {
                val previous = reader.read()
                reader.mark(1)
                val firstInPage = reader.read()
                if (previous >= 0 && firstInPage >= 0 &&
                    previous.toChar().isHighSurrogate() && firstInPage.toChar().isLowSurrogate()) 1
                else {
                    reader.reset()
                    0
                }
            } else 0
            val buffer = CharArray(TextPageChars - carry)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            val lookahead = reader.read()
            val continuesCharacter = count > 0 && lookahead >= 0 &&
                buffer[count - 1].isHighSurrogate() && lookahead.toChar().isLowSurrogate()
            val hasMore = if (continuesCharacter) reader.read() >= 0 else lookahead >= 0
            val text = (String(buffer, 0, count) + if (continuesCharacter) lookahead.toChar().toString() else "")
                .let { if (page == 0) it.removePrefix("\uFEFF") else it }
            val lines = text.lineSequence().flatMap { line ->
                if (line.length <= 2048) sequenceOf(line) else sequence {
                    var start = 0
                    while (start < line.length) {
                        val boundary = minOf(start + 2048, line.length)
                        val end = if (boundary < line.length && line[boundary - 1].isHighSurrogate() &&
                            line[boundary].isLowSurrogate()) boundary + 1 else boundary
                        yield(line.substring(start, end))
                        start = end
                    }
                }
            }.toList()
            TextPage(lines, hasMore)
        }
    }
}

private val MarkdownHeading = Regex("^(#{1,6}) +(.+)$")
