package one.demouraLima.fwallpaper

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.util.Size
import kotlinx.coroutines.isActive
import java.io.FileDescriptor
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class BitmapManager(
    val sourceFolder: Uri,
    private val contentResolver: ContentResolver
) {
    private val photoIds: MutableList<String> = mutableListOf()
    private var bitmaps: MutableList<Bitmap?> = mutableListOf()
    private var nextBitmaps: MutableList<Bitmap?> = mutableListOf()
    private var position: Int = 0

    enum class CropMode {
        CROP_START,
        CROP_CENTER,
        CROP_END,
    }

    data class CropOptions(val mode: CropMode, val aspectRatio: Float)

    suspend fun loadPhotoIds() {
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

    suspend fun loadNextBitmaps(count: Int, minSize: Size, cropOptions: CropOptions?): List<Bitmap?> {
        nextBitmaps.clear()
        loadBitmaps(nextBitmaps, count, minSize, cropOptions)
        bitmaps.clear()
        swapBitmapLists()
        return bitmaps
    }

    fun getBitmaps(): List<Bitmap?> {
        return bitmaps
    }

   suspend fun loadMoreBitmaps(count: Int, minSize: Size, cropOptions: CropOptions?): List<Bitmap?> {
       loadBitmaps(bitmaps, count, minSize, cropOptions)
       return bitmaps
   }

    fun rewind(count: Int) {
        if (position > count) {
            position -= count
        } else {
            // Position is circular, so wrap around.
            position = photoIds.size - (count - position)
        }

        val moveCount = min(count, bitmaps.size)
        repeat(min(moveCount, nextBitmaps.size)) {
            nextBitmaps.removeAt(0)
        }
        for (bitmap in bitmaps.reversed().take(moveCount)) {
            nextBitmaps.add(0, bitmap)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqSize: Size): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqSize.height || width > reqSize.width) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqSize.height && halfWidth / inSampleSize >= reqSize.width) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }


    private fun loadBitmap(photoId: String, minSize: Size, cropOptions: CropOptions?): Bitmap? {
        val photoUri = DocumentsContract.buildDocumentUriUsingTree(sourceFolder, photoId)

        val parcelFileDescriptor: ParcelFileDescriptor =
            contentResolver
                .openFileDescriptor(photoUri, "r") ?: return null

        return parcelFileDescriptor.use {
            val fileDescriptor: FileDescriptor = it.fileDescriptor

            BitmapFactory.Options().run {
                inJustDecodeBounds = true
                BitmapFactory.decodeFileDescriptor(fileDescriptor)

                inSampleSize = calculateInSampleSize(this, minSize)
                inJustDecodeBounds = false
                BitmapFactory.decodeFileDescriptor(fileDescriptor)
            }
        }
    }

    private fun getNextBitmap(minSize: Size, cropOptions: CropOptions?): Bitmap? {
        if (photoIds.size == 0) return null
        // Reset pos if we reached end.
        if (position == photoIds.size) position = 0

        return loadBitmap(photoIds[position++], minSize, cropOptions)
    }

    private suspend fun loadBitmaps(outBitmaps: MutableList<Bitmap?>, count: Int, minSize: Size, cropOptions: CropOptions?) {
        for (i in 0 until count) {
            if (coroutineContext.isActive) {
                outBitmaps.add(getNextBitmap(minSize, cropOptions))
            }
        }
    }

    private fun swapBitmapLists() {
        bitmaps = nextBitmaps.also { nextBitmaps = bitmaps }
    }
}