package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.*
import android.os.BatteryManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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


class CameraActivity : AppCompatActivity() {
    private lateinit var manager: VaxtorAlprManager
    private var initOcr: Long = -1

    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var cameraProvider: ProcessCameraProvider

    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo

    private lateinit var textViewTemperature: TextView
    private var intentfilter: IntentFilter? = null

    private var minimumLens: Float? = null
    private var minimumLensNum: Float? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        val relativeLayoutMainContainer =
            findViewById<RelativeLayout>(R.id.relativeLayoutMainContainer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, relativeLayoutMainContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        initOcr = -1

        val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
        val textViewPlateLog = findViewById<TextView>(R.id.textViewPlateLog)
        textViewTemperature = findViewById(R.id.textViewTemperature)
        textViewPlateLog.movementMethod = ScrollingMovementMethod()

        val seekBarZoom = findViewById<SeekBar>(R.id.seekBarZoom)
        val seekBarFoco = findViewById<SeekBar>(R.id.seekBarFoco)

        val buttonExit = findViewById<Button>(R.id.buttonExit)
        buttonExit.setOnClickListener {
            buttonExit.isEnabled = false
            cameraProvider.unbindAll()
            Thread.sleep(100)
            imageAnalyzer.clearAnalyzer()
            Thread.sleep(100)
            cameraExecutor.shutdown()
            Thread.sleep(100)
            manager.finalize()
            Thread.sleep(100)
            manager.shutdown()
            Thread.sleep(100)
            finish()
        }

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
                same_plate_delay = 20,
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
            val c2v = VaxtorLicensingManager.getC2V()
            val sharedPreference = getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
            if (initOcr < 0) {
                if (c2v.isNotEmpty()) {
                    val ret = VaxtorLicensingManager.setC2V(c2v)
                    if (ret < -1) {
                        if (!sharedPreference.contains("chave")) {
                            val chave = sharedPreference.getString("chave", "")
                            if (chave != null && chave.isNotEmpty()) {
                                VaxtorLicensingManager.registerLicense(chave) { bool, _ ->
                                    if (bool) {
                                        initOcr = manager.initOcr(
                                            ocrInitialiseArgs,
                                            FirebaseCrashlytics.getInstance()
                                        )
                                    } else {
                                        val inputEditTextField = EditText(this)
                                        val dialog = AlertDialog.Builder(this)
                                            .setTitle("Chave")
                                            .setMessage("Chave inválida! Por favor digite a chave")
                                            .setView(inputEditTextField)
                                            .setPositiveButton("OK") { _, _ ->
                                                val editTextInput =
                                                    inputEditTextField.text.toString()
                                                val editor = sharedPreference.edit()
                                                editor.putString("chave", editTextInput)
                                                editor.apply()
                                                initOcr = manager.initOcr(
                                                    ocrInitialiseArgs,
                                                    FirebaseCrashlytics.getInstance()
                                                )
                                            }
                                            .setNegativeButton("Cancelar", null)
                                            .create()
                                        dialog.show()
                                    }
                                }
                            }
                        }
                    } else {
                        val editor = sharedPreference.edit()
                        editor.putString("c2v", c2v)
                        editor.apply()
                        initOcr =
                            manager.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
                    }
                }
            } else if (!sharedPreference.contains("c2v")) {
                val editor = sharedPreference.edit()
                editor.putString("c2v", c2v)
                editor.apply()
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(this.applicationContext)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()

                val sizeRotated = Size(1920, 1080)

                val preview = Preview.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

                imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            YuvImageAnalyzer(::onYuvImage)
                        )
                    }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                val camera =
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                camera.cameraControl.cancelFocusAndMetering()

                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                if (cameraManager.cameraIdList.isNotEmpty()) {
                    val cameraCharacteristics =
                        cameraManager.getCameraCharacteristics(cameraManager.cameraIdList[0])
                    val camCharacteristics =
                        cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val isManualFocus =
                        camCharacteristics?.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR }
                    if (isManualFocus == true) {
                        minimumLens =
                            cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                        val camera2CameraControl: Camera2CameraControl =
                            Camera2CameraControl.from(cameraControl)
                        val captureRequestOptions = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AF_MODE,
                                CameraMetadata.CONTROL_AF_MODE_OFF
                            )
                            .build()
                        camera2CameraControl.captureRequestOptions = captureRequestOptions
                    }
                }

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

                seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        val zoom = progress / 100.toFloat()
                        cameraControl.setLinearZoom(zoom)
                        val editor = sharedPreference.edit()
                        editor.putInt("zoom", progress)
                        editor.apply()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                val camera2CameraControl: Camera2CameraControl =
                    Camera2CameraControl.from(cameraControl)
                seekBarFoco.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        minimumLensNum = progress.toFloat() * minimumLens!! / 100
                        if (minimumLens != null) {
                            val captureRequestOptions = CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CameraMetadata.CONTROL_AF_MODE_OFF
                                )
                                .setCaptureRequestOption(
                                    CaptureRequest.LENS_FOCUS_DISTANCE,
                                    minimumLensNum!!
                                )
                                .build()
                            camera2CameraControl.captureRequestOptions = captureRequestOptions
                            val editor = sharedPreference.edit()
                            editor.putInt("foco", progress)
                            editor.apply()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                Toast.makeText(
                    applicationContext,
                    "Ajuste o zoom e foco deslizando na barra ao lado",
                    Toast.LENGTH_LONG
                ).show()

                if (sharedPreference.contains("zoom")) {
                    val progress = sharedPreference.getInt("zoom", 0)
                    seekBarZoom.progress = progress
                    val zoom = progress / 100.toFloat()
                    cameraControl.setLinearZoom(zoom)
                }

                if (sharedPreference.contains("foco")) {
                    val progress = sharedPreference.getInt("foco", 0)
                    seekBarFoco.progress = progress
                    if (minimumLens != null) {
                        minimumLensNum = progress.toFloat() * minimumLens!! / 100
                        val captureRequestOptions = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AF_MODE,
                                CameraMetadata.CONTROL_AF_MODE_OFF
                            )
                            .setCaptureRequestOption(
                                CaptureRequest.LENS_FOCUS_DISTANCE,
                                minimumLensNum!!
                            )
                            .build()
                        camera2CameraControl.captureRequestOptions = captureRequestOptions
                    }
                }
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
                                    val fullText =
                                        "* $plate - ${veiculo.pendencia}\n\n${textViewPlateLog.text}\n"
                                    textViewPlateLog.text = fullText

                                    if (it._source_image != null) {
                                        val imagePOJO = Utilities.mapImagePOJO(
                                            ImageInfoPOJO(
                                                _format = it._source_image!!._format,
                                                _height = it._source_image!!._height,
                                                _width = it._source_image!!._width,
                                                _image = it._source_image!!._image,
                                                _size = it._source_image!!._size
                                            )
                                        )

                                        var veiculoBitmap =
                                            Utilities.bitmapFromImagePojo(imagePOJO!!)!!
                                        val veiculoImage =
                                            Utilities.getScaledImage(veiculoBitmap, 640, 480)
                                        val veiculoImageBase64 =
                                            Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                        veiculo.foto2 = veiculoImageBase64
                                        val plateBox = it._plate_info?._plate_bounding_box!!
                                        val plateRect =
                                            Rect(plateBox[0], plateBox[1], plateBox[2], plateBox[3])
                                        veiculoBitmap = Utilities.bitmapFromImagePojo(imagePOJO)!!
                                        val plateBitamap = Utilities.cropBitmap(
                                            veiculoBitmap,
                                            plateRect
                                        )
                                        val byteArrayOutputStream = ByteArrayOutputStream()
                                        plateBitamap.compress(
                                            Bitmap.CompressFormat.PNG,
                                            100,
                                            byteArrayOutputStream
                                        )
                                        val byteArray: ByteArray =
                                            byteArrayOutputStream.toByteArray()
                                        val plateImageBase64 =
                                            Base64.encodeToString(byteArray, Base64.NO_WRAP)
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
                                                            applicationContext,
                                                            Utilities.analiseException(
                                                                response.code(),
                                                                response.raw().toString(),
                                                                if (response.errorBody() != null) response.errorBody()!!
                                                                    .string() else null,
                                                                applicationContext
                                                            ),
                                                            Toast.LENGTH_LONG
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
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Erro ao acessar servidor. Verifique internet.",
                                                    Toast.LENGTH_LONG
                                                ).show()
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

    companion object {
        lateinit var fiscalizacao: Fiscalizacao
    }

    private val broadcastreceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0).toFloat() / 10
            val batteryTempString = "$batteryTemp${0x00B0.toChar()}C"

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = level / scale.toDouble()
            val bt = (batteryPct * 100).toInt()

            val finalString = "Temp: $batteryTempString | Bat: $bt%"

            textViewTemperature.text = finalString
        }
    }
}