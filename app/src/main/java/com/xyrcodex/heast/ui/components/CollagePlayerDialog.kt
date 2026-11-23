package com.xyrcodex.heast.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.xyrcodex.heast.data.model.Track
import com.xyrcodex.heast.viewmodel.MusicViewModel
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

private enum class CollageLayout(val label: String, val count: Int) {
    FEATURED("Featured", 2), GRID("Grid", 4), WALL("Wall", 5)
}

@Composable
fun CollagePlayerDialog(
    currentTrack: Track,
    queue: List<Track>,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableTracks = remember(currentTrack, queue) {
        (listOf(currentTrack) + queue).distinctBy { it.id }.take(18)
    }
    val selectedIds = remember(currentTrack.id) { mutableStateListOf(currentTrack.id) }
    var layout by remember { mutableStateOf(CollageLayout.GRID) }
    var rotation by remember { mutableFloatStateOf(8f) }
    var backgroundX by remember { mutableFloatStateOf(0f) }
    var backgroundY by remember { mutableFloatStateOf(0f) }
    var backgroundBlur by remember { mutableFloatStateOf(0f) }
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackPosition by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    var backgroundUri by remember { mutableStateOf<Uri?>(null) }
    var watermark by remember { mutableStateOf("HEAST MUSIC") }
    var showWatermark by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> backgroundUri = uri }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        CollageRenderer.save(context, availableTracks.filter { selectedIds.contains(it.id) }.ifEmpty { listOf(currentTrack) }.take(minOf(layout.count, 5)), backgroundUri, rotation, layout, watermark.takeIf { showWatermark }, backgroundX, backgroundY, backgroundBlur, playbackPosition, duration, isPlaying)
                    }
                    Toast.makeText(context, "Collage tersimpan di Pictures/Heast-Music", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        } else Toast.makeText(context, "Izin penyimpanan ditolak", Toast.LENGTH_SHORT).show()
    }

    val selectedTracks = availableTracks.filter { selectedIds.contains(it.id) }.take(minOf(layout.count, 5)).ifEmpty { listOf(currentTrack) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { androidx.compose.material3.Icon(Icons.Outlined.Collections, null, tint = Color(0xFFBEF570)) },
        title = { Text("Collage Player", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF101713)).border(1.dp, Color(0xFF344239), RoundedCornerShape(18.dp))
                ) {
                    if (backgroundUri != null) {
                        AsyncImage(model = backgroundUri, contentDescription = null, modifier = Modifier.matchParentSize().graphicsLayer { scaleX = 1.45f; scaleY = 1.45f; translationX = backgroundX * 70f; translationY = backgroundY * 70f }, contentScale = ContentScale.Crop)
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.48f)))
                    }
                    Column(Modifier.align(Alignment.Center).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            selectedTracks.take(2).forEach { track ->
                                CollagePreviewCard(track, rotation, Modifier.weight(1f))
                            }
                        }
                        if (selectedTracks.size > 2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                selectedTracks.drop(2).take(2).forEach { track -> CollagePreviewCard(track, -rotation * 0.55f, Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (showWatermark) {
                        Text(watermark.ifBlank { "HEAST MUSIC" }, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp), color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }
                Text("Layout", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    CollageLayout.values().forEach { option ->
                        FilterChip(selected = layout == option, onClick = { layout = option }, label = { Text("${option.label} · ${option.count}") })
                    }
                }
                Text("Rotasi kartu · ${rotation.toInt()}°", style = MaterialTheme.typography.labelLarge)
                Slider(value = rotation, onValueChange = { rotation = it }, valueRange = 0f..24f)
                Text("Posisi background horizontal · ${(backgroundX * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = backgroundX, onValueChange = { backgroundX = it }, valueRange = -1f..1f)
                Text("Posisi background vertikal · ${(backgroundY * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = backgroundY, onValueChange = { backgroundY = it }, valueRange = -1f..1f)
                Text("Blur background · ${backgroundBlur.toInt()} px", style = MaterialTheme.typography.labelMedium)
                Slider(value = backgroundBlur, onValueChange = { backgroundBlur = it }, valueRange = 0f..24f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.skipPrevious() }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
                    IconButton(onClick = { viewModel.togglePlayPause() }) { Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause") }
                    IconButton(onClick = { viewModel.skipNext() }) { Icon(Icons.Filled.SkipNext, "Next") }
                    Text("${formatCollageTime(playbackPosition)} / ${formatCollageTime(duration)}", style = MaterialTheme.typography.labelSmall)
                }
                Text("Foto latar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBEF570), contentColor = Color.Black)) {
                        androidx.compose.material3.Icon(Icons.Outlined.AddPhotoAlternate, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (backgroundUri == null) "Pilih foto" else "Ganti foto")
                    }
                    Text(if (backgroundUri == null) "Pakai latar bawaan" else "Foto dipilih", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    if (backgroundUri != null) TextButton(onClick = { backgroundUri = null }) { Text("Hapus") }
                }
                Text("Pilih lagu · maksimal 5", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                availableTracks.forEach { track ->
                    val checked = selectedIds.contains(track.id)
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            if (checked) {
                                if (selectedIds.size > 1) selectedIds.remove(track.id)
                            } else if (selectedIds.size < minOf(layout.count, 5)) selectedIds.add(track.id)
                        }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            if (it && selectedIds.size < minOf(layout.count, 5)) selectedIds.add(track.id)
                            else if (!it && selectedIds.size > 1) selectedIds.remove(track.id)
                        })
                        AsyncImage(model = track.thumbnailUrlForDisplay(144), contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showWatermark, onCheckedChange = { showWatermark = it })
                    Text("Watermark")
                }
                OutlinedTextField(value = watermark, onValueChange = { watermark = it.take(28) }, enabled = showWatermark, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Teks watermark") })
                Text("Output: Pictures/Heast-Music · JPG 1440 × 1920", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val save = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    CollageRenderer.save(context, selectedTracks, backgroundUri, rotation, layout, watermark.takeIf { showWatermark }, backgroundX, backgroundY, backgroundBlur, playbackPosition, duration, isPlaying)
                                }
                                Toast.makeText(context, "Collage tersimpan di Pictures/Heast-Music", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally { busy = false }
                        }
                    }
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else save()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBEF570), contentColor = Color.Black)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                else androidx.compose.material3.Icon(Icons.Outlined.SaveAlt, null)
                Spacer(Modifier.width(7.dp))
                Text(if (busy) "Menyimpan…" else "Simpan Collage")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Tutup") } }
    )
}

