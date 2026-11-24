package ru.kitai.app

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: Settings, onSave: (Settings) -> Unit) {
    var key by remember { mutableStateOf("") }
    var clearKey by rememberSaveable { mutableStateOf(false) }
    var theme by rememberSaveable { mutableStateOf(settings.theme) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Перевод через OpenRouter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.take(1_024); clearKey = false; error = null },
                label = { Text("Ключ OpenRouter") },
                placeholder = {
                    Text(if (settings.openRouterKey.isNotEmpty() && !clearKey) "Ключ сохранён" else "sk-or-…")
                },
                singleLine = true,
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text(error ?: "Kitai использует бесплатные модели. Ключ хранится на устройстве в зашифрованном виде.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.openRouterKey.isNotEmpty()) {
                TextButton(onClick = { clearKey = !clearKey; key = ""; error = null }) {
                    Text(if (clearKey) "Оставить сохранённый ключ" else "Удалить сохранённый ключ")
                }
            }
        }
        item {
            Text("Тема", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { option ->
                    FilterChip(selected = theme == option, onClick = { theme = option }, label = { Text(option.title) })
                }
            }
        }
        item {
            Button(
                onClick = {
                    val updatedKey = key.trim().ifEmpty { settings.openRouterKey.takeUnless { clearKey }.orEmpty() }
                    error = if (updatedKey.isNotEmpty() && !validOpenRouterKey(updatedKey)) {
                        "Вставьте ключ API OpenRouter целиком."
                    } else null
                    if (error == null) onSave(Settings(theme, updatedKey))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить настройки") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationScreen(model: KitaiViewModel, settings: Settings, openSettings: () -> Unit) {
    val state by model.translation.collectAsStateWithLifecycle()
    val upload by model.upload.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var picture by rememberSaveable { mutableStateOf(false) }
    var direction by rememberSaveable { mutableStateOf(Direction.ZH_RU) }
    var text by rememberSaveable { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableIntStateOf(-1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            reading = true
            highlighted = -1
            localError = null
            model.setUpload(null)
            scope.launch {
                try {
                    model.setUpload(withContext(Dispatchers.IO) { loadUpload(resolver, uri) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    localError = if (failure is IllegalArgumentException) failure.message
                    else "Не удалось прочитать изображение."
                } finally {
                    reading = false
                }
            }
        }
    }
    LazyColumn(state = listState, contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Перевод", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !picture, onClick = { picture = false; model.resetTranslation() }, label = { Text("Текст") })
                FilterChip(selected = picture, onClick = { picture = true; model.resetTranslation() }, label = { Text("Изображение") })
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Direction.entries.forEach { option ->
                    FilterChip(
                        selected = direction == option,
                        onClick = { direction = option; model.resetTranslation() },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        if (picture || settings.openRouterKey.isEmpty()) item {
            val title = if (picture) "Распознавание на устройстве" else "Перевод через OpenRouter"
            val message = if (picture) "Для перевода в OpenRouter отправляется только распознанный текст."
            else "Добавьте ключ OpenRouter в настройках."
            InfoCard(title, message) {
                if (settings.openRouterKey.isEmpty()) {
                    TextButton(onClick = openSettings) { Text("Добавить ключ") }
                }
            }
        }
        if (picture) {
            item {
                OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !reading) { Text("Выбрать изображение") }
                Text("JPEG, PNG или WebP · до 5 МБ", style = MaterialTheme.typography.bodySmall)
            }
            val image = upload
            if (image != null) item(key = "ocr-preview") { OcrPreview(image, state.image, highlighted) }
            item {
                Button(
                    onClick = { upload?.let { highlighted = -1; model.recognize(it, direction) } },
                    enabled = upload != null && !reading && !state.loading,
                ) { Text(if (settings.openRouterKey.isEmpty()) "Распознать" else "Распознать и перевести") }
            }
            localError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.image?.blocks?.isEmpty() == true) item { InfoCard("Текст не найден", "Попробуйте более чёткое изображение.") }
            state.image?.blocks?.let { blocks ->
                itemsIndexed(blocks) { index, block ->
                    InfoCard("Фрагмент ${index + 1}", null) {
                        SelectionContainer {
                            Column {
                                Text(block.text, style = MaterialTheme.typography.bodyMedium)
                                block.translation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        if (block.box != null) {
                            TextButton(onClick = {
                                highlighted = if (highlighted == index) -1 else index
                                if (highlighted >= 0) {
                                    scope.launch {
                                        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "ocr-preview" }
                                        listState.animateScrollToItem(visible?.index ?: 5)
                                    }
                                }
                            }) {
                                Text(if (highlighted == index) "Снять выделение" else "Выделить на изображении")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(5000); model.resetTranslation() },
                    label = { Text("Исходный текст") },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { model.translate(text.trim(), direction) },
                    enabled = text.isNotBlank() && !state.loading && settings.openRouterKey.isNotEmpty(),
                ) { Text("Перевести") }
            }
            state.text?.let { result ->
                item { InfoCard("Перевод", null) { SelectionContainer { Text(result) } } }
            }
        }
        if (state.loading || reading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun OcrPreview(upload: UploadImage, result: OcrResult?, highlighted: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(240.dp)) {
        AsyncImage(
            model = upload.bytes, contentDescription = "Выбранное изображение",
            contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize(),
        )
        if (result != null) {
            Canvas(Modifier.matchParentSize()) {
                val scale = min(size.width / result.width, size.height / result.height)
                val centerOffset = Offset((size.width - result.width * scale) / 2, (size.height - result.height * scale) / 2)
                result.blocks.forEachIndexed { index, block ->
                    val box = block.box ?: return@forEachIndexed
                    val top = centerOffset + Offset(box.x * scale, box.y * scale)
                    val area = Size(box.width * scale, box.height * scale)
                    if (index == highlighted) drawRect(Color(0x66FFCC00), top, area)
                    drawRect(Color(0xFFFFC226), top, area, style = Stroke(if (index == highlighted) 4.dp.toPx() else 2.dp.toPx()))
                }
            }
            val selected = result.blocks.getOrNull(highlighted)
            val box = selected?.box
            if (box != null) {
                val scale = min(maxWidth.value / result.width, maxHeight.value / result.height)
                val left = (maxWidth.value - result.width * scale) / 2 + box.x * scale
                val top = (maxHeight.value - result.height * scale) / 2 + box.y * scale
                val width = min(220f, maxWidth.value)
                val x = left.coerceIn(0f, maxWidth.value - width)
                val below = top + box.height * scale + 4
                val y = if (below + 56 <= maxHeight.value) below else (top - 56).coerceAtLeast(0f)
                Surface(
                    modifier = Modifier.offset(x.dp, y.dp).widthIn(max = width.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        selected.translation ?: selected.text,
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun loadUpload(resolver: ContentResolver, uri: Uri): UploadImage {
    require(uri.scheme == "content") { "Выберите изображение из файлов устройства." }
    val mime = resolver.getType(uri)?.lowercase()
    require(mime in setOf("image/jpeg", "image/png", "image/webp")) {
        "Выберите изображение JPEG, PNG или WebP."
    }
    val maximum = 5 * 1024 * 1024
    val bytes = requireNotNull(resolver.openInputStream(uri)) { "Файл недоступен." }.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var count = input.read(chunk)
        while (count != -1) {
            require(output.size() + count <= maximum) { "Изображение должно занимать не больше 5 МБ." }
            output.write(chunk, 0, count)
            count = input.read(chunk)
        }
        output.toByteArray()
    }
    require(bytes.isNotEmpty()) { "Файл изображения пуст." }
    return UploadImage(bytes)
}
