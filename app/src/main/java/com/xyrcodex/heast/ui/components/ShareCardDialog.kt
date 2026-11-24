package com.xyrcodex.heast.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.widget.Toast
import com.xyrcodex.heast.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ShareCardTemplate { POSTER, LYRICS }

@Composable
fun ShareCardDialog(
    track: Track,
    lyrics: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf(ShareCardTemplate.POSTER) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun createCard(save: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ShareCardRenderer.render(context, track, lyrics, template == ShareCardTemplate.LYRICS)
                }
                bitmap = result
                if (save) {
                    withContext(Dispatchers.IO) { ShareCardRenderer.save(context, result) }
                    Toast.makeText(context, "Tersimpan di Pictures/Heast-Music", Toast.LENGTH_LONG).show()
                } else {
                    ShareCardRenderer.share(context, result, track.title)
                }
            } catch (error: Exception) {
                Toast.makeText(context, "Gagal membuat share card: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) createCard(true)
        else Toast.makeText(context, "Izin penyimpanan ditolak", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Share Card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pilih desain kartu untuk ${track.title}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { template = ShareCardTemplate.POSTER; bitmap = null }) {
                        Text(if (template == ShareCardTemplate.POSTER) "✓ Poster" else "Poster")
                    }
                    TextButton(onClick = { template = ShareCardTemplate.LYRICS; bitmap = null }) {
                        Text(if (template == ShareCardTemplate.LYRICS) "✓ Lyrics" else "Lyrics")
                    }
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Share card preview",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                    )
                } ?: Text(
                    if (template == ShareCardTemplate.POSTER) "Poster artwork dengan cover, judul, artis dan branding Heast."
                    else "Kartu lirik dengan potongan lirik lagu dan artwork.",
                    color = Color(0xFF9AA39D)
                )
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("  Menyiapkan gambar…")
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (Build.VERSION.SDK_INT < 29 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else createCard(true)
                    }
                ) { Text("Simpan") }
                TextButton(enabled = !busy, onClick = { createCard(false) }) { Text("Bagikan") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Tutup") }
        }
    )
}

private object ShareCardRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private val lime = android.graphics.Color.rgb(190, 245, 112)

    suspend fun render(context: Context, track: Track, lyrics: List<String>, lyricMode: Boolean): Bitmap {
        val artwork = withContext(Dispatchers.IO) {
            try {
                val connection = URL(track.highResThumbnailUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 7000
                connection.setRequestProperty("User-Agent", "Heast-Music-Android")
                connection.connect()
                connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
            } catch (_: Exception) { null }
        }
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), intArrayOf(
            android.graphics.Color.rgb(23, 49, 42),
            android.graphics.Color.rgb(8, 13, 11),
            android.graphics.Color.BLACK
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        val imageRect = if (lyricMode) RectF(80f, 90f, 330f, 340f) else RectF(110f, 130f, 970f, 990f)
        artwork?.let {
            val side = minOf(it.width, it.height)
            val cropped = Bitmap.createBitmap(it, (it.width-side)/2, (it.height-side)/2, side, side)
            canvas.drawBitmap(cropped, null, imageRect, paint)
            if (cropped !== it) cropped.recycle()
            it.recycle()
        } ?: run {
            paint.color = android.graphics.Color.rgb(43, 70, 57)
            canvas.drawRoundRect(imageRect, 42f, 42f, paint)
        }

        paint.color = lime
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 42f
        canvas.drawText("HEAST", 82f, 1190f, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 24f
        paint.color = android.graphics.Color.rgb(180, 192, 183)
        canvas.drawText("MUSIC FOR YOUR MOMENT", 82f, 1230f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        if (lyricMode) {
            paint.textSize = 52f
            drawWrapped(canvas, track.title, 390f, 150f, 600f, paint, 2)
            paint.textSize = 34f
            paint.color = android.graphics.Color.LTGRAY
            drawWrapped(canvas, track.artist, 390f, 245f, 600f, paint, 2)
            paint.color = lime
            paint.textSize = 30f
            canvas.drawText("LYRICS", 82f, 440f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 43f
            val selected = lyrics.filter { it.isNotBlank() }.take(7)
            if (selected.isEmpty()) {
                canvas.drawText("Listen, feel, and", 82f, 540f, paint)
                canvas.drawText("make the moment yours.", 82f, 605f, paint)
            } else {
                var y = 535f
                selected.forEach { line ->
                    drawWrapped(canvas, line, 82f, y, 910f, paint, 2)
                    y += if (line.length > 46) 112f else 82f
                    if (y > 1080f) return@forEach
                }
            }
        } else {
            paint.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), intArrayOf(
                android.graphics.Color.rgb(25, 53, 43),
                android.graphics.Color.rgb(8, 12, 10),
                android.graphics.Color.BLACK
            ), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(54f, 54f, 1026f, 1296f), 54f, 54f, paint)
            paint.shader = null
            paint.color = lime
            paint.textSize = 34f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("HEAST  /  MUSIC FOR YOUR MOMENT", 104f, 142f, paint)
            paint.color = android.graphics.Color.argb(150, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(RectF(100f, 190f, 980f, 1050f), 38f, 38f, paint)
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.WHITE
            paint.textSize = when {
                track.title.length > 58 -> 42f
                track.title.length > 38 -> 48f
                else -> 58f
            }
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            drawWrapped(canvas, track.title.trim(), 112f, 1140f, 850f, paint, 2)
            paint.color = android.graphics.Color.rgb(190, 200, 193)
            paint.textSize = 34f
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            drawWrapped(canvas, track.artist, 112f, 1220f, 850f, paint, 1)
            paint.color = lime
            paint.textSize = 23f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("LISTEN  •  FEEL  •  REPEAT", 112f, 1270f, paint)
        }
        return bitmap
    }

    private fun drawWrapped(canvas: Canvas, value: String, x: Float, startY: Float, maxWidth: Float, paint: Paint, maxLines: Int) {
        val words = value.trim().split(Regex("\\s+"))
        var line = ""
        var y = startY
        var count = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line, x, y, paint)
                count++
                if (count >= maxLines) return
                line = word
                y += paint.textSize * 1.25f
            } else line = candidate
        }
        if (line.isNotEmpty() && count < maxLines) canvas.drawText(line, x, y, paint)
    }

    fun save(context: Context, bitmap: Bitmap): Uri {
        val filename = "Heast-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg"
        val legacyDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Heast-Music")
        if (Build.VERSION.SDK_INT < 29 && !legacyDirectory.exists() && !legacyDirectory.mkdirs()) {
            error("Tidak dapat membuat folder Pictures/Heast-Music")
        }
        val legacyFile = File(legacyDirectory, filename)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Heast-Music")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, legacyFile.absolutePath)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak dapat membuat file galeri")
        context.contentResolver.openOutputStream(uri)?.use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) error("Gagal menyimpan gambar")
        } ?: error("Tidak dapat membuka file galeri")
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
        return uri
    }

    fun share(context: Context, bitmap: Bitmap, title: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "heast-share.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Bagikan Share Card"))
    }
}