@Composable
private fun CollagePreviewCard(track: Track, angle: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.graphicsLayer { rotationZ = angle }.clip(RoundedCornerShape(11.dp))
            .background(Color(0xE8151B18)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
            .padding(7.dp)
    ) {
        AsyncImage(model = track.thumbnailUrlForDisplay(320), contentDescription = null, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
        Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(track.artist, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 8.sp)
    }
}

private fun formatCollageTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.US, "%02d:%02d", total / 60L, total % 60L)
}

private fun blurBitmap(source: Bitmap, radius: Float): Bitmap {
    val factor = (radius / 24f).coerceIn(0f, 1f)
    if (factor <= 0f) return source
    val width = (source.width * (1f - factor * 0.88f)).toInt().coerceAtLeast(24)
    val height = (source.height * (1f - factor * 0.88f)).toInt().coerceAtLeast(24)
    val small = Bitmap.createScaledBitmap(source, width, height, true)
    return Bitmap.createScaledBitmap(small, source.width, source.height, true).also { small.recycle() }
}

private object CollageRenderer {
    private const val W = 1440
    private const val H = 1920

    fun save(context: Context, tracks: List<Track>, photo: Uri?, rotation: Float, layout: CollageLayout, watermark: String?, backgroundX: Float, backgroundY: Float, blur: Float, progressMs: Long, durationMs: Long, isPlaying: Boolean) {
        require(tracks.isNotEmpty()) { "Pilih minimal satu lagu" }
        val background = photo?.let { uri -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (background != null) {
            val source = if (blur > 0f) blurBitmap(background, blur) else background
            val scale = maxOf(W.toFloat() / source.width, H.toFloat() / source.height) * 1.35f
            val bw = source.width * scale
            val bh = source.height * scale
            val left = (W-bw)/2f + backgroundX * W * 0.22f
            val top = (H-bh)/2f + backgroundY * H * 0.22f
            canvas.drawBitmap(source, null, RectF(left, top, left+bw, top+bh), paint)
            if (source !== background) source.recycle()
            background.recycle()
            paint.color = AndroidColor.argb(115, 0, 0, 0)
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        } else {
            canvas.drawColor(AndroidColor.rgb(16, 24, 20))
            paint.shader = android.graphics.LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), AndroidColor.rgb(49, 59, 49), AndroidColor.BLACK, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
            paint.shader = null
        }

        val count = minOf(tracks.size, layout.count, 5)
        val positions = when (layout) {
            CollageLayout.FEATURED -> listOf(RectF(170f, 300f, 750f, 1040f), RectF(690f, 770f, 1270f, 1510f))
            CollageLayout.GRID -> listOf(RectF(90f, 300f, 680f, 900f), RectF(760f, 300f, 1350f, 900f), RectF(90f, 1010f, 680f, 1610f), RectF(760f, 1010f, 1350f, 1610f))
            CollageLayout.WALL -> listOf(RectF(70f, 260f, 500f, 800f), RectF(505f, 210f, 935f, 750f), RectF(940f, 260f, 1370f, 800f), RectF(250f, 940f, 680f, 1480f), RectF(760f, 940f, 1190f, 1480f))
        }
        tracks.take(count).forEachIndexed { index, track ->
            val rect = positions[index]
            val art = loadArtwork(track.thumbnailUrlForDisplay(640))
            val angle = when (index % 2) { 0 -> -rotation; else -> rotation }
            canvas.save()
            canvas.rotate(angle, rect.centerX(), rect.centerY())
            paint.color = AndroidColor.argb(225, 13, 18, 16)
            canvas.drawRoundRect(rect, 34f, 34f, paint)
            val artRect = RectF(rect.left + 16f, rect.top + 16f, rect.right - 16f, rect.top + (rect.width() - 32f))
            if (art != null) {
                canvas.drawBitmap(art, null, artRect, paint)
                art.recycle()
            } else {
                paint.color = AndroidColor.rgb(48, 68, 56)
                canvas.drawRoundRect(artRect, 22f, 22f, paint)
            }
            paint.color = AndroidColor.WHITE
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = 34f
            canvas.drawText(track.title.take(25), rect.left + 22f, artRect.bottom + 58f, paint)
            paint.color = AndroidColor.LTGRAY
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 25f
            canvas.drawText(track.artist.take(30), rect.left + 22f, artRect.bottom + 98f, paint)
            paint.color = AndroidColor.rgb(190, 245, 112)
            val progress = (progressMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val bar = RectF(rect.left + 22f, rect.bottom - 118f, rect.right - 22f, rect.bottom - 108f)
            paint.color = AndroidColor.argb(90, 255, 255, 255)
            canvas.drawRoundRect(bar, 8f, 8f, paint)
            paint.color = AndroidColor.rgb(190, 245, 112)
            canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * progress, bar.bottom), 8f, 8f, paint)
            paint.color = AndroidColor.WHITE
            paint.textSize = 22f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(formatCollageTime(progressMs), bar.left, bar.top - 18f, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatCollageTime(durationMs), bar.right, bar.top - 18f, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 44f
            canvas.drawText("◀   ${if (isPlaying) "Ⅱ" else "▶"}   ▶", rect.centerX(), rect.bottom - 45f, paint)
            paint.textAlign = Paint.Align.LEFT
            canvas.restore()
        }
        watermark?.takeIf { it.isNotBlank() }?.let {
            paint.color = AndroidColor.WHITE
            paint.textSize = 58f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.setShadowLayer(8f, 0f, 2f, AndroidColor.BLACK)
            canvas.drawText(it, W / 2f, 1800f, paint)
            paint.clearShadowLayer()
            paint.textAlign = Paint.Align.LEFT
            paint.color = AndroidColor.rgb(190, 245, 112)
            paint.textSize = 24f
            canvas.drawText("MUSIC FOR YOUR MOMENT", 82f, 1850f, paint)
        }
        val name = "Heast-Collage-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Heast-Music")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Tidak dapat membuat file galeri")
        context.contentResolver.openOutputStream(uri)?.use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) error("Gagal menulis gambar") } ?: error("Tidak dapat membuka galeri")
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        bitmap.recycle()
    }

    private fun loadArtwork(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "Heast-Music-Android")
        connection.connect()
        connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
    } catch (_: Exception) { null }
}
