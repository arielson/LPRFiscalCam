package br.net.ari.lprfiscalcam.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class TesseractHelper(context: Context) {

    private val tessBaseApi: TessBaseAPI = TessBaseAPI()

    init {
        try {
            val dataPath = context.filesDir.absolutePath //+ "/tessdata/"

            val tessdataDir = File(context.filesDir.absolutePath, "tessdata")
            if (!tessdataDir.exists()) {
                tessdataDir.mkdir()
            }

            val assets = context.assets
            val trainedDataPath = File(tessdataDir, "eng.traineddata")
            if (!trainedDataPath.exists()) {
                assets.open("eng.traineddata").use { inputStream ->
                    trainedDataPath.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            extractAssets(context)
//            val datapath = Utilities.getFileFromAssets(context, "eng.traineddata").absolutePath
            val language = "eng"

            // Initialize Tesseract with the provided language and data path
            tessBaseApi.init(dataPath, language)
        } catch (ex: Exception) {
            Log.d("ERRO", "${ex.message}")
        }
    }

    private fun getLocalDir(context: Context): File {
        return context.filesDir
    }

    private fun getTessDataPath(context: Context): String? {
        return getLocalDir(context).absolutePath
    }

    private fun copyFile(am: AssetManager, assetName: String?, outFile: File?
    ) {
        try {
            am.open(assetName!!).use { `in` ->
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (`in`.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun extractAssets(context: Context) {
        val am = context.assets
        val localDir: File = getLocalDir(context)
        if (!localDir.exists() && !localDir.mkdir()) {
            throw RuntimeException("Can't create directory $localDir")
        }
        val tessDir = File(getTessDataPath(context), "tessdata")
        if (!tessDir.exists() && !tessDir.mkdir()) {
            throw RuntimeException("Can't create directory $tessDir")
        }

        // Extract all assets to our local directory.
        // All *.traineddata into "tessdata" subdirectory, other files into root.
        try {
            for (assetName in am.list("")!!) {
                if (assetName.endsWith(".traineddata")) {
                    val targetFile = File(tessDir, assetName)

                    if (!targetFile.exists()) {
                        copyFile(am, assetName, targetFile)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

//    fun recognizeText(bitmap: Bitmap): String {
//        tessBaseApi.setImage(bitmap)
//
//        return tessBaseApi.utF8Text
//    }
}