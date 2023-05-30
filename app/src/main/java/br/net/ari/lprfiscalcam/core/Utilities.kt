package br.net.ari.lprfiscalcam.core

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.Html
import android.util.Base64
import android.view.Window
import android.widget.Button
import android.widget.TextView
import br.net.ari.lprfiscalcam.R
import br.net.ari.lprfiscalcam.interfaces.APIService
import br.net.ari.lprfiscalcam.models.Cliente
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit


object Utilities {
    private const val Host = "lprfiscalapi.ari.net.br"
//    private const val Host = "lprfiscalapihomol.ari.net.br"

    private const val ServiceUrl = "https://$Host/api/v1/"
    private var service: APIService? = null
    private val interceptor = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY)
    var cliente: Cliente? = null
    private fun okHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .addInterceptor(Interceptor { chain: Interceptor.Chain ->
                val original = chain.request()
                val requestBuilder: Request.Builder = original.newBuilder()
                    .header(
                        "Authorization",
                        if (cliente != null) "bearer " + cliente!!.token else ""
                    )
                val request: Request = requestBuilder.build()
                chain.proceed(request)
            })
            .build()
    }

    fun service(): APIService {
        if (service == null) {
            val gson = GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                .create()
            val gsonConverterFactory = GsonConverterFactory.create(gson)
            val retrofit = Retrofit.Builder()
                .baseUrl(ServiceUrl)
                .client(okHttpClient())
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(gsonConverterFactory)
                .build()
            service = retrofit.create(
                APIService::class.java
            )
        }
        return service as APIService
    }

    fun cropBitmap(bitmap: Bitmap, rect: RectF): Bitmap =
        Bitmap.createBitmap(bitmap, rect.left.toInt(), rect.top.toInt(), rect.width().toInt(), rect.height().toInt())

    fun getScaledImage(bitmapImage: Bitmap, newWidth: Int, newHeight: Int): ByteArray? {
        val mutableBitmapImage = Bitmap.createScaledBitmap(bitmapImage, newWidth, newHeight, false)
        val outputStream = ByteArrayOutputStream()
        mutableBitmapImage.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        if (mutableBitmapImage != bitmapImage) {
            mutableBitmapImage.recycle()
        }
        bitmapImage.recycle()
        return outputStream.toByteArray()
    }

//    fun getScaledImage(originalImage: ByteArray, newWidth: Int, newHeight: Int): ByteArray? {
//        val bitmapImage = BitmapFactory.decodeByteArray(originalImage, 0, originalImage.size)
//        val mutableBitmapImage = Bitmap.createScaledBitmap(bitmapImage, newWidth, newHeight, false)
//        val outputStream = ByteArrayOutputStream()
//        mutableBitmapImage.compress(Bitmap.CompressFormat.PNG, 0, outputStream)
//        if (mutableBitmapImage != bitmapImage) {
//            mutableBitmapImage.recycle()
//        }
//        bitmapImage.recycle()
//        return outputStream.toByteArray()
//    }

    fun sha256(base: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(base.toByteArray(StandardCharsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString()
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
    }

    fun analiseException(code: Int, raw: String?, error: String?, context: Context): String? {
        return try {
            assert(error != null)
            when (code) {
                401 -> "Acesso não autorizado"
                404 -> error
                else -> if (error!!.isNotEmpty()) error else raw
            }
        } catch (e: Exception) {
            e.printStackTrace()
            context.getString(R.string.service_failure)
        }
    }

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + " " + model
        }
    }

    private fun capitalize(s: String?): String {
        if (s == null || s.isEmpty()) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            first.uppercaseChar().toString() + s.substring(1)
        }
    }

    fun colorByStatus(status: String?): String {
        if (status?.contains("Roubo_e_Furto") == true) {
            return "#ff0000"
        } else if (status?.contains("Licenciamento") == true) {
            return "#7393B3"
        } else if (status?.contains("Clonagem") == true) {
            return "#A52A2A"
        } else if (status?.contains("Restricao_Judicial") == true) {
            return "#36013F"
        } else if (status?.contains("CNH_Suspensa") == true) {
            return "#DD6E0F"
        } else if (status?.contains("CNH_Vencida") == true) {
            return "#00FF00"
        } else if (status?.contains("CNH_Cassada") == true) {
            return "#ffc0cb"
        }

        return "#000000"
    }

    fun greatestCommonFactor(width: Int, height: Int): Int {
        return if (height == 0) width else greatestCommonFactor(height, width % height)
    }

    val brasilRegex = "[A-Z]{3}\\d[A-Z0-9]{1}\\d{2}"

    fun validateBrazilianLicensePlate(licensePlate: String): Boolean {
        val regex = Regex(brasilRegex)

        return regex.matches(licensePlate)
    }

    private fun getMatchedString(input: String, pattern: Regex): String? {
        val matchResult = pattern.find(input)

        return matchResult?.value
    }

    private fun removeSpecialCharacters(input: String): String {
        val pattern = """[^a-zA-Z0-9]""".toRegex()

        return pattern.replace(input, "")
    }

    fun normalizePlate(input: String): String {
        val result = removeSpecialCharacters(input)
        val resultMatch = getMatchedString(result, Regex(brasilRegex))
        if (resultMatch != null)
            return resultMatch.toString()
        else {
            val resultMatch2 = getMatchedString(result.replace("G", "0"), Regex(brasilRegex))
            if (resultMatch2 != null)
                return resultMatch2.toString()
        }

        return ""
    }

    fun getSecondsBetweenDates(start: LocalDateTime, end: LocalDateTime): Long {
        val duration = Duration.between(start, end)

        return duration.seconds
    }

    fun showDialog(activity: Activity, info: String?) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setContentView(R.layout.dialog_custom_layout)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.8).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
        )
        val textViewInfo = dialog.findViewById(R.id.textViewInfo) as TextView
        textViewInfo.text = Html.fromHtml(info, Html.FROM_HTML_MODE_LEGACY)
        val buttonFechar = dialog.findViewById(R.id.buttonFechar) as Button
        buttonFechar.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    @Throws(IOException::class)
    fun getFileFromAssets(context: Context, fileName: String): File = File(context.cacheDir, fileName)
        .also {
            if (!it.exists()) {
                it.outputStream().use { cache ->
                    context.assets.open(fileName).use { inputStream ->
                        inputStream.copyTo(cache)
                    }
                }
            }
        }

    fun getSimples(value: String): String {
        val decodedBytes: ByteArray = Base64.decode(value, Base64.DEFAULT)

        return String(decodedBytes, Charset.forName("UTF-8"))
    }
}