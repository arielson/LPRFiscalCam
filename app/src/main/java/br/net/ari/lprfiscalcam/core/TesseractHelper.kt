package br.net.ari.lprfiscalcam.core

import android.content.Context
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI

class TesseractHelper(private val context: Context) {

    private val tessBaseApi: TessBaseAPI = TessBaseAPI()

    init {
        val datapath = context.filesDir.absolutePath + "/tessdata/"
        val language = "eng"

        // Initialize Tesseract with the provided language and data path
        tessBaseApi.init(datapath, language)
    }

    fun recognizeText(imagePath: String): String {
        val bitmap = BitmapFactory.decodeFile(imagePath)
        tessBaseApi.setImage(bitmap)

        return tessBaseApi.utF8Text
    }
}