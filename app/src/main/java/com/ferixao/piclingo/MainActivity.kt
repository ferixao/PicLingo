package com.ferixao.piclingo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.min

private data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float, val translated: String)
private data class PicItem(
    val uri: Uri, val text: String = "", val translated: String = "", val regions: List<Region> = emptyList(),
    val imageWidth: Int = 0, val imageHeight: Int = 0, val busy: Boolean = false, val error: String? = null
)
private data class Lang(val label: String, val code: String)
private val languages = listOf(
    Lang("English", "en"), Lang("فارسی", "fa"), Lang("العربية", "ar"), Lang("Русский", "ru"),
    Lang("Türkçe", "tr"), Lang("Deutsch", "de"), Lang("Français", "fr"), Lang("Español", "es"),
    Lang("日本語", "ja"), Lang("한국어", "ko"), Lang("中文", "zh"), Lang("हिन्दी", "hi")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PicLingoApp() }
    }
}

private fun createRecognizer(code: String): TextRecognizer = when (code) {
    "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicLingoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF080B18) else Color(0xFFF4F6FC)
    val panel = if (dark) Color(0xFF151A2D) else Color.White
    val alt = if (dark) Color(0xFF202741) else Color(0xFFE9EDFA)
    val fg = if (dark) Color(0xFFF5F7FF) else Color(0xFF171B2D)
    val muted = if (dark) Color(0xFFAAB3CC) else Color(0xFF626C83)
    val cyan = if (dark) Color(0xFF22D3EE) else Color(0xFF087F9B)
    val purple = if (dark) Color(0xFF9B6BFF) else Color(0xFF7042D8)

    var items by remember { mutableStateOf(emptyList<PicItem>()) }
    var page by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf(languages[0]) }
    var target by remember { mutableStateOf(languages[1]) }
    var allBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var fa by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val accepted = uris.take((100 - items.size).coerceAtLeast(0))
            items = items + accepted.map { PicItem(it) }
            page = (items.size - 1).coerceAtLeast(0)
            message = if (accepted.size < uris.size) {
                if (fa) "حداکثر ۱۰۰ تصویر قابل انتخاب است" else "Maximum 100 images"
            } else ""
        }
    }

    suspend fun translateBlocks(blocks: List<Text.TextBlock>): List<Region> {
        if (blocks.isEmpty()) return emptyList()
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source.code).setTargetLanguage(target.code).build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            blocks.mapNotNull { block ->
                val rect = block.boundingBox ?: return@mapNotNull null
                val translated = translator.translate(block.text).await()
                if (translated.isBlank()) null else Region(
                    rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), translated
                )
            }
        } finally {
            translator.close()
        }
    }

    suspend fun process(index: Int) {
        if (index !in items.indices) return
        val old = items[index]
        items = items.toMutableList().also { it[index] = old.copy(busy = true, error = null) }
        try {
            // ML Kit's bundled OCR supports Latin, Chinese, Devanagari, Japanese and Korean scripts.
            // Persian, Arabic and Cyrillic source OCR need a different OCR engine.
            if (source.code in listOf("fa", "ar", "ru")) {
                throw IllegalArgumentException(
                    if (fa) "تشخیص متن فارسی، عربی و روسی در موتور OCR فعلی پشتیبانی نمی‌شود. زبان مبدأ را به یکی از زبان‌های پشتیبانی‌شده تغییر بده."
                    else "The current OCR engine does not recognize Persian, Arabic or Cyrillic source text."
                )
            }
            val image = InputImage.fromFilePath(context, old.uri)
            val client = createRecognizer(source.code)
            val result = try { client.process(image).await() } finally { client.close() }
            val regions = translateBlocks(result.textBlocks)
            items = items.toMutableList().also {
                it[index] = old.copy(
                    text = result.text,
                    translated = regions.joinToString("\n") { region -> region.translated },
                    regions = regions,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    busy = false
                )
            }
        } catch (e: Exception) {
            items = items.toMutableList().also {
                it[index] = old.copy(busy = false, error = e.localizedMessage ?: "Processing failed")
            }
        }
    }

    val scheme = if (dark) {
        darkColorScheme(primary = purple, secondary = cyan, background = bg, surface = panel, onBackground = fg, onSurface = fg)
    } else {
        lightColorScheme(primary = purple, secondary = cyan, background = bg, surface = panel, onBackground = fg, onSurface = fg)
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("PicLingo", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = fg)
                            Text(if (fa) "ترجمه هوشمند تصاویر" else "Smart image translation", fontSize = 11.sp, color = muted)
                        }
                    },
                    actions = {
                        TextButton(onClick = { fa = !fa }) {
                            Text(if (fa) "EN" else "فا", color = cyan, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
                )
            },
            bottomBar = {
                if (items.isNotEmpty()) {
                    Surface(color = panel, tonalElevation = 5.dp) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(items.getOrNull(page)?.translated.orEmpty()))
                                    message = if (fa) "ترجمه کپی شد" else "Copied"
                                },
                                enabled = items.getOrNull(page)?.translated?.isNotBlank() == true,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (fa) "کپی ترجمه" else "Copy")
                            }
                            Button(
                                onClick = {
                                    allBusy = true
                                    scope.launch {
                                        val pending = items.indices.filter { items[it].translated.isBlank() && !items[it].busy }
                                        for (i in pending) process(i)
                                        allBusy = false
                                    }
                                },
                                enabled = !allBusy && items.any { it.translated.isBlank() && !it.busy },
                                modifier = Modifier.weight(1.3f),
                                colors = ButtonDefaults.buttonColors(containerColor = purple)
                            ) {
                                if (allBusy) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Translate, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (fa) "ترجمه تصاویر" else "Translate")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LangMenu(if (fa) "زبان مبدأ" else "Source", source, { source = it }, Modifier.weight(1f))
                        IconButton(onClick = { val tmp = source; source = target; target = tmp }) {
                            Icon(Icons.Default.SwapHoriz, null, tint = cyan)
                        }
                        LangMenu(if (fa) "زبان مقصد" else "Target", target, { target = it }, Modifier.weight(1f))
                    }
                }

                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                Modifier.size(112.dp).clip(RoundedCornerShape(30.dp))
                                    .background(if (dark) Color(0xFF172B50) else Color(0xFFE3E9FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PhotoCamera, null, Modifier.size(60.dp), tint = purple)
                                Icon(Icons.Default.Translate, null, Modifier.align(Alignment.BottomEnd).padding(10.dp).size(28.dp), tint = cyan)
                            }
                            Text(
                                if (fa) "تصاویرت رو به هر زبانی ترجمه کن" else "Translate your images",
                                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg, textAlign = TextAlign.Center
                            )
                            Text(
                                if (fa) "تا ۱۰۰ تصویر انتخاب کن؛ ترجمه روی خود عکس نمایش داده می‌شه."
                                else "Select up to 100 images and see translations on them.",
                                color = muted, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { picker.launch(arrayOf("image/*")) },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = purple)
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (fa) "انتخاب تصاویر" else "Choose images", fontSize = 16.sp)
                            }
                            Text(if (fa) "پردازش با Google ML Kit" else "Powered by Google ML Kit", color = muted, fontSize = 11.sp)
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                (if (fa) "تصویر " else "Image ") + (page + 1) + (if (fa) " از " else " of ") + items.size,
                                color = fg, fontWeight = FontWeight.SemiBold
                            )
                            Text(if (fa) "برای جابه‌جایی، تصویر را ورق بزن" else "Swipe to browse", color = muted, fontSize = 11.sp)
                        }
                        Row {
                            IconButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                                Icon(Icons.Default.AddPhotoAlternate, "Add", tint = cyan)
                            }
                            IconButton(onClick = {
                                items = items.toMutableList().also { if (page in it.indices) it.removeAt(page) }
                                page = (page - 1).coerceAtLeast(0)
                            }) { Icon(Icons.Default.DeleteOutline, "Delete", tint = Color(0xFFE05A70)) }
                            IconButton(onClick = { items = emptyList(); page = 0; message = "" }) {
                                Icon(Icons.Default.DeleteSweep, "Clear", tint = muted)
                            }
                        }
                    }

                    val pager = rememberPagerState(
                        initialPage = page.coerceIn(0, items.lastIndex),
                        pageCount = { items.size }
                    )
                    LaunchedEffect(page, items.size) {
                        if (page in items.indices && pager.currentPage != page) pager.animateScrollToPage(page)
                    }
                    LaunchedEffect(pager.currentPage) { page = pager.currentPage }

                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { index ->
                        val item = items[index]
                        Card(
                            Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = panel)
                        ) {
                            Box(Modifier.fillMaxSize().padding(6.dp)) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = "Selected image",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                if (item.regions.isNotEmpty() && item.imageWidth > 0 && item.imageHeight > 0) {
                                    TranslationOverlay(item, Modifier.fillMaxSize())
                                }
                                if (item.busy) {
                                    Surface(
                                        Modifier.align(Alignment.Center),
                                        color = Color.Black.copy(alpha = .75f),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                                            Spacer(Modifier.width(10.dp))
                                            Text(if (fa) "در حال ترجمه..." else "Translating...", color = Color.White)
                                        }
                                    }
                                }
                                if (item.error != null) {
                                    Text(
                                        item.error,
                                        Modifier.align(Alignment.BottomCenter).padding(12.dp)
                                            .background(Color(0xFFE05A70), RoundedCornerShape(8.dp)).padding(10.dp),
                                        color = Color.White
                                    )
                                }
                                if (!item.busy && item.translated.isBlank() && item.error == null) {
                                    Button(
                                        onClick = { scope.launch { process(index) } },
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = purple)
                                    ) {
                                        Icon(Icons.Default.AutoFixHigh, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (fa) "ترجمه روی تصویر" else "Translate on image")
                                    }
                                }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        repeat(items.size.coerceAtMost(12)) { dot ->
                            Box(
                                Modifier.padding(horizontal = 3.dp)
                                    .size(if (dot == page.coerceAtMost(11)) 8.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (dot == page.coerceAtMost(11)) purple else alt)
                            )
                        }
                        if (items.size > 12) Text(" +" + (items.size - 12), color = muted, fontSize = 10.sp)
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, color = if (dark) Color(0xFFFFC857) else Color(0xFF9A6200), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TranslationOverlay(item: PicItem, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val scale = min(containerW / item.imageWidth.toFloat(), containerH / item.imageHeight.toFloat())
        val insetX = (containerW - item.imageWidth * scale) / 2f
        val insetY = (containerH - item.imageHeight * scale) / 2f

        item.regions.forEach { region ->
            val x = with(density) { (insetX + region.left * scale).toDp() }
            val y = with(density) { (insetY + region.top * scale).toDp() }
            val width = with(density) { ((region.right - region.left) * scale).coerceAtLeast(12f).toDp() }
            val height = with(density) { ((region.bottom - region.top) * scale).coerceAtLeast(12f).toDp() }
            val size = (height.value * .52f).coerceIn(8f, 17f)
            Box(
                Modifier.offset(x, y).width(width).height(height)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFF8F8F5).copy(alpha = .97f))
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    region.translated,
                    color = Color(0xFF171717),
                    fontSize = size.sp,
                    lineHeight = (size * 1.05f).sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Clip,
                    maxLines = 6
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LangMenu(label: String, selected: Lang, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.label) }, onClick = { onSelect(lang); expanded = false })
            }
        }
    }
}
