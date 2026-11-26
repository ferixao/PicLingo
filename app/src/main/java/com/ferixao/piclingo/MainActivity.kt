package com.ferixao.piclingo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class PicItem(val uri: Uri, val text: String = "", val translated: String = "", val busy: Boolean = false, val error: String? = null)
private data class Lang(val label: String, val code: String)
private val languages = listOf(
    Lang("English", "en"), Lang("فارسی", "fa"), Lang("العربية", "ar"),
    Lang("Русский", "ru"), Lang("Türkçe", "tr"), Lang("Deutsch", "de"),
    Lang("Français", "fr"), Lang("Español", "es"), Lang("日本語", "ja"),
    Lang("한국어", "ko"), Lang("中文", "zh")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PicLingoApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicLingoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val bg = Color(0xFF0B0B14)
    val panel = Color(0xFF171729)
    val purple = Color(0xFF8B5CF6)
    var items by remember { mutableStateOf(emptyList<PicItem>()) }
    var page by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf(languages[0]) }
    var target by remember { mutableStateOf(languages[1]) }
    var busyAll by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isPersian by remember { mutableStateOf(true) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            items = (items + uris.map { PicItem(it) }).take(100)
            page = items.lastIndex.coerceAtLeast(0)
            message = if (uris.size + items.size > 100) "حداکثر ۱۰۰ تصویر قابل انتخاب است" else ""
        }
    }

    suspend fun translateText(text: String): String {
        if (text.isBlank()) return ""
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source.code)
            .setTargetLanguage(target.code)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    suspend fun processOne(index: Int) {
        if (index !in items.indices) return
        val current = items[index]
        items = items.toMutableList().also { it[index] = current.copy(busy = true, error = null) }
        try {
            val image = InputImage.fromFilePath(context, current.uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val extracted = try { recognizer.process(image).await().text } finally { recognizer.close() }
            val translated = translateText(extracted)
            items = items.toMutableList().also { it[index] = current.copy(text = extracted, translated = translated, busy = false) }
        } catch (e: Exception) {
            items = items.toMutableList().also { it[index] = current.copy(busy = false, error = e.localizedMessage ?: "Processing failed") }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = purple, secondary = Color(0xFF22D3EE),
        background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = bg, topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PicLingo", fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                        Text(if (isPersian) "ترجمه گروهی تصاویر" else "Batch image translator", fontSize = 11.sp, color = Color.LightGray)
                    }
                },
                actions = {
                    TextButton(onClick = { isPersian = !isPersian }) {
                        Text(if (isPersian) "EN" else "فا", color = Color(0xFF22D3EE))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LanguageMenu("از / From", source, { source = it }, Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, null, Modifier.align(Alignment.CenterVertically), tint = Color(0xFF22D3EE))
                    LanguageMenu("به / To", target, { target = it }, Modifier.weight(1f))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = purple)) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPersian) "انتخاب تصاویر" else "Choose images")
                    }
                    OutlinedButton(onClick = {
                        if (items.isNotEmpty() && !busyAll) {
                            busyAll = true
                            scope.launch {
                                for (i in items.indices) if (items[i].translated.isBlank()) processOne(i)
                                busyAll = false
                            }
                        }
                    }, enabled = items.isNotEmpty() && !busyAll) {
                        if (busyAll) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Translate, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (isPersian) "ترجمه همه" else "Translate all")
                    }
                }

                if (items.isNotEmpty()) {
                    val pagerState = rememberPagerState(initialPage = page, pageCount = { items.size })
                    LaunchedEffect(page) { if (pagerState.currentPage != page) pagerState.animateScrollToPage(page) }
                    LaunchedEffect(pagerState.currentPage) { page = pagerState.currentPage }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isPersian) "تصویر ${page + 1} از ${items.size}" else "Image ${page + 1} of ${items.size}", color = Color.LightGray)
                        TextButton(onClick = { items = emptyList(); page = 0 }) { Text(if (isPersian) "پاک‌کردن همه" else "Clear all", color = Color(0xFFF87171)) }
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(0.42f)) { idx ->
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = panel), modifier = Modifier.fillMaxSize()) {
                            AsyncImage(model = items[idx].uri, contentDescription = "Selected image", modifier = Modifier.fillMaxSize())
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().weight(0.58f)) {
                        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(if (isPersian) "متن ترجمه‌شده" else "Translated text", fontWeight = FontWeight.Bold, color = Color(0xFF22D3EE))
                                Row {
                                    IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(items.getOrNull(page)?.translated.orEmpty())) }) {
                                        Icon(Icons.Default.ContentCopy, if (isPersian) "کپی" else "Copy")
                                    }
                                    IconButton(onClick = { items = items.toMutableList().also { it.removeAt(page) }; page = (page - 1).coerceAtLeast(0) }) {
                                        Icon(Icons.Default.DeleteOutline, if (isPersian) "حذف" else "Delete", tint = Color(0xFFF87171))
                                    }
                                }
                            }
                            val current = items.getOrNull(page)
                            when {
                                current == null -> Text(if (isPersian) "تصویری انتخاب نشده" else "No image selected", color = Color.LightGray)
                                current.busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(if (isPersian) "در حال OCR و ترجمه..." else "Reading and translating...") }
                                current.error != null -> Text(current.error, color = Color(0xFFF87171))
                                current.translated.isNotBlank() -> SelectionContainer { Text(current.translated, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) }
                                else -> {
                                    Text(if (isPersian) "برای استخراج متن و ترجمه این تصویر، دکمه زیر را بزن." else "Tap below to extract and translate this image.", color = Color.LightGray)
                                    Button(onClick = { scope.launch { processOne(page) } }, colors = ButtonDefaults.buttonColors(containerColor = purple)) {
                                        Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text(if (isPersian) "استخراج و ترجمه" else "OCR & Translate")
                                    }
                                }
                            }
                            if (current?.text?.isNotBlank() == true) {
                                Divider(color = Color(0xFF34344A))
                                Text(if (isPersian) "متن اصلی" else "Original text", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.LightGray)
                                SelectionContainer { Text(current.text, fontSize = 12.sp, color = Color.LightGray) }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Collections, null, Modifier.size(64.dp), tint = purple)
                            Text(if (isPersian) "تا ۱۰۰ تصویر انتخاب کن و یک‌جا ترجمه‌شون کن" else "Select up to 100 images and translate them together", color = Color.LightGray)
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, color = Color(0xFFFBBF24), fontSize = 12.sp)
                Text("OCR & Translation powered by Google ML Kit", color = Color(0xFF77778F), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageMenu(label: String, selected: Lang, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selected.label, onValueChange = {}, readOnly = true, label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { lang -> DropdownMenuItem(text = { Text(lang.label) }, onClick = { onSelect(lang); expanded = false }) }
        }
    }
}
