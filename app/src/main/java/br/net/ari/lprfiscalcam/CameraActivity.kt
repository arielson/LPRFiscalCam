package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.media.MediaPlayer
import android.os.*
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.Size
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
import br.net.ari.lprfiscalcam.core.*
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import com.google.android.gms.location.*
import org.tensorflow.lite.task.gms.vision.detector.Detection
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import android.util.Base64
import android.view.*
import br.net.ari.lprfiscalcam.data.Resolution
import br.net.ari.lprfiscalcam.dto.PlateDTO
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.LocalDateTime
import kotlin.properties.Delegates


class CameraActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {
    companion object {
        lateinit var fiscalizacao: Fiscalizacao
        var binData: Int = 0
    }

    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var cameraProvider: ProcessCameraProvider

    private var camera: Camera? = null
    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var cameraManager: CameraManager? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var camCharacteristics: IntArray? = null

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

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var activity: AppCompatActivity
    private lateinit var bitmapBuffer: Bitmap

    private lateinit var viewFinder: PreviewView
    private lateinit var seekBarZoom: SeekBar
    private lateinit var textViewZoom: TextView
    private lateinit var buttonZoomMenos: Button
    private lateinit var buttonZoomMais: Button
    private lateinit var seekBarFoco: SeekBar
    private lateinit var textViewFoco: TextView
    private lateinit var buttonFocoMenos: Button
    private lateinit var buttonFocoMais: Button
    private lateinit var textViewBrilho: TextView
    private lateinit var buttonBrilhoMenos: Button
    private lateinit var buttonBrilhoMais: Button
    private lateinit var textViewPlateLog: TextView
    private lateinit var mediaPlayer: MediaPlayer

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var switchNoite: Switch

    private lateinit var recognizer: TextRecognizer

    private var placas = mutableListOf<PlateDTO>()

    private var logText = ""

