package br.net.ari.lprfiscalcam.core

import android.content.Context
import android.graphics.*
import android.os.Build
import br.net.ari.lprfiscalcam.R
import br.net.ari.lprfiscalcam.data.ImageInfoPOJO
import br.net.ari.lprfiscalcam.data.ImagePOJO
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit


object Utilities {
    private const val Host = "lprfiscalapi.ari.net.br"

    //    private final static String Host = "8ad6-191-136-213-194.ngrok.io";
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

    fun mapImagePOJO(sourceImage: ImageInfoPOJO?): ImagePOJO? {
        val width = sourceImage?._width?.toInt()
        val height = sourceImage?._height?.toInt()
        val image = sourceImage?._image
        val format = br.net.ari.lprfiscalcam.enums.ImageFormat.getFormat(sourceImage?._format?.toInt() ?: -1)
        return if (width != null && height != null && image != null && format != null)
            ImagePOJO(
                width = width,
                height = height,
                src = image,
                rotationDegrees = 0,
                imageFormat = format
            )
        else null
    }

    fun bitmapFromImagePojo(imagePOJO: ImagePOJO): Bitmap? = when (imagePOJO.imageFormat) {
        br.net.ari.lprfiscalcam.enums.ImageFormat.RGB -> {
            val src = imagePOJO.src
            val pix = IntArray(src.size / 3) { i ->
                val a = 0xff
                val r = (0xFF and src[3 * i].toInt())
                val g = (0xFF and src[3 * i + 1].toInt())
                val b = (0xFF and src[3 * i + 2].toInt())
                Color.argb(a, r, g, b)
            }
            Bitmap.createBitmap(pix, imagePOJO.width, imagePOJO.height, Bitmap.Config.ARGB_8888)
        }
        br.net.ari.lprfiscalcam.enums.ImageFormat.JPEG -> {
            imagePOJO.src.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        br.net.ari.lprfiscalcam.enums.ImageFormat.YUV -> {
            val yuvImage =
                YuvImage(imagePOJO.src, ImageFormat.NV21, imagePOJO.width, imagePOJO.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
            out.toByteArray().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        else -> throw Exception("can't create from ${imagePOJO.imageFormat}")
    }

    fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap =
        Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())

    fun getScaledImage(bitmapImage: Bitmap, newWidth: Int, newHeight: Int): ByteArray? {
        val mutableBitmapImage = Bitmap.createScaledBitmap(bitmapImage, newWidth, newHeight, false)
        val outputStream = ByteArrayOutputStream()
        mutableBitmapImage.compress(Bitmap.CompressFormat.JPEG, 0, outputStream)
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
}