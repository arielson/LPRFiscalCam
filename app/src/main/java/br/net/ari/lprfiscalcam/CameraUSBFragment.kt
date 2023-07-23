package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.content.*
import android.graphics.*
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Bundle
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import br.net.ari.lprfiscalcam.core.ObjectDetectorHelper
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.databinding.FragmentCameraUSBBinding
import br.net.ari.lprfiscalcam.dto.PlateDTO
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.android.gms.location.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.ToastUtils
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import kotlinx.coroutines.*
import org.tensorflow.lite.task.gms.vision.detector.Detection
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime

private const val ARG_PARAM1 = "param1"

/**
 * A simple [Fragment] subclass.
 * Use the [CameraUSBFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CameraUSBFragment : CameraFragment(), IPreviewDataCallBack,
    ObjectDetectorHelper.DetectorListener {
    private lateinit var mViewBinding: FragmentCameraUSBBinding

    private var camera: MultiCameraClient.ICamera? = null

    private lateinit var textViewTemperature: TextView
    private var intentfilter: IntentFilter? = null

    private lateinit var activity: CameraUSBActivity

    private lateinit var sharedPreference: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private lateinit var textViewPlateLog: TextView
    private lateinit var mediaPlayer: MediaPlayer

    private lateinit var recognizer: TextRecognizer

    private var placas = mutableListOf<PlateDTO>()

    private var logText = ""

    @SuppressLint("DiscouragedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = this.getActivity() as CameraUSBActivity
        textViewTemperature = view.findViewById(R.id.textViewTemperature)

        sharedPreference = activity.getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
        editor = sharedPreference.edit()

        val buttonExit = view.findViewById<Button>(R.id.buttonExit)
        buttonExit?.setOnClickListener {
            buttonExit.isEnabled = false
            camera?.removePreviewDataCallBack(this)
            Thread.sleep(200)
            closeCamera()
            Thread.sleep(100)
            activity.finish()
        }

        intentfilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        requireContext().registerReceiver(broadcastreceiver, intentfilter)

        val vezesTotal = 5
        var vezesTocada = 0
        val resID = resources.getIdentifier(
            "sirene", "raw",
            activity.packageName
        )

        mediaPlayer = MediaPlayer.create(activity, resID)
        mediaPlayer.setOnCompletionListener {
            vezesTocada++
            if (vezesTocada < vezesTotal)
                mediaPlayer.start()
            else
                vezesTocada = 0
        }

        textViewPlateLog = view.findViewById(R.id.textViewPlateLog)
        textViewPlateLog.movementMethod = ScrollingMovementMethod()

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        objectDetectorHelper = ObjectDetectorHelper(
            context = requireContext(),
            objectDetectorListener = this
        )
    }

    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(requireContext())
    }

    override fun getCameraViewContainer(): ViewGroup {
        return mViewBinding.cameraViewContainer
    }

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        mViewBinding = FragmentCameraUSBBinding.inflate(inflater, container, false)
        return mViewBinding.root
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> handleCameraOpened()
            ICameraStateCallBack.State.CLOSED -> handleCameraClosed()
            ICameraStateCallBack.State.ERROR -> handleCameraError(msg)
        }
    }

    private fun handleCameraOpened() {
        ToastUtils.show("camera opened success")
        camera = getCurrentCamera()
        camera?.addPreviewDataCallBack(this)
    }

    private fun handleCameraClosed() {
        ToastUtils.show("camera closed success")
    }

    private fun handleCameraError(msg: String?) {
        ToastUtils.show("camera opened error: $msg")
    }

    override fun getCameraRequest(): CameraRequest {
        val previewSizes = camera?.getAllPreviewSizes()
        val maxPreviewSize = previewSizes?.maxBy { it.width }
        var width = 1280
        var height = 720
        if (maxPreviewSize != null) {
            width = maxPreviewSize.width
            height = maxPreviewSize.height
        }

        return CameraRequest.Builder()
            .setPreviewWidth(width) // camera preview width
            .setPreviewHeight(height) // camera preview height
            .setRenderMode(CameraRequest.RenderMode.OPENGL) // camera render mode
            .setDefaultRotateType(RotateType.ANGLE_0) // rotate camera image when opengl mode
            .setAudioSource(CameraRequest.AudioSource.NONE) // set audio source
            .setAspectRatioShow(true) // aspect render,default is true
            .setCaptureRawImage(false) // capture raw image picture when opengl mode
            .setRawPreviewData(true)  // preview raw image when opengl mode
            .create()
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param fiscalizacaoId Id da fiscaliazção.
         * @return A new instance of fragment CameraUSBFragment.
         */
        @JvmStatic
        fun newInstance(fiscalizacaoId: Long) =
            CameraUSBFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_PARAM1, fiscalizacaoId)
                }
            }
    }

    override fun onPreviewData(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        if (data != null) {
            try {
                val bitmapJob = CoroutineScope(Dispatchers.Unconfined).launch {
                    val bitmap = byteArrayToBitmap(data, width, height)
                    if (bitmap != null)
                        detectObjects(bitmap)
                }

                runBlocking {
                    bitmapJob.join()
                }
            } catch (ex: java.lang.Exception) {
                Log.e("onPreviewData", "${ex.message}")
            }
        }
    }

    private suspend fun byteArrayToBitmap(byteArray: ByteArray, width: Int, height: Int): Bitmap? = withContext(
        Dispatchers.IO
    ) {
        val yuvImage = YuvImage(byteArray, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        val rect = Rect(0, 0, width, height)

        if (!yuvImage.compressToJpeg(rect, 80, outputStream)) {
            return@withContext null
        }

        val jpegByteArray = outputStream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)

        outputStream.close()

        return@withContext bitmap
    }

    private fun detectObjects(bitmap: Bitmap) {
        try {
            objectDetectorHelper.detect(bitmap, 0)
        } catch (ex: Exception) {
            Log.e("detectObjects", "${ex.message}")
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

            Utilities.service().setCamera(camera)
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
                                requireContext()
                            )
                            Log.e("ERRO POST", "$error")
                        }
                    }

                    override fun onFailure(call: Call<br.net.ari.lprfiscalcam.models.Camera?>, t: Throwable) {
                        t.printStackTrace()
                    }
                })
        }
    }

    private fun sendPlate(
        placa: String,
        confiabilidade: Float,
        bitmapPlaca: Bitmap,
        bitmapVeiculo: Bitmap,
        aferidor: Int
    ) {
        Log.d("Placa:", placa)

        val cameraId = sharedPreference.getLong("camera", 0)
        val veiculoInput = Veiculo()
        veiculoInput.placa = placa
        veiculoInput.fiscalizacaoId = CameraUSBActivity.fiscalizacaoId
        veiculoInput.dispositivo = Utilities.getDeviceName()
        veiculoInput.cameraId = cameraId
        veiculoInput.confianca = confiabilidade.toDouble()
        veiculoInput.latitude = activity.latitude
        veiculoInput.longitude = activity.longitude
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
                                val veiculoImage =
                                    Utilities.getScaledImage(bitmapVeiculo, 640, 480)
                                val veiculoImageBase64 =
                                    Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                veiculo.foto2 = veiculoImageBase64

                                val plateImageBase64 = Utilities.bitmapToBase64(bitmapPlaca)
                                veiculo.foto1 = plateImageBase64

                                Utilities.service().postVeiculo(veiculo)
                                    .enqueue(object : Callback<String?> {
                                        override fun onResponse(
                                            call: Call<String?>,
                                            response: Response<String?>
                                        ) {
                                            if (!response.isSuccessful) {
                                                try {
                                                    Toast.makeText(
                                                        requireContext(),
                                                        Utilities.analiseException(
                                                            response.code(),
                                                            response.raw().toString(),
                                                            if (response.errorBody() != null) response.errorBody()!!
                                                                .string() else null,
                                                            requireContext()
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
                                                requireContext(),
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
                            requireContext(),
                            Utilities.analiseException(
                                response.code(),
                                response.raw().toString(),
                                if (response.errorBody() != null) response.errorBody()!!
                                    .string() else null,
                                requireContext()
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                    t.printStackTrace()
                    Toast.makeText(
                        requireContext(),
                        R.string.service_failure,
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    override fun onInitialized() {
        objectDetectorHelper.setupObjectDetector()
    }

    override fun onError(error: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, error, Toast.LENGTH_SHORT).show()
        }
    }

    private var isPost = false

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
                    val inputImage = InputImage.fromBitmap(placa, 0)

                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            var placaTexto = ""
                            for (textBlock in visionText.textBlocks) {
                                for (line in textBlock.lines) {
                                    Log.d("LINHA", "${line.text} - ${line.confidence}")
                                    val ocrConfidence =
                                        sharedPreference.getFloat("ocrconfidence", 0.95f)
                                    if (line.confidence >= ocrConfidence)
                                        placaTexto += line.text
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

                                    val veiculoBitmap: Bitmap = bitmap.copy(bitmap.config, true)
                                    sendPlate(placaNormalizada, confidence, placa, veiculoBitmap, 1)
                                }
                            } else if (!isPost) {
                                isPost = true
                                val veiculoBitmap: Bitmap = bitmap.copy(bitmap.config, true)
                                val placaBitmap: Bitmap = placa.copy(placa.config, true)
                                val cameraId = sharedPreference.getLong("camera", 0)
                                val veiculoInput = Veiculo()
                                veiculoInput.fiscalizacaoId = CameraUSBActivity.fiscalizacaoId
                                veiculoInput.cameraId = cameraId
                                veiculoInput.foto1 = Utilities.bitmapToBase64(placaBitmap)
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

                                                        placas.add(placaDTO)
                                                        sendPlate(
                                                            veiculo.placa!!,
                                                            confidence,
                                                            placaBitmap,
                                                            veiculoBitmap,
                                                            2
                                                        )
                                                        isPost = false
                                                    } else
                                                        isPost = false
                                                } else
                                                    isPost = false
                                            } else {
                                                isPost = false
                                                val error = Utilities.analiseException(
                                                    response.code(), response.raw().toString(),
                                                    if (response.errorBody() != null) response.errorBody()!!
                                                        .string() else null,
                                                    requireContext()
                                                )
                                                Log.e("ERRO POST", "$error")
                                            }
                                        }

                                        override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                                            isPost = false
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