    private var ocrConfidence by Delegates.notNull<Float>()

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

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                setFoco()
                setZoom()
            }
        })
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

    @SuppressLint("DiscouragedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        activity = this

        val vezesTotal = 5
        var vezesTocada = 0
        val resID = resources.getIdentifier(
            "sirene", "raw",
            packageName
        )

        mediaPlayer = MediaPlayer.create(activity, resID)
        mediaPlayer.setOnCompletionListener {
            vezesTocada++
            if (vezesTocada < vezesTotal)
                mediaPlayer.start()
            else
                vezesTocada = 0
        }

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
        ocrConfidence = sharedPreference.getFloat("ocrconfidence", 0.95f)

        val relativeLayoutMainContainer =
            findViewById<RelativeLayout>(R.id.relativeLayoutMainContainer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, relativeLayoutMainContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val windowInsetsController = window.insetsController
            windowInsetsController?.let {
                it.hide(WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        viewFinder = findViewById(R.id.viewFinder)
        textViewPlateLog = findViewById(R.id.textViewPlateLog)
        textViewTemperature = findViewById(R.id.textViewTemperature)
        textViewPlateLog.movementMethod = ScrollingMovementMethod()

        seekBarZoom = findViewById(R.id.seekBarZoom)
        textViewZoom = findViewById(R.id.textViewZoom)
        buttonZoomMais = findViewById(R.id.buttonZoomMais)
        buttonZoomMenos = findViewById(R.id.buttonZoomMenos)
        seekBarFoco = findViewById(R.id.seekBarFoco)
        textViewFoco = findViewById(R.id.textViewFoco)
        buttonFocoMais = findViewById(R.id.buttonFocoMais)
        buttonFocoMenos = findViewById(R.id.buttonFocoMenos)
        seekBarBrilho = findViewById(R.id.seekBarBrilho)
        buttonBrilhoMenos = findViewById(R.id.buttonBrilhoMenos)
        buttonBrilhoMais = findViewById(R.id.buttonBrilhoMais)
        textViewBrilho = findViewById(R.id.textViewBrilho)

        switchNoite = findViewById(R.id.switchNoite)
        switchNoite.isChecked = binData == 1
        switchNoite.setOnCheckedChangeListener { _, isChecked ->
            var binData = 0
            if (isChecked)
                binData = 1
            CameraActivity.binData = binData
            val intent = Intent(activity, CameraActivity::class.java)
            startActivity(intent)
            finish()
        }

        buttonZoomMenos.setOnClickListener {
            if (seekBarZoom.progress > seekBarZoom.min)
                changeZoom(seekBarZoom.progress - 1)
        }

        buttonZoomMais.setOnClickListener {
            if (seekBarZoom.progress < seekBarZoom.max)
                changeZoom(seekBarZoom.progress + 1)
        }

        buttonFocoMenos.setOnClickListener {
            if (seekBarFoco.progress > seekBarFoco.min)
                changeFoco(seekBarFoco.progress - 1)
        }

        buttonFocoMais.setOnClickListener {
            if (seekBarFoco.progress < seekBarFoco.max)
                changeFoco(seekBarFoco.progress + 1)
        }

        buttonBrilhoMenos.setOnClickListener {
            Log.d("BRILHO MENOS", "${seekBarBrilho.progress} - ${seekBarBrilho.min}")
            if (seekBarBrilho.progress > seekBarBrilho.min)
                changeBrilho(seekBarBrilho.progress - 1)
        }

        buttonBrilhoMais.setOnClickListener {
            Log.d("BRILHO MAIS", "${seekBarBrilho.progress} - ${seekBarBrilho.max}")
            if (seekBarBrilho.progress < seekBarBrilho.max)
                changeBrilho(seekBarBrilho.progress + 1)
        }

        val buttonClose = findViewById<Button>(R.id.buttonClose)
        buttonClose.setOnClickListener {
            buttonClose.isEnabled = false
            cameraProvider.unbindAll()
            Thread.sleep(50)
            imageAnalyzer.clearAnalyzer()
            Thread.sleep(50)
            cameraExecutor.shutdown()
            Thread.sleep(50)
            finish()
        }

        val buttonExit = findViewById<Button>(R.id.buttonExit)
        buttonExit.setOnClickListener {
            buttonExit.isEnabled = false
            val builder = AlertDialog.Builder(this@CameraActivity)
            builder.setMessage("Ao sair os dados de login, senha e operação serão limpos e a chave utilizada perderá o acesso por alguns dias. Deseja continuar?")
                .setCancelable(false)
                .setPositiveButton("Sim") { _, _ ->
                    val chave = sharedPreference.getString("chave", "")
                    val uuid = sharedPreference.getString("uuid", "")
                    Utilities.service().cleanCameraByChave(chave, uuid)
                        .enqueue(object : Callback<br.net.ari.lprfiscalcam.models.Camera?> {
                            override fun onResponse(
                                call: Call<br.net.ari.lprfiscalcam.models.Camera?>,
                                response: Response<br.net.ari.lprfiscalcam.models.Camera?>
                            ) {
                                if (response.isSuccessful && response.body() != null) {
                                    cameraProvider.unbindAll()
                                    Thread.sleep(50)
                                    imageAnalyzer.clearAnalyzer()
                                    Thread.sleep(50)
                                    cameraExecutor.shutdown()
                                    Thread.sleep(50)
                                    Utilities.clearSharedPreferences(activity, "lprfiscalcam")
                                    val intent = Intent(activity, MainActivity::class.java)
                                    startActivity(intent)
                                    finish()
                                } else {
                                    Utilities.showDialog(
                                        activity,
                                        Utilities.analiseException(
                                            response.code(), response.raw().toString(),
                                            if (response.errorBody() != null) response.errorBody()!!
                                                .string() else null,
                                            applicationContext
                                        ),
                                        null
                                    )
                                }
                            }

                            override fun onFailure(
                                call: Call<br.net.ari.lprfiscalcam.models.Camera?>,
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

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            binData = binData,
            objectDetectorListener = this
        )
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError", "VisibleForTests")
    fun setupCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this.applicationContext)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()

                cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                if (cameraManager != null) {
                    cameraCharacteristics =
                        cameraManager?.getCameraCharacteristics(cameraManager!!.cameraIdList[0])
                    camCharacteristics =
                        cameraCharacteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                }

                val streamConfigurationMap =
                    cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val resolutions =
                    streamConfigurationMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                val resolutionsCamera = mutableListOf<Resolution>()
                resolutions?.forEach { size ->
                    Log.d("Resolution", "${size.width} x ${size.height}")
                    resolutionsCamera.add(Resolution(size.width, size.height))
                }
                val resolutionCorrect = Utilities.resolutionCorrection(resolutionsCamera)
                val sizeRotated = Size(resolutionCorrect.width, resolutionCorrect.height)

                val preview = Preview.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build()
                    .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

                imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(sizeRotated)
                    .setTargetRotation(Surface.ROTATION_90)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { image ->
                            if (!::bitmapBuffer.isInitialized) {
                                bitmapBuffer = Bitmap.createBitmap(
                                    image.width,
                                    image.height,
                                    Bitmap.Config.ARGB_8888
                                )
                            }

                            detectObjects(image)
                        }
                    }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                camera =
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo
                camera?.cameraControl?.cancelFocusAndMetering()

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
                        val text = "ZOOM [$zoom]"
                        textViewZoom.text = text
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
                            val text = "FOCO [$progress]"
                            textViewFoco.text = text
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })

                Toast.makeText(
                    applicationContext,
                    "Ajuste o zoom, foco e brilho deslizando nas barras ao lado",
                    Toast.LENGTH_SHORT
                ).show()

                setZoom()
                setFoco()
            }, ContextCompat.getMainExecutor(this.applicationContext))
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun setZoom() {
        if (sharedPreference.contains("zoom")) {
            val progress = sharedPreference.getInt("zoom", 0)
            seekBarZoom.progress = progress
            val zoom = progress / 100.toFloat()
            cameraControl?.setLinearZoom(zoom)
            val text = "ZOOM [$zoom]"
            textViewZoom.text = text
        }
    }

    private fun changeZoom(progress: Int) {
        seekBarZoom.progress = progress
        val zoom = progress / 100.toFloat()
        cameraControl?.setLinearZoom(zoom)
        val text = "ZOOM [$zoom]"
        textViewZoom.text = text
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setFoco() {
        if (sharedPreference.contains("foco")) {
            val progress = sharedPreference.getInt("foco", 0)
            seekBarFoco.progress = progress
            val text = "FOCO [$progress]"
            textViewFoco.text = text
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
                val camera2CameraControl: Camera2CameraControl =
                    Camera2CameraControl.from(cameraControl!!)
                camera2CameraControl.captureRequestOptions = captureRequestOptions
            }
        }
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError", "VisibleForTests")
    private fun changeFoco(progress: Int) {
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
            val camera2CameraControl: Camera2CameraControl =
                Camera2CameraControl.from(cameraControl!!)
            camera2CameraControl.captureRequestOptions = captureRequestOptions
            seekBarFoco.progress = progress
            editor.putInt("foco", progress)
            editor.apply()
            val text = "FOCO [$progress]"
            textViewFoco.text = text
        }
    }

    private fun detectObjects(image: ImageProxy) {
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        val imageRotation = image.imageInfo.rotationDegrees
        objectDetectorHelper.detect(bitmapBuffer, imageRotation)
    }

    private fun sendPlate(
        placa: String,
        confiabilidade: Float,
        bitmapPlaca: Bitmap,
        base64Veiculo: String,
        aferidor: Int
    ) {
        Log.d("Placa:", placa)

        val cameraId = sharedPreference.getLong("camera", 0)
        val veiculoInput = Veiculo()
        veiculoInput.placa = placa
        veiculoInput.fiscalizacaoId = fiscalizacao.id
        veiculoInput.dispositivo = Utilities.getDeviceName()
        veiculoInput.cameraId = cameraId
        veiculoInput.confianca = confiabilidade.toDouble()
        veiculoInput.latitude = latitude
        veiculoInput.longitude = longitude
        veiculoInput.aferidor = aferidor
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
                        veiculo?.placa = placa
                        veiculo?.confianca = confiabilidade.toDouble()
                        veiculo?.dispositivo = Utilities.getDeviceName()
                        if (veiculo?.id!! > 0) {
                            logText =
                                "<font color=${Utilities.colorByStatus(veiculo.pendencia)}>* $placa - ${veiculo.pendencia}</font><br><br>${logText}\n"
                            textViewPlateLog.text =
                                Html.fromHtml(logText, Html.FROM_HTML_MODE_LEGACY)

                            if (veiculo.pendencia?.contains("Roubo_e_Furto") == true) {
                                mediaPlayer.start()
                                Utilities.showDialog(
                                    activity,
                                    "${veiculo.marcaModelo}<br><font color=${
                                        Utilities.colorByStatus(veiculo.pendencia)
                                    }>$placa</font>",
                                    null
                                )
                            }

                            Thread {
                                val plateImageBase64 = Utilities.bitmapToBase64(bitmapPlaca)
                                veiculo.foto1 = plateImageBase64
                                veiculo.foto2 = base64Veiculo

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
                            }.start()
                        } else {
                            logText =
                                "<font color=#5EBA7D>* $placa - OK</font><br><br>${logText}\n"
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
                    val text = "BRILHO [$progress]"
                    textViewBrilho.text = text
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun changeBrilho(progress: Int) {
        cameraControl?.setExposureCompensationIndex(progress)
        seekBarBrilho.progress = progress
        val text = "BRILHO [$progress]"
        textViewBrilho.text = text
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError", "VisibleForTests")
    fun loadFocus() {
        if (cameraManager != null) {
            cameraCharacteristics =
                cameraManager?.getCameraCharacteristics(cameraManager!!.cameraIdList[0])
            camCharacteristics =
                cameraCharacteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        }
        if (cameraControl != null) {
            if (cameraManager != null && cameraManager!!.cameraIdList.isNotEmpty()) {
                val isManualFocus =
                    camCharacteristics?.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR }
                if (isManualFocus == true) {
                    minimumLens =
                        cameraCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
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

            val camera = br.net.ari.lprfiscalcam.models.Camera()
            val cameraId = sharedPreference.getLong("camera", 0)
            camera.id = cameraId
            camera.bateria = bt.toFloat()
            camera.temperatura = batteryTemp

            if (cameraId > 0)
                Utilities.service().setCamera2(camera)
                    .enqueue(object : Callback<br.net.ari.lprfiscalcam.models.Camera?> {
                        override fun onResponse(
                            call: Call<br.net.ari.lprfiscalcam.models.Camera?>,
                            response: Response<br.net.ari.lprfiscalcam.models.Camera?>
                        ) {
                            if (!response.isSuccessful) {
                                val error = Utilities.analiseException(
                                    response.code(), response.raw().toString(),
                                    if (response.errorBody() != null) response.errorBody()!!
                                        .string() else null,
                                    applicationContext
                                )
                                Log.e("ERRO POST", "$error")
                            }
                        }

                        override fun onFailure(
                            call: Call<br.net.ari.lprfiscalcam.models.Camera?>,
                            t: Throwable
                        ) {
                            t.printStackTrace()
                        }
                    })
        }
    }

    override fun onInitialized() {
        objectDetectorHelper.setupObjectDetector()
        setupCamera()
    }

    override fun onError(error: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, error, Toast.LENGTH_SHORT).show()
        }
    }

    private var lastPost = LocalDateTime.now()

    override fun onResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int,
        bitmap: Bitmap
    ) {
        if (results != null) {
            for (result in results) {
                val bndbox = result.boundingBox
                val confidence = result.categories.first().score
                Log.d("Confiabilidade", "${confidence * 100} %")
                Log.d("Tempo de inferência", "$inferenceTime ms")
                var left = 0f
                if (bndbox.left > 0)
                    left = bndbox.left
                var top = 0f
                if (bndbox.top > 0)
                    top = bndbox.top
                try {
                    bndbox.left = left
                    bndbox.top = top
                    val placa = Utilities.cropBitmap(bitmap, bndbox)

                    val placaInputImage: InputImage = if (placa.width > 640 && placa.height > 480) {
                        val plateImage =
                            Utilities.getScaledImage(placa, 640, 480)
                        val plateResizedBitmap =
                            BitmapFactory.decodeByteArray(plateImage, 0, plateImage.size)
                        InputImage.fromBitmap(plateResizedBitmap, 0)
                    } else
                        InputImage.fromBitmap(placa, 0)

                    recognizer.process(placaInputImage)
                        .addOnSuccessListener { visionText ->
                            val veiculoBitmap: Bitmap = bitmap.copy(bitmap.config, true)
                            var placaTexto = ""
                            for (textBlock in visionText.textBlocks) {
                                for (line in textBlock.lines) {
                                    Log.d("LINHA", "${line.text} - ${line.confidence}")

                                    if (line.confidence >= ocrConfidence)
                                        placaTexto += line.text
                                    else
                                        Log.d(
                                            "ERRO CONFIANÇA",
                                            "${line.text} - ${line.confidence} | $ocrConfidence"
                                        )
                                }
                            }
                            val placaNormalizada = Utilities.normalizePlate(placaTexto)
                            Log.d("PLACA NORMALIZADA", placaNormalizada)

                            val isBrasil = Utilities.validateBrazilianLicensePlate(placaNormalizada)
                            if (isBrasil) {
                                Log.d("PLACA FINAL", placaNormalizada)
                                limparPlacas()
                                if (!placas.any { it.placa == placaNormalizada }) {
                                    val placaDTO = PlateDTO()
                                    placaDTO.placa = placaNormalizada
                                    placaDTO.data = LocalDateTime.now()

                                    placas.add(placaDTO)
                                    val veiculoImage =
                                        Utilities.getScaledImage(veiculoBitmap, 640, 480)
                                    val veiculoImageBase64 =
                                        Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                    sendPlate(
                                        placaNormalizada,
                                        confidence,
                                        placaInputImage.bitmapInternal!!,
                                        veiculoImageBase64,
                                        1
                                    )
                                }
                            } else if (Utilities.getSecondsBetweenDates(
                                    lastPost,
                                    LocalDateTime.now()
                                ) >= 0.5
                            ) {
                                lastPost = LocalDateTime.now()
                                var postTime = SystemClock.uptimeMillis()
                                val cameraId = sharedPreference.getLong("camera", 0)
                                val veiculoInput = Veiculo()
                                veiculoInput.fiscalizacaoId = fiscalizacao.id
                                veiculoInput.cameraId = cameraId
                                veiculoInput.foto1 =
                                    Utilities.bitmapToBase64(placaInputImage.bitmapInternal!!)
                                Utilities.service().getPlaca(veiculoInput)
                                    .enqueue(object : Callback<Veiculo?> {
                                        override fun onResponse(
                                            call: Call<Veiculo?>,
                                            response: Response<Veiculo?>
                                        ) {
                                            if (response.isSuccessful && response.body() != null) {
                                                val veiculo = response.body()!!
                                                if (veiculo.placa != null) {
                                                    Log.d("Foto Placa", "${veiculo.placa}")
                                                    limparPlacas()
                                                    if (!placas.any { it.placa == veiculo.placa }) {
                                                        val placaDTO = PlateDTO()
                                                        placaDTO.placa = veiculo.placa
                                                        placaDTO.data = LocalDateTime.now()
                                                        val veiculoImage = Utilities.getScaledImage(
                                                            veiculoBitmap,
                                                            640,
                                                            480
                                                        )
                                                        val veiculoImageBase64 =
                                                            Base64.encodeToString(
                                                                veiculoImage,
                                                                Base64.NO_WRAP
                                                            )
                                                        placas.add(placaDTO)
                                                        sendPlate(
                                                            veiculo.placa!!,
                                                            confidence,
                                                            placaInputImage.bitmapInternal!!,
                                                            veiculoImageBase64,
                                                            2
                                                        )
                                                        postTime =
                                                            SystemClock.uptimeMillis() - postTime
                                                    } else {
                                                        postTime =
                                                            SystemClock.uptimeMillis() - postTime
                                                    }
                                                } else {
                                                    postTime = SystemClock.uptimeMillis() - postTime
                                                }
                                            } else {
                                                postTime = SystemClock.uptimeMillis() - postTime
                                                val error = Utilities.analiseException(
                                                    response.code(), response.raw().toString(),
                                                    if (response.errorBody() != null) response.errorBody()!!
                                                        .string() else null,
                                                    applicationContext
                                                )
                                                Log.e("ERRO POST", "$error")
                                            }
                                            Log.d("Tempo de post", "$postTime ms")
                                        }

                                        override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                                            t.printStackTrace()
                                        }
                                    })
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("ERRO", e.message!!)
                        }
                } catch (exc: Exception) {
                    Log.e("Erro", "Error $exc")
                }
            }
        }
    }

    fun limparPlacas() {
        val samePlateDelay = sharedPreference.getInt("sameplatedelay", 500)
        placas.removeAll {
            Utilities.getSecondsBetweenDates(
                it.data!!,
                LocalDateTime.now()
            ) > samePlateDelay
        }
    }
}