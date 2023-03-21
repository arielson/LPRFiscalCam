package br.net.ari.lprfiscalcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import br.net.ari.lprfiscalcam.core.PermissionUtils
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.core.YuvImageAnalyzer
import br.net.ari.lprfiscalcam.data.ImageInfoPOJO
import br.net.ari.lprfiscalcam.data.ImagePOJO
import br.net.ari.lprfiscalcam.enums.ImageFormat
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.vaxtor.alprlib.AlprOcr
import com.vaxtor.alprlib.VaxtorAlprManager
import com.vaxtor.alprlib.VaxtorLicensingManager
import com.vaxtor.alprlib.arguments.OcrFindPlatesImageArgs
import com.vaxtor.alprlib.arguments.OcrInitialiseArgs
import com.vaxtor.alprlib.enums.OperMode
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt


class CameraActivity : AppCompatActivity() {
    private lateinit var manager : VaxtorAlprManager
    private var initOcr : Long = -1

    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private lateinit var cameraControl : CameraControl
    private lateinit var cameraInfo : CameraInfo

    private lateinit var textViewTemperature : TextView
    private var intentfilter: IntentFilter? = null

    private val zoomScaleMin = 1.0f
    private val zoomScaleMax = 8.0f
    private var zoomScaleCurrent = 1.0f
    private val zoomScaleFactor = 0.05f


    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
        val textViewPlateLog = findViewById<TextView>(R.id.textViewPlateLog)
        textViewTemperature = findViewById(R.id.textViewTemperature)
        textViewPlateLog.movementMethod = ScrollingMovementMethod()

        val buttonZoomIn = findViewById<Button>(R.id.buttonZoomIn)
        val buttonZoomOut = findViewById<Button>(R.id.buttonZoomOut)
        val buttonZoomZero = findViewById<Button>(R.id.buttonZoomZero)
        val buttonAutoFoco = findViewById<Button>(R.id.buttonAutoFoco)

        PermissionUtils.requestPermission(this, PermissionUtils.cameraPermissions)

        intentfilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(broadcastreceiver, intentfilter)

        try {
            val ocrFile = File(cacheDir, "ocr_data.bin")
            if (!ocrFile.exists()) {
                ocrFile.createNewFile()
                val openRawResource = resources.openRawResource(com.vaxtor.alprlib.R.raw.ocr_data)
                val fileOutputStream = FileOutputStream(ocrFile)
                openRawResource.copyTo(fileOutputStream)
            }

            manager = VaxtorAlprManager(ocrFile.absolutePath)
            val countries = longArrayOf(AlprOcr.ocrGetWorldCountryStateCode("Brazil"))
            val ocrInitialiseArgs = OcrInitialiseArgs(
                oper_mode = OperMode.ASYNC.code,
                list_countries_codes = countries,
                list_num_countries = countries.size,
                ocr_complexity = 2,
                grammar_strict = 1,
                min_global_confidence = 80,
                min_character_confidence = 70,
                same_plate_delay = 8,
                same_plate_max_chars_distance = 1,
                max_slop_angle = 30,
                background_mode = 1,
                min_num_plate_characters = 7,
                max_num_plate_characters = 7,
                min_char_height = 18,
                max_char_height = 42,
                detect_multiline_plate = 1
            )

            initOcr = manager.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
            val teste = VaxtorLicensingManager.getC2V()
            if (initOcr < 0) {
                if (teste.isNotEmpty()) {
                    val ret = VaxtorLicensingManager.setC2V(teste)
                    if (ret < -1) {
                        VaxtorLicensingManager.registerLicense("981287e4-d75e-495e-bf71-dba9f0e31369") { bool, error ->
                            if (bool) {
                                initOcr = manager.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
                                Toast.makeText(this.applicationContext, "Success registering license", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this.applicationContext, "Error registering license $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        initOcr = manager.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
                    }
                }
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(this.applicationContext)
            cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val sizeRotated = Size(1920, 1080)

                val preview = Preview.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder?.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build()
                        .also {
                            it.setAnalyzer(
                                cameraExecutor,
                                YuvImageAnalyzer(:: onYuvImage)
                            )
                        }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                camera.cameraControl.cancelFocusAndMetering()
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo

//                val characteristics = Camera2CameraInfo.extractCameraCharacteristics(cameraInfo)
//                val mono = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
//                if (mono == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO) {
//                    Log.d("Mono", "1")
//                } else if (mono == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO) {
//                    Log.d("Mono", "2")
//                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (exc: Exception) {
                    Log.e("Erro", "Use case binding failed $exc")
                }

                buttonZoomIn.setOnClickListener {
                    if (zoomScaleCurrent < zoomScaleMax) {
                        zoomScaleCurrent += zoomScaleFactor
                        zoomScaleCurrent = (zoomScaleCurrent * 100.0f).roundToInt() / 100.0f

                        cameraControl.setZoomRatio(zoomScaleCurrent)
                        buttonAutoFoco.performClick()
                    }
                }

                buttonZoomOut.setOnClickListener {
                    if (zoomScaleCurrent > zoomScaleMin) {
                        zoomScaleCurrent -= zoomScaleFactor
                        zoomScaleCurrent = (zoomScaleCurrent * 100.0f).roundToInt() / 100.0f

                        cameraControl.setZoomRatio(zoomScaleCurrent)
                        buttonAutoFoco.performClick()
                    }
                }

                buttonZoomZero.setOnClickListener {
                    zoomScaleCurrent = 1.0f
                    cameraControl.setZoomRatio(zoomScaleCurrent)
                    buttonAutoFoco.performClick()
                }

                buttonAutoFoco.setOnClickListener {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        viewFinder.width.toFloat(), viewFinder.height.toFloat()
                    )
                    val centreX = viewFinder.x + viewFinder.width / 2
                    val centreY = viewFinder.y + viewFinder.height / 2
                    val autoFocusPoint = factory.createPoint(centreX, centreY)
                    try {
                        camera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                autoFocusPoint,
                                FocusMeteringAction.FLAG_AF
                            ).apply {
                                disableAutoCancel()
                            }.build()
                        )
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                }
                buttonAutoFoco.performClick()
            }, ContextCompat.getMainExecutor(this.applicationContext))

