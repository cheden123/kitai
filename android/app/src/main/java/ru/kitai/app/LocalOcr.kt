package ru.kitai.app

import android.content.Context
import android.graphics.ImageDecoder
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Recognizes text using the bundled language models without a network request. */
class LocalOcr(context: Context) {
    private val application = context.applicationContext

    suspend fun recognize(bytes: ByteArray, sourceLanguage: String): OcrResult =
        withContext(Dispatchers.IO) {
            recognitionLock.withLock {
                val language = when (sourceLanguage) {
                    "en" -> "eng"
                    "ru" -> "rus"
                    "zh" -> "chi_sim"
                    else -> throw IllegalArgumentException("Неподдерживаемый язык")
                }
                val directory = modelsDirectory(language)
                val bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                ) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val pixels = info.size.width.toLong() * info.size.height
                    if (pixels > MAX_PIXELS) {
                        val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
                try {
                    val api = TessBaseAPI()
                    try {
                        check(api.init(directory.path, language, TessBaseAPI.OEM_LSTM_ONLY))
                        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                        api.setImage(bitmap)
                        val blocks = if (api.getUTF8Text().isNullOrBlank()) emptyList() else readLines(api)
                        OcrResult(bitmap.width, bitmap.height, blocks)
                    } finally {
                        api.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

    private fun modelsDirectory(language: String): File {
        val root = File(application.filesDir, "tesseract-fast-4.1.0")
        val tessdata = File(root, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs())
        val model = File(tessdata, "$language.traineddata")
        if (!model.isFile || model.length() == 0L) {
            val destination = AtomicFile(model)
            val stream = destination.startWrite()
            try {
                application.assets.open("tessdata/${model.name}").use { it.copyTo(stream) }
                destination.finishWrite(stream)
            } catch (failure: Exception) {
                destination.failWrite(stream)
                throw failure
            }
        }
        return root
    }

    private fun readLines(api: TessBaseAPI): List<OcrBlock> {
        val iterator = api.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        try {
            iterator.begin()
            val lines = mutableListOf<OcrBlock>()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val rect = iterator.getBoundingRect(level)
                    val box = rect?.takeUnless { it.isEmpty }?.let {
                        OcrBounds(it.left, it.top, it.width(), it.height())
                    }
                    lines.add(OcrBlock(text, null, box))
                }
            } while (lines.size < 100 && iterator.next(level))
            return lines
        } finally {
            iterator.delete()
        }
    }

    private companion object {
        const val MAX_PIXELS = 6_000_000L
        val recognitionLock = Mutex()
    }
}
