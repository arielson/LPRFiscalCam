package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.Html
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
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.net.ari.lprfiscalcam.core.*
import br.net.ari.lprfiscalcam.data.ImageInfoPOJO
import br.net.ari.lprfiscalcam.data.ImagePOJO
import br.net.ari.lprfiscalcam.enums.ImageFormat
import br.net.ari.lprfiscalcam.models.Camera
import br.net.ari.lprfiscalcam.models.CameraLog
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.android.gms.location.*
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
import java.util.*
import java.util.concurrent.Executors


class CameraActivity : AppCompatActivity() {
    companion object {
        lateinit var fiscalizacao: Fiscalizacao
    }

    private lateinit var manager: VaxtorAlprManager
    private var initOcr: Long = -1

    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var cameraProvider: ProcessCameraProvider

    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    private lateinit var textViewTemperature: TextView
    private var intentfilter: IntentFilter? = null

    private var minimumLens: Float? = null
    private var minimumLensNum: Float? = null

    private lateinit var seekBarBrilho: SeekBar

    private lateinit var sharedPreference: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var latitude: Double? = null
    private var longitude: Double? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CODE && grantResults.contains(PermissionUtils.Permission.CAMERA.ordinal)) {
            loadFocus()
            loadBrilho()
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError", "VisibleForTests")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        PermissionUtils.requestPermission(this, PermissionUtils.cameraPermissions)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).apply {
            setMinUpdateDistanceMeters(5f)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(true)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    Log.d("Latitude", "${location.latitude}")
                    latitude = location.latitude
                    Log.d("Longitude", "${location.longitude}")
                    longitude = location.longitude
                }
            }
        }

        sharedPreference = getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
        editor = sharedPreference.edit()

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
        seekBarBrilho = findViewById(R.id.seekBarBrilho)

        val buttonClose = findViewById<Button>(R.id.buttonClose)
        buttonClose.setOnClickListener {
            buttonClose.isEnabled = false
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

        val buttonExit = findViewById<Button>(R.id.buttonExit)
        buttonExit.setOnClickListener {
            buttonExit.isEnabled = false
            val builder = AlertDialog.Builder(this@CameraActivity)
            builder.setMessage("Ao sair os dados de login, senha e operação serão limpos. Deseja continuar?")
                .setCancelable(false)
                .setPositiveButton("Sim") { _, _ ->
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
                    editor.remove("fiscalizacao")
                    editor.remove("login")
                    editor.remove("senha")
                    editor.apply()
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Não") { dialog, _ ->
                    buttonExit.isEnabled = true
                    dialog.dismiss()
                }
            val alert = builder.create()
            alert.show()
        }

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
                ocr_complexity = Constants.OCRComplexity,
                grammar_strict = Constants.GrammarStrict,
                min_global_confidence = Constants.MinGlobalConfidence,
                min_character_confidence = Constants.MinCharacterConfidence,
                same_plate_delay = Constants.SamePlateDelay,
                same_plate_max_chars_distance = Constants.SamePlateMaxCharsDistance,
                max_slop_angle = Constants.MaxSlopAngle,
                background_mode = Constants.BackgroundMode,
                min_num_plate_characters = Constants.MinNumPlateCharacters,
                max_num_plate_characters = Constants.MaxNumPlateCharacters,
                min_char_height = Constants.MinCharHeight,
                max_char_height = Constants.MaxCharHeight,
                detect_multiline_plate = Constants.DetectMultilinePlate
            )

            initOcr = manager.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())

            if (initOcr == -1L) {
                Toast.makeText(
                    applicationContext,
                    "Atualizando chave...",
                    Toast.LENGTH_LONG
                ).show()
                val chave = sharedPreference.getString("chave_lprfiscal", "")
                if (chave != null && chave.isNotEmpty()) {
                    Utilities.service().getCameraByChave(chave)
                        .enqueue(object : Callback<Camera?> {
                            override fun onResponse(
                                call: Call<Camera?>,
                                response: Response<Camera?>
                            ) {
                                if (response.isSuccessful && response.body() != null) {
                                    val camera = response.body()!!
                                    VaxtorLicensingManager.registerLicense(camera.chaveVaxtor!!) { bool, error ->
                                        if (!bool) {
                                            val packageInfo = getPackageInfo()
                                            val verCode =
                                                PackageInfoCompat.getLongVersionCode(packageInfo)
                                                    .toInt()

                                            val initOcr = manager.initOcr(
                                                ocrInitialiseArgs,
                                                FirebaseCrashlytics.getInstance()
                                            )

                                            if (initOcr < 1) {
                                                val cameraLog = CameraLog()
                                                cameraLog.cameraId = camera.id
                                                cameraLog.dispositivo =
                                                    Utilities.getDeviceName()
                                                cameraLog.texto =
                                                    "App VerCode: $verCode <br> Chave: $chave <br> Chave Vaxtor: ${camera.chaveVaxtor!!} <br> Erro initOcr: $initOcr <br> Erro: $error"
                                                Utilities.service()
                                                    .setLog(cameraLog)
                                                    .enqueue(object :
                                                        Callback<Void?> {
                                                        override fun onResponse(
                                                            call: Call<Void?>,
                                                            response: Response<Void?>
                                                        ) {
                                                            if (response.isSuccessful) {
                                                                Toast.makeText(
                                                                    applicationContext,
                                                                    "Ocorreu um erro! Log enviado com sucesso. Suporte irá verificar o problema.",
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            } else {
                                                                Toast.makeText(
                                                                    applicationContext,
                                                                    Utilities.analiseException(
                                                                        response.code(),
                                                                        response.raw()
                                                                            .toString(),
                                                                        if (response.errorBody() != null) response.errorBody()!!
                                                                            .string() else null,
                                                                        applicationContext
                                                                    ),
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        }

                                                        override fun onFailure(
                                                            call: Call<Void?>,
                                                            t: Throwable
                                                        ) {
                                                            t.printStackTrace()
                                                            Toast.makeText(
                                                                applicationContext,
                                                                R.string.service_failure,
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    })
                                            } else {
                                                sendData(camera)
                                            }
                                        }
                                    }
                                } else {
                                    Toast.makeText(
                                        applicationContext, Utilities.analiseException(
                                            response.code(), response.raw().toString(),
                                            if (response.errorBody() != null) response.errorBody()!!
                                                .string() else null,
                                            applicationContext
                                        ), Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            override fun onFailure(call: Call<Camera?>, t: Throwable) {
                                t.printStackTrace()
                                Toast.makeText(
                                    applicationContext,
                                    R.string.service_failure,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Chave local não encontrada",
                        Toast.LENGTH_LONG
                    ).show()
                }
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

                loadFocus()
                loadBrilho()

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
                        cameraControl?.setLinearZoom(zoom)
                        editor.putInt("zoom", progress)
                        editor.apply()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                val camera2CameraControl: Camera2CameraControl =
                    Camera2CameraControl.from(cameraControl!!)
                seekBarFoco.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
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
                            editor.putInt("foco", progress)
                            editor.apply()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                Toast.makeText(
                    applicationContext,
                    "Ajuste o zoom, foco e brilho deslizando nas barras ao lado",
                    Toast.LENGTH_LONG
                ).show()

                if (sharedPreference.contains("zoom")) {
                    val progress = sharedPreference.getInt("zoom", 0)
                    seekBarZoom.progress = progress
                    val zoom = progress / 100.toFloat()
                    cameraControl?.setLinearZoom(zoom)
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

            var logText = ""
            manager.eventPlateInfoCallback = { it ->
                val plate = it._plate_info?._plate_number_asciivar
                Log.d("Placa:", "$plate")

                val cameraId = sharedPreference.getLong("camera", 0)
                val veiculoInput = Veiculo()
                veiculoInput.placa = plate
                veiculoInput.fiscalizacaoId = fiscalizacao.id
                veiculoInput.dispositivo = Utilities.getDeviceName()
                veiculoInput.cameraId = cameraId
                veiculoInput.latitude = latitude
                veiculoInput.longitude = longitude
                Utilities.service()
                    .setVeiculo(veiculoInput)
                    .enqueue(object : Callback<Veiculo?> {
                        override fun onResponse(
                            call: Call<Veiculo?>,
                            response: Response<Veiculo?>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                val veiculo: Veiculo? = response.body()
                                veiculo?.cameraId = cameraId
                                veiculo?.placa = plate
                                val confPerc = it._plate_info?._plate_read_confidence?.div(100.0)
                                veiculo?.confianca = confPerc
                                veiculo?.dispositivo = Utilities.getDeviceName()
                                if (veiculo?.id!! > 0) {
                                    logText =
                                        "<font color=${Utilities.colorByStatus(veiculo.pendencia)}>* $plate - ${veiculo.pendencia}</font><br><br>${logText}\n"
                                    textViewPlateLog.text =
                                        Html.fromHtml(logText, Html.FROM_HTML_MODE_LEGACY)

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

                                        if (veiculo.pendencia?.contains("Roubo_e_Furto") == true) {
                                            val veiculoImage =
                                                Utilities.getScaledImage(veiculoBitmap, 640, 480)
                                            val veiculoImageBase64 =
                                                Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                            veiculo.foto2 = veiculoImageBase64
                                        }

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
                                            Bitmap.CompressFormat.JPEG,
                                            100,
                                            byteArrayOutputStream
                                        )
                                        val byteArray: ByteArray =
                                            byteArrayOutputStream.toByteArray()
                                        val plateImageBase64 =
                                            Base64.encodeToString(byteArray, Base64.NO_WRAP)
                                        veiculo.foto1 = plateImageBase64
                                    }
                                    Utilities.service().postVeiculo(veiculo)
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
                                    logText =
                                        "<font color=#5EBA7D>* $plate - OK</font><br><br>${logText}\n"
                                    textViewPlateLog.text =
                                        Html.fromHtml(logText, Html.FROM_HTML_MODE_LEGACY)
                                }
                            } else {
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
                            }
                        }

                        override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                            t.printStackTrace()
                            Toast.makeText(
                                applicationContext,
                                R.string.service_failure,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun loadBrilhoData(): Boolean {
        if (cameraInfo != null) {
            val exposure = cameraInfo?.exposureState
            if (exposure?.isExposureCompensationSupported == true) {
                val exposureRange = exposure.exposureCompensationRange
                val min = exposureRange.lower
                val max = exposureRange.upper
                seekBarBrilho.min = min
                seekBarBrilho.max = max

                return true
            }
        }
        return false
    }

    private fun loadBrilho() {
        if (loadBrilhoData()) {
            seekBarBrilho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    cameraControl?.setExposureCompensationIndex(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError", "VisibleForTests")
    fun loadFocus() {
        if (cameraControl != null) {
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
                        Camera2CameraControl.from(cameraControl!!)
                    val captureRequestOptions = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_OFF
                        )
                        .build()
                    camera2CameraControl.captureRequestOptions = captureRequestOptions
                }
            }
        }
    }

    private fun onYuvImage(image: ImagePOJO) {
        try {
            recognizeYuvImage(image)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "Erro: $e", Toast.LENGTH_SHORT)
                .show()
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

    @Suppress("DEPRECATION")
    fun getPackageInfo(): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    fun sendData(
        camera: Camera
    ) {
        val c2V = VaxtorLicensingManager.getC2V()
        val cameraInput = Camera()
        cameraInput.id = camera.id
        cameraInput.c2V = c2V
        cameraInput.uuid = Utilities.getDeviceName()
        Utilities.service().patchC2VByChave(cameraInput)
            .enqueue(object : Callback<Camera?> {
                override fun onResponse(
                    call: Call<Camera?>,
                    response: Response<Camera?>
                ) {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            applicationContext, Utilities.analiseException(
                                response.code(), response.raw().toString(),
                                if (response.errorBody() != null) response.errorBody()!!
                                    .string() else null,
                                applicationContext
                            ), Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(
                    call: Call<Camera?>,
                    t: Throwable
                ) {
                    t.printStackTrace()
                    Toast.makeText(applicationContext, R.string.service_failure, Toast.LENGTH_LONG)
                        .show()
                }
            })
    }
}