            manager.eventPlateInfoCallback = { it ->
                val plate = it._plate_info?._plate_number_asciivar
                Log.d("Placa:", "$plate")

                Utilities.service().GetVeiculo(plate, fiscalizacao.id)
                    .enqueue(object : Callback<Veiculo?> {
                        override fun onResponse(
                            call: Call<Veiculo?>,
                            response: Response<Veiculo?>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                val veiculo: Veiculo? = response.body()
                                veiculo?.placa = plate
                                val confPerc = it._plate_info?._plate_read_confidence?.div(100.0)
                                veiculo?.confianca = confPerc
                                veiculo?.dispositivo = Utilities.getDeviceName()
                                if (veiculo?.id!! > 0) {
                                    val fullText = "* $plate - ${veiculo.pendencia}\n\n${textViewPlateLog.text}\n"
                                    textViewPlateLog.text = fullText

                                    if (it._source_image != null ) {
                                        val imagePOJO = Utilities.mapImagePOJO(ImageInfoPOJO(
                                            _format = it._source_image!!._format,
                                            _height = it._source_image!!._height,
                                            _width = it._source_image!!._width,
                                            _image = it._source_image!!._image,
                                            _size = it._source_image!!._size
                                        ))

                                        var veiculoBitmap = Utilities.bitmapFromImagePojo(imagePOJO!!)!!
                                        val veiculoImage = Utilities.getScaledImage(veiculoBitmap, 640, 480)
                                        val veiculoImageBase64 = Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                        veiculo.foto2 = veiculoImageBase64
                                        val plateBox = it._plate_info?._plate_bounding_box!!
                                        val plateRect = Rect(plateBox[0], plateBox[1], plateBox[2], plateBox[3])
                                        veiculoBitmap = Utilities.bitmapFromImagePojo(imagePOJO)!!
                                        val plateBitamap = Utilities.cropBitmap(veiculoBitmap,
                                            plateRect
                                        )
                                        val byteArrayOutputStream = ByteArrayOutputStream()
                                        plateBitamap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                                        val byteArray: ByteArray = byteArrayOutputStream.toByteArray()
                                        val plateImageBase64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                        veiculo.foto1 = plateImageBase64
                                    }
                                    Utilities.service().PostVeiculo(veiculo)
                                        .enqueue(object : Callback<String?> {
                                            override fun onResponse(
                                                call: Call<String?>,
                                                response: Response<String?>
                                            ) {
                                                if (!response.isSuccessful) {
                                                    try {
                                                        Toast.makeText(
                                                            applicationContext, Utilities.analiseException(
                                                                response.code(),
                                                                response.raw().toString(),
                                                                if (response.errorBody() != null) response.errorBody()!!
                                                                    .string() else null,
                                                                applicationContext
                                                            ), Toast.LENGTH_LONG
                                                        ).show()
                                                    } catch (e: IOException) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }

                                            override fun onFailure(
                                                call: Call<String?>,
                                                t: Throwable
                                            ) {
                                                t.printStackTrace()
                                                Toast.makeText(applicationContext, "Erro ao acessar servidor. Verifique internet.", Toast.LENGTH_LONG).show()
                                            }
                                        })
                                } else {
                                    val fullText = "* $plate - OK\n\n${textViewPlateLog.text}\n"
                                    textViewPlateLog.text = fullText
                                }
                            }
                        }

                        override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                            t.printStackTrace()
                        }
                    })
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun onYuvImage(image: ImagePOJO) {
        try {
            recognizeYuvImage(image)
        } catch (e: Exception) {
            Log.e("Erro", "onYuvImage $e")
        }
    }

    private fun recognizeYuvImage(imagePOJO: ImagePOJO) {
        val ocrId = manager.ocrId ?: -111
        if (ocrId < 0) throw Exception("Can't recognize Image. Ocr init code  = $ocrId")

        manager.ocrApplyImageRotation(imagePOJO.rotationDegrees)

        if (imagePOJO.imageFormat == ImageFormat.YUV)
            manager.ocrFindPlatesYUV(
                OcrFindPlatesImageArgs(
                    id = ocrId,
                    src_image = imagePOJO.src,
                    image_width = imagePOJO.width.toLong(),
                    image_height = imagePOJO.height.toLong()
                )
            )
        else if (imagePOJO.imageFormat == ImageFormat.JPEG)
            manager.ocrFindPlatesJPEG(imagePOJO.src)
    }

    override fun onDestroy() {
        super.onDestroy()
        manager.shutdown()
        cameraExecutor.shutdown()
    }

    companion object {
        lateinit var fiscalizacao: Fiscalizacao
    }

    private val broadcastreceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toFloat() / 10
            val batteryTempString = "$batteryTemp ${0x00B0.toChar()}C"
            textViewTemperature.text = batteryTempString
        }
    }
}