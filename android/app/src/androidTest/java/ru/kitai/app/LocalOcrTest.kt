package ru.kitai.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class LocalOcrTest {
    @Test
    fun recognizesEnglishFromBundledModel() = runBlocking {
        val text = "Wireless headphones"
        val bitmap = Bitmap.createBitmap(1000, 240, Bitmap.Config.ARGB_8888)
        val bytes = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 48f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 65f
                })
            }
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = LocalOcr(context).recognize(bytes, "en")

        assertEquals(1000, result.width)
        assertEquals(240, result.height)
        assertEquals(text, result.blocks.single().text)
        assertNotNull(result.blocks.single().box)
    }
}
