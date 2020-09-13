package one.demouraLima.fwallpaper

import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import androidx.core.graphics.toRectF
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import java.io.FileDescriptor
import kotlin.coroutines.coroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

class FWallpaperService : WallpaperService() {
    enum class ScaleType {
        CROP_START,
        CROP_CENTER,
        CROP_END,
        MATRIX_FILL,
        MATRIX_START,
        MATRIX_CENTER,
        MATRIX_END
    }

    enum class Orientation {
        Landscape,
        Portrait
    }

    override fun onCreateEngine(): Engine {
        val prefStorage = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val folder = prefStorage.getString("source_folder", null) ?: return BlankEngine()
        val width = prefStorage.getInt("grid_width", 2)
        val height = prefStorage.getInt("grid_height", 4)
        val delay = prefStorage.getInt("delay", 1)
        val scaleType = kotlin.runCatching { ScaleType.valueOf(prefStorage.getString("scale_type", null)!!) }
            .getOrDefault(ScaleType.MATRIX_CENTER)

        val uri = try {
            Uri.parse(folder)
        } catch (e: Exception) {
            Log.w("FWallpaper", "Failed to parse uri due to ${e.message}")
            return BlankEngine()
        }

        return FEngine(uri, Size(width, height), scaleType, delay)
    }

    // Used when there are no images to display.
    inner class BlankEngine() : Engine() {}

