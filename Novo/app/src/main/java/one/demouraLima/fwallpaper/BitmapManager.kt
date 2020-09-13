package one.demouraLima.fwallpaper

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.isActive
import java.io.FileDescriptor
import kotlin.coroutines.coroutineContext

class BitmapManager(
    private val sourceFolder: Uri,
    private val contentResolver: ContentResolver
) {
    private val photoIds: MutableList<String> = mutableListOf()
    private var bitmaps: MutableList<Bitmap?> = mutableListOf()
    private var nextBitmaps: MutableList<Bitmap?> = mutableListOf()
    private var iterator = photoIds.iterator()

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

    suspend fun loadNextBitmaps(count: Int, cropOptions: CropOptions?) {
        nextBitmaps.clear()
        loadBitmaps(nextBitmaps, count, cropOptions)
        bitmaps.clear()
        swapBitmapLists()
    }

    fun getBitmaps(): List<Bitmap?> {
        return bitmaps
    }

    private fun loadBitmap(photoId: String, cropOptions: CropOptions?): Bitmap? {
        val photoUri = DocumentsContract.buildDocumentUriUsingTree(sourceFolder, photoId)

        val parcelFileDescriptor: ParcelFileDescriptor =
            contentResolver
                .openFileDescriptor(photoUri, "r") ?: return null

        parcelFileDescriptor.use {
            val fileDescriptor: FileDescriptor = it.fileDescriptor
            return BitmapFactory.decodeFileDescriptor(fileDescriptor)
        }
    }

    private fun getNextBitmap(cropOptions: CropOptions?): Bitmap? {
        if (!iterator.hasNext()) {
            if (photoIds.size > 0) {
                iterator = photoIds.iterator()
            } else {
                return null
            }
        }

        return loadBitmap(iterator.next(), cropOptions)
    }

    private suspend fun loadBitmaps(outBitmaps: MutableList<Bitmap?>, count: Int, cropOptions: CropOptions?) {
        for (i in 0 until count) {
            if (coroutineContext.isActive) {
                outBitmaps.add(getNextBitmap(cropOptions))
            }
        }
    }

    private fun swapBitmapLists() {
        bitmaps = nextBitmaps.also { nextBitmaps = bitmaps }
    }
}