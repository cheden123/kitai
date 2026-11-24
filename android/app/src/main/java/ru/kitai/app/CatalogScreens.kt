package ru.kitai.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.math.BigDecimal

val screenPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    state: CatalogState,
    settings: Settings,
    model: KitaiViewModel,
    openOriginal: (Market, String) -> Unit,
    openSettings: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val search = { focus.clearFocus(); model.submit() }
    val products = remember(state.products, state.order, state.pricedOnly) { filteredProducts(state) }
    val validRublesDate = remember(state.products) {
        state.products.filter { it.priceRub?.toBigDecimalOrNull() != null }.mapNotNull { it.fxDate }.maxOrNull()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Каталог товаров", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Поиск по Taobao, 1688 и Alibaba.com",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = model::editQuery,
                label = { Text("Название товара") },
                placeholder = { Text("Например, настольная лампа") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Market.entries.forEach { market ->
                    val selected = market in state.sources
                    FilterChip(
                        selected = selected,
                        onClick = { model.toggleSource(market) },
                        enabled = !selected || state.sources.size > 1,
                        label = { Text(market.title) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = search, enabled = settings.address.isNotBlank() && state.query.isNotBlank()) { Text("Найти") }
                if (state.query.isNotBlank()) {
                    TextButton(
                        onClick = model::translateQuery,
                        enabled = settings.address.isNotBlank() && !state.translating,
                    ) { Text(if (state.translating) "Переводим…" else "Перевести на китайский") }
                }
            }
        }
        state.queryTranslation?.let { translation ->
            item {
                InfoCard("Перевод запроса", translation) {
                    TextButton(onClick = { focus.clearFocus(); model.submitTranslation() }) { Text("Искать по переводу") }
                }
            }
        }
        state.translationError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (settings.address.isBlank()) {
            item {
                InfoCard("Подключите сервер", "Укажите адрес Kitai API, чтобы получить предложения площадок.") {
                    TextButton(onClick = openSettings) { Text("Настроить сервер") }
                }
            }
        } else if (state.error != null) {
            item {
                InfoCard("Не удалось загрузить каталог", state.error) {
                    TextButton(onClick = { if (state.cursor == null) model.retry() else model.loadMore() }) {
                        Text("Повторить")
                    }
                }
            }
        }
        state.originalQuery?.let {
            item { Text("Исходный запрос: $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (state.products.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SortControl(state.order) { model.setFilters(order = it) }
                        FilterChip(
                            selected = state.pricedOnly,
                            onClick = { model.setFilters(pricedOnly = !state.pricedOnly) },
                            label = { Text("Есть цена") },
                        )
                    }
                    if (state.order == CatalogOrder.RUBLES && validRublesDate != null) {
                        Text(
                            "Курс на ${formatDay(validRublesDate)}. Остальные цены идут после.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.cursor != null && (state.order != CatalogOrder.DEFAULT || state.pricedOnly)) {
                        Text("Фильтры применяются к загруженным предложениям.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.loaded && state.products.isEmpty() && state.outcomes.values.any { it.status == "available" }) {
            item { InfoCard("Совпадений нет", "Измените название товара или выберите другие площадки.") }
        }
        if (state.products.isNotEmpty() && products.isEmpty()) {
            item { InfoCard("Нет предложений с ценой", "Сбросьте фильтр или загрузите следующую страницу.") }
        }
        items(products, key = Product::key) { product ->
            ProductCard(product) { model.openProduct(product) }
        }
        if (state.cursor != null && state.error == null) {
            item {
                OutlinedButton(onClick = model::loadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Загрузить ещё")
                }
            }
        }
        item { Text("Состояние площадок", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(Market.entries.filter { it in state.sources }, key = Market::id) { market ->
            val status = state.outcomes[market.id]
            val label = status?.description ?: when {
                settings.address.isBlank() -> "Адрес сервера не указан"
                state.loading -> "Проверяем подключение…"
                else -> "Статус неизвестен"
            }
            Surface(
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(market.title, style = MaterialTheme.typography.titleSmall)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { openOriginal(market, market.homepage) }) { Text("Сайт") }
                }
            }
        }
    }
}

private fun filteredProducts(state: CatalogState): List<Product> {
    val list = if (state.pricedOnly) state.products.filter { it.priceMin?.toBigDecimalOrNull() != null }
    else state.products
    val key: (Product) -> BigDecimal? = when (state.order) {
        CatalogOrder.DEFAULT -> return list
        CatalogOrder.MINIMUM -> { product -> product.moq?.toBigDecimalOrNull() }
        CatalogOrder.RUBLES -> {
            val rateDate = list.filter { it.priceRub?.toBigDecimalOrNull() != null }.mapNotNull { it.fxDate }.maxOrNull()
            val sameDate: (Product) -> BigDecimal? = { product ->
                product.priceRub?.takeIf { rateDate != null && product.fxDate == rateDate }?.toBigDecimalOrNull()
            }
            sameDate
        }
    }
    return list.sortedWith(compareBy<Product, BigDecimal?>(nullsLast(), key))
}

@Composable
private fun SortControl(order: CatalogOrder, onChoice: (CatalogOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(order.title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CatalogOrder.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.title) }, onClick = { expanded = false; onChoice(choice) })
            }
        }
    }
}