    inner class FEngine(
        private val sourceFolder: Uri,
        private val gridSize: Size,
        private val scaleType: ScaleType,
        private val delaySeconds: Int
    ) : Engine() {
        private val photoIds: MutableList<String> = mutableListOf()
        private var iterator: Iterator<String> = photoIds.iterator()

        private var bitmaps: MutableList<Bitmap?> = mutableListOf()
        private var nextBitmaps: MutableList<Bitmap?> = mutableListOf()

        private lateinit var surfaceScope: CoroutineScope
        private var drawRoutine: Job? = null
        private val visibilityChange = Channel<Boolean>(Channel.CONFLATED)

        init {
            // Apparently, in SAF the uri to list a folder's children is not the same uri as the
            // folder itself ???. It has an extra /children at the end. Okay Google, okay.
            val sourceFolderChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                this.sourceFolder,
                DocumentsContract.getTreeDocumentId(this.sourceFolder)
            )

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
//                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            // This doesn't work, because Google
//            val selection = "${DocumentsContract.Document.COLUMN_MIME_TYPE} LIKE ?"
//            val selectionArgs = arrayOf("image/*")
            // Does this work? Probably not
//            val sortBy = "${DocumentsContract.Document.COLUMN_DOCUMENT_ID} ASC"

            contentResolver.query(
                sourceFolderChildrenUri,
                projection,
                null,
                null,
                null
            )?.use {
                val idColumn =
                    it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
//                val nameColumn =
//                    it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn =
                    it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                Log.d("FWallpaper", "Found ${it.count} files.")
                photoIds.clear()
                while (it.moveToNext()) {
                    val photoId = it.getString(idColumn)
//                    val photoName = it.getString(nameColumn)
                    val photoMime = it.getString(mimeColumn)

                    if (photoMime.startsWith("image/")) {
//                        Log.d("FWallpaper", "Found photo $photoName of type $photoMime.")
                        photoIds.add(photoId)
                    }
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d("FWallpaper", "onSurfaceCreated called")
            super.onSurfaceCreated(holder)
            surfaceScope = CoroutineScope(Dispatchers.Main)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            Log.d("FWallpaper", "onSurfaceDestroyed called")
            super.onSurfaceDestroyed(holder)
            if (surfaceScope.isActive) {
                surfaceScope.cancel()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            Log.d(
                "FWallpaper",
                "onSurfaceChanged called with format: $format, width: $width, height: $height. Creating ${holder?.isCreating}"
            )
            super.onSurfaceChanged(holder, format, width, height)
        }

        @ExperimentalTime
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
            Log.d("FWallpaper", "onSurfaceRedrawNeeded called with holder: $holder. Creating: ${holder?.isCreating}")
            super.onSurfaceRedrawNeeded(holder)
            if (holder == null) return
            drawRoutine?.cancel()

            runBlocking {
                if (bitmaps.size != gridSize.width * gridSize.height) {
                    bitmaps.clear()
                    loadBitmaps(bitmaps)
                }
                draw(holder)
            }

            drawRoutine = surfaceScope.launch {
                do {
                    suspendWhileInvisible()
                    Log.d("FWallpaper", "Preparing to load next bitmaps.")
                    nextBitmaps.clear()
                    loadBitmaps(nextBitmaps)

                    delay(delaySeconds.seconds)

                    suspendWhileInvisible()
                    bitmaps = nextBitmaps.also { nextBitmaps = bitmaps }
                    draw(holder)
                } while (isActive)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d("FWallpaper", "onVisibilityChanged called with $visible")
            super.onVisibilityChanged(visible)
            visibilityChange.sendBlocking(visible)
        }

        private suspend fun suspendWhileInvisible() {
            if (visibilityChange.poll() == false) {
                do {
                    Log.d("FWallpaper", "Receiving visible false, will wait for true")
                    val visible = visibilityChange.receive()
                } while (!visible)
            }
        }

        private fun getBitmapFromUri(uri: Uri): Bitmap? {
            val parcelFileDescriptor: ParcelFileDescriptor =
                applicationContext.contentResolver
                    .openFileDescriptor(uri, "r") ?: return null

            parcelFileDescriptor.use {
                val fileDescriptor: FileDescriptor = it.fileDescriptor
                return BitmapFactory.decodeFileDescriptor(fileDescriptor)
            }
        }

        private fun getNextBitmap(): Bitmap? {
            if (!iterator.hasNext()) {
                if (photoIds.size > 0) {
                    iterator = photoIds.iterator()
                } else {
                    return null
                }
            }

            val photoUri = DocumentsContract.buildDocumentUriUsingTree(this.sourceFolder, iterator.next())
            return getBitmapFromUri(photoUri)
        }

        private suspend fun loadBitmaps(outBitmaps: MutableList<Bitmap?>) {
            for (i in 0 until gridSize.height * gridSize.width) {
                if (coroutineContext.isActive) {
                    outBitmaps.add(getNextBitmap())
                }
            }
        }

        private fun getGridRect(canvasSize: Size, gridPos: Point): Rect {
            val rectSize = Size(canvasSize.width / gridSize.width, canvasSize.height / gridSize.height)
            val rectStart = Point(rectSize.width * gridPos.x, rectSize.height * gridPos.y)

            return Rect(rectStart.x, rectStart.y, rectStart.x + rectSize.width, rectStart.y + rectSize.height)
        }

        private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, gridPos: Point) {
            val dstRect = getGridRect(Size(canvas.width, canvas.height), gridPos)

            val crop = { cropFocus: (Int, Int) -> Int ->
                val orientation = if (bitmap.height > bitmap.width) {
                    Orientation.Portrait
                } else {
                    Orientation.Landscape
                }

                when (orientation) {
                    Orientation.Landscape -> {
                        val aspectRatio = dstRect.width().toFloat() / dstRect.height().toFloat()
                        val newSrcWidth = (bitmap.height * aspectRatio).toInt()
                        val newLeft = cropFocus(bitmap.width, newSrcWidth)

                        val srcRect = Rect(newLeft, 0, newLeft + newSrcWidth, bitmap.height)
                    }
                    Orientation.Portrait -> {
                        val aspectRatio = dstRect.width().toFloat() / dstRect.height().toFloat()
                        val newSrcHeight = (bitmap.width / aspectRatio).toInt()
                        val newTop = cropFocus(bitmap.height, newSrcHeight)

                        val srcRect = Rect(0, newTop, bitmap.width, newTop + newSrcHeight)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    }
                }
            }

            val drawWithMatrix = { scaleToFit: Matrix.ScaleToFit ->
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val scaleMatrix = Matrix().apply { setRectToRect(srcRect.toRectF(), dstRect.toRectF(), scaleToFit) }
                canvas.drawBitmap(bitmap, scaleMatrix, null)
            }

            when (scaleType) {
                ScaleType.CROP_START -> crop() { _, _ -> 0 }
                ScaleType.CROP_CENTER -> crop() { oldVal, newVal -> (oldVal - newVal) / 2 }
                ScaleType.CROP_END -> crop() { oldVal, newVal -> oldVal - newVal }
                ScaleType.MATRIX_START -> drawWithMatrix(Matrix.ScaleToFit.START)
                ScaleType.MATRIX_CENTER -> drawWithMatrix(Matrix.ScaleToFit.CENTER)
                ScaleType.MATRIX_END -> drawWithMatrix(Matrix.ScaleToFit.END)
                ScaleType.MATRIX_FILL -> drawWithMatrix(Matrix.ScaleToFit.FILL)
            }
        }

        private suspend fun draw(holder: SurfaceHolder) {
            Log.d("FWallpaper", "draw called")
            if (bitmaps.size != gridSize.width * gridSize.height) {
                Log.d(
                    "FWallaper",
                    "Bitmap size was unexpected ${bitmaps.size}. Expected ${gridSize.width * gridSize.height}"
                )
                return
            }
            val canvas = try {
                val c = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }

                if (c == null) {
                    Log.d("FWallaper", "Couldn't lock canvas")
                    return
                }
                c
            } catch (e: Exception) {
                Log.e("FWallpaper", "Failed to lock canvas due to ${e.message}")
                return
            }

            Log.d("FWallpaper", "Drawing with canvas ${canvas.width}x${canvas.height}")
            try {
                for (i in 0 until gridSize.width) {
                    for (j in 0 until gridSize.height) {
                        if (coroutineContext.isActive) {
                            val bitmap = bitmaps[i * gridSize.height + j]
                            if (bitmap != null) {
                                drawBitmap(canvas, bitmap, Point(i, j))
                            } else {
                                val rect = getGridRect(Size(canvas.width, canvas.height), Point(i, j))
                                canvas.drawText("Missing Image", rect.left.toFloat(), rect.exactCenterY(), Paint())
                            }
                        }
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
