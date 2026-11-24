package ru.kitai.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualProductDialog(
    initialAddress: String,
    initialTitle: String,
    existingProduct: Product?,
    onDismiss: () -> Unit,
    onSave: (Product, (Boolean) -> Unit) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var title by rememberSaveable { mutableStateOf(existingProduct?.displayTitle ?: initialTitle) }
    var price by rememberSaveable { mutableStateOf(existingProduct?.priceMin.orEmpty()) }
    var minimum by rememberSaveable { mutableStateOf(existingProduct?.moq.orEmpty()) }
    var currency by rememberSaveable {
        val market = marketplaceProductLink(initialAddress)?.market
        mutableStateOf(existingProduct?.currency ?: if (market == Market.ALIBABA) "USD" else "CNY")
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Добавить товар") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(4_096); error = null },
                    label = { Text("Ссылка на карточку товара") },
                    supportingText = { Text("Taobao, 1688 или Alibaba.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200); error = null },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.take(16); error = null },
                    label = { Text("Цена за единицу") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (price.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CNY", "USD", "EUR", "RUB").forEach { option ->
                            FilterChip(
                                selected = currency == option,
                                onClick = { currency = option },
                                label = { Text(option) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = minimum,
                    onValueChange = { minimum = it.take(12); error = null },
                    label = { Text("Минимальная партия") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val link = marketplaceProductLink(address)
                val priceText = price.trim().replace(',', '.')
                val amount = priceText.toBigDecimalOrNull()
                val units = minimum.trim().toLongOrNull()
                error = when {
                    link == null -> "Вставьте прямую ссылку на карточку товара из адресной строки."
                    title.isBlank() -> "Введите название товара."
                    price.isNotBlank() && (amount == null || amount.signum() <= 0 || amount.scale() !in 0..2) ->
                        "Укажите цену больше нуля с точностью до двух знаков."
                    minimum.isNotBlank() && (units == null || units < 1) ->
                        "Укажите минимальную партию целым положительным числом."
                    else -> null
                }
                if (error == null && link != null) {
                    val product = link.product(
                        title.trim(), amount?.toPlainString(), currency.takeIf { amount != null }, units?.toString(),
                    )
                    saving = true
                    onSave(product) { success ->
                        saving = false
                        if (success) onDismiss() else error = "Не удалось сохранить товар. Попробуйте ещё раз."
                    }
                }
            }) { Text(if (saving) "Сохраняем…" else "В избранное") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } },
    )
}
