package com.esafirm.imagepicker.features.fileloader

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.esafirm.imagepicker.features.ImagePickerConfig
import com.esafirm.imagepicker.features.ImageSortMode
import com.esafirm.imagepicker.features.common.ImageLoaderListener
import com.esafirm.imagepicker.helper.ImagePickerUtils
import com.esafirm.imagepicker.model.Folder
import com.esafirm.imagepicker.model.Image
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class DefaultImageFileLoader(private val context: Context) : ImageFileLoader {

    private var executor: ExecutorService? = null

    override fun loadDeviceImages(
        config: ImagePickerConfig,
        listener: ImageLoaderListener
    ) {

        getExecutorService().execute(
            ImageLoadRunnable(
                context = context.applicationContext,
                isFolderMode = config.isFolderMode,
                onlyVideo = config.isOnlyVideo,
                includeVideo = config.isIncludeVideo,
                includeAnimation = config.isIncludeAnimation,
                excludedImages = config.excludedImages,
                sortMode = config.imagesSortMode,
                listener = listener
            )
        )
    }

    override fun abortLoadImages() {
        executor?.shutdown()
        executor = null
    }

    private fun getExecutorService(): ExecutorService {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor()
        }
        return executor!!
    }

    private class ImageLoadRunnable(
        private val context: Context,
        private val isFolderMode: Boolean,
        private val onlyVideo: Boolean,
        private val includeVideo: Boolean,
        private val includeAnimation: Boolean,
        private val excludedImages: List<File>?,
        private val sortMode: ImageSortMode,
        private val listener: ImageLoaderListener
    ) : Runnable {

        companion object {
            private const val DEFAULT_FOLDER_NAME = "SDCARD"
            private const val FIRST_LIMIT = 1_000

            private const val QUERY_LIMIT = "limit"
        }

        enum class Projection(val column: String) {
            ID(MediaStore.Images.Media._ID),
            DISPLAY_NAME(MediaStore.Images.Media.DISPLAY_NAME),
            DATA(MediaStore.Images.Media.DATA),
            BUCKET_DISPLAY_NAME(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            MIME_TYPE(MediaStore.Images.Media.MIME_TYPE),
            DATE_MODIFIED(MediaStore.Images.Media.DATE_MODIFIED),
            SIZE(MediaStore.Images.Media.SIZE);

            companion object {
                fun columns() = values().map { it.column }.toTypedArray()
            }
        }

        @SuppressLint("InlinedApi")
        private fun queryData(limit: Int? = null): Cursor? {
            val useNewApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val sourceUri = if (limit != null && useNewApi) {
                getSourceUri().buildUpon()
                    .appendQueryParameter(QUERY_LIMIT, limit.toString())
                    .build()
            } else {
                getSourceUri()
            }

            val type = MediaStore.Files.FileColumns.MEDIA_TYPE

            val selection = when {
                onlyVideo -> "${type}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
                includeVideo -> "$type=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR $type=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
                // Empty because we query from image media store
                else -> ""
            }

            val sortColumn = when (sortMode) {
                ImageSortMode.NAME_ASC,
                ImageSortMode.NAME_DESC -> MediaStore.Files.FileColumns.DISPLAY_NAME
                ImageSortMode.TYPE_ASC,
                ImageSortMode.TYPE_DESC -> MediaStore.Files.FileColumns.MIME_TYPE
                ImageSortMode.DATE_MODIFIED_ASC,
                ImageSortMode.DATE_MODIFIED_DESC -> MediaStore.Files.FileColumns.DATE_MODIFIED
                ImageSortMode.SIZE_ASC,
                ImageSortMode.SIZE_DESC -> MediaStore.Files.FileColumns.SIZE
                else -> MediaStore.Files.FileColumns.DATE_MODIFIED
            }
            val sortDirection = when (sortMode) {
                ImageSortMode.NAME_ASC,
                ImageSortMode.TYPE_ASC,
                ImageSortMode.DATE_MODIFIED_ASC,
                ImageSortMode.SIZE_ASC -> ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                ImageSortMode.NAME_DESC,
                ImageSortMode.TYPE_DESC,
                ImageSortMode.DATE_MODIFIED_DESC,
                ImageSortMode.SIZE_DESC -> ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                else -> ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            }

            return if (useNewApi) {
                val args = Bundle().apply {
                    // Sort function
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, sortDirection)
                    // Selection
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    // Limit
                    if (limit != null) {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    }
                }

                context.contentResolver.query(sourceUri, Projection.columns(), args, null)
            } else {
                val oldSortDirection = if (sortDirection == ContentResolver.QUERY_SORT_DIRECTION_ASCENDING) "ASC" else "DESC"

                val sortOrder = "$sortColumn $oldSortDirection".let {
                    if (limit != null) "$it LIMIT $limit" else it
                }

                context.contentResolver.query(
                    sourceUri, Projection.columns(),
                    selection, null, sortOrder
                )
            }
        }

        private fun getSourceUri(): Uri {
            return if (onlyVideo || includeVideo) {
                MediaStore.Files.getContentUri("external")
            } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        private fun cursorToImage(cursor: Cursor): Image? {
            val path = cursor.getString(cursor.getColumnIndex(Projection.DATA.column))
            val file = makeSafeFile(path) ?: return null
            if (excludedImages != null && excludedImages.contains(file)) return null

            // Exclude GIF when we don't want it
            if (!includeAnimation) {
                if (ImagePickerUtils.isGifFormat(path)) return null
            }

            val id = cursor.getLong(cursor.getColumnIndex(Projection.ID.column))
            val name = cursor.getString(cursor.getColumnIndex(Projection.DISPLAY_NAME.column))
            val type = cursor.getString(cursor.getColumnIndex(Projection.MIME_TYPE.column))
            val dateString = cursor.getString(cursor.getColumnIndex(Projection.DATE_MODIFIED.column))
            val date = Date(dateString.toLong())
            val size = cursor.getLong(cursor.getColumnIndex(Projection.SIZE.column))

            return if (name != null) {
                return Image(id, name, path, type, date, size)
            } else null
        }

        private fun processData(cursor: Cursor?) {
            if (cursor == null) {
                listener.onFailed(NullPointerException())
                return
            }

            val result: MutableList<Image> = ArrayList()
            val folderMap: MutableMap<String, Folder> = mutableMapOf()

            if (cursor.moveToFirst()) {
                do {
                    val image = cursorToImage(cursor)

                    if (image != null) {
                        result.add(image)

                        // Load folders
                        if (!isFolderMode) continue
                        var bucket = cursor.getString(cursor.getColumnIndex(Projection.BUCKET_DISPLAY_NAME.column))
                        if (bucket == null) {
                            val parent = File(image.path).parentFile
                            bucket = if (parent != null) parent.name else DEFAULT_FOLDER_NAME
                        }

                        if (bucket != null) {
                            var folder = folderMap[bucket]
                            if (folder == null) {
                                folder = Folder(bucket)
                                folderMap[bucket] = folder
                            }
                            folder.images.add(image)
                        }
                    }

                } while (cursor.moveToNext())
            }
            cursor.close()

            val folders = folderMap.values.toList()
            listener.onImageLoaded(result, folders)
        }

        override fun run() {
            // We're gonna load two times for faster load if the devices has many images
            val cursor = queryData(FIRST_LIMIT)
            val isLoadDataAgain = cursor?.count == FIRST_LIMIT
            processData(cursor)

            if (isLoadDataAgain) {
                processData(queryData())
            }
        }
    }

    companion object {
        private fun makeSafeFile(path: String?): File? {
            return if (path == null || path.isEmpty()) {
                null
            } else try {
                File(path)
            } catch (ignored: Exception) {
                null
            }
        }
    }
}