@Composable
fun InfoCard(title: String, message: String?, actions: @Composable () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            actions()
        }
    }
}

@Composable
fun ProductCard(product: Product, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            product.imageUrl?.takeIf { secureUri(it) != null }?.let { image ->
                AsyncImage(
                    model = image, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(78.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(product.market?.title.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(
                    product.displayTitle, style = MaterialTheme.typography.titleMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (!product.isLinkOnly) {
                    Text(product.originalPrice(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    product.rubles()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    product.moq?.let { Text("Минимум: ${formatNumber(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductDetailScreen(
    detail: DetailState,
    saved: BookmarkedProduct?,
    onMark: (SavedAction, Boolean) -> Unit,
    openOriginal: (Market, String) -> Unit,
) {
    val product = detail.product
    LazyColumn(
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(13.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (detail.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val image = product.imageUrl?.takeIf { secureUri(it) != null }
        if (image != null) {
            item {
                AsyncImage(
                    model = image, contentDescription = "Фотография товара", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)),
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.market?.title.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(product.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (product.displayTitle != product.title) Text(product.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!product.isLinkOnly) {
            item {
                InfoCard(product.originalPrice(), product.rubles()) {
                    product.fxDate?.let { Text("Курс на ${formatDay(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailField("Минимальная партия", product.moq?.let(::formatNumber))
                    DetailField("Продавец", product.seller)
                    DetailField("Данные получены", formatObservation(product.observedAt))
                }
            }
        }
        detail.error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMark(SavedAction.FAVORITE, saved?.favorite != true) }) {
                    Text(if (saved?.favorite == true) "Убрать из избранного" else "В избранное")
                }
                OutlinedButton(onClick = { onMark(SavedAction.COMPARE, saved?.comparison != true) }) {
                    Text(if (saved?.comparison == true) "Убрать из сравнения" else "Сравнить")
                }
            }
        }
        val market = product.market
        if (market != null && market.accepts(product.url)) {
            item {
                Button(onClick = { openOriginal(market, product.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть на ${market.title}")
                }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf(String::isNotBlank) ?: "Не указано", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SavedScreen(saved: List<BookmarkedProduct>, model: KitaiViewModel) {
    var history by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val list = saved.filter { if (history) it.viewed else it.favorite }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Очистить историю просмотров?") },
            confirmButton = { TextButton(onClick = { confirm = false; model.clearHistory() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Сохранено", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !history, onClick = { history = false }, label = { Text("Избранное") })
                FilterChip(selected = history, onClick = { history = true }, label = { Text("История") })
            }
        }
        if (list.isEmpty()) item {
            InfoCard(
                if (history) "История пуста" else "В избранном пока пусто",
                if (history) "Здесь появятся открытые предложения." else "Сохраняйте предложения из карточек товаров.",
            )
        }
        if (history && list.isNotEmpty()) item {
            TextButton(onClick = { confirm = true }) { Text("Очистить историю") }
        }
        items(list, key = { it.product.key }) { row ->
            ProductCard(row.product) { model.openProduct(row.product) }
        }
    }
}

@Composable
fun CompareScreen(saved: List<BookmarkedProduct>, model: KitaiViewModel) {
    val products = saved.filter(BookmarkedProduct::comparison).map(BookmarkedProduct::product)
    LazyColumn(contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Text("Сравнение", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        if (products.size < 2) item {
            InfoCard(
                if (products.isEmpty()) "Список пуст" else "Добавьте ещё предложение",
                "Нажмите «Сравнить» в карточках товаров.",
            )
        }
        if (products.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                products.forEach { product ->
                    Surface(
                        Modifier.width(210.dp), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(product.market?.title.orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(
                                product.displayTitle, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            HorizontalDivider()
                            DetailField("Цена", product.originalPrice())
                            DetailField("Оценка в рублях", product.rubles())
                            DetailField("Курс на", product.fxDate?.let(::formatDay))
                            DetailField("Минимальная партия", product.moq?.let(::formatNumber))
                            DetailField("Продавец", product.seller)
                            TextButton(onClick = { model.openProduct(product) }) { Text("Подробнее") }
                            TextButton(onClick = { model.mark(product, SavedAction.COMPARE, false) }) { Text("Убрать") }
                        }
                    }
                }
            }
        }
    }
}
