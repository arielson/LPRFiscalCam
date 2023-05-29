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
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import br.net.ari.lprfiscalcam.core.Constants
import br.net.ari.lprfiscalcam.core.ObjectDetectorHelper
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.databinding.FragmentCameraUSBBinding
import br.net.ari.lprfiscalcam.dto.plateDTO
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
class CameraUSBFragment : CameraFragment(), IPreviewDataCallBack, ObjectDetectorHelper.DetectorListener  {
    private lateinit var mViewBinding: FragmentCameraUSBBinding

    private var camera : MultiCameraClient.ICamera? = null

    private lateinit var textViewTemperature : TextView
    private var intentfilter: IntentFilter? = null

    private lateinit var activity: CameraUSBActivity

    private lateinit var sharedPreference: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private lateinit var textViewPlateLog: TextView
    private lateinit var mediaPlayer: MediaPlayer

    private lateinit var recognizer: TextRecognizer
    private lateinit var bitmapBuffer: Bitmap

    private var placas = mutableListOf<plateDTO>()

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
            objectDetectorListener = this)
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
        return CameraRequest.Builder()
            .setPreviewWidth(1280) // camera preview width
            .setPreviewHeight(720) // camera preview height
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
            if (!::bitmapBuffer.isInitialized) {
                bitmapBuffer = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
                )
            }
            try {
                val bitmap = byteArrayToBitmap(data, width, height)
                if (bitmap != null)
                    detectObjects(bitmap)
            } catch (ex: java.lang.Exception) {
                Log.e("onPreviewData", "${ex.message}")
            }
        }
    }

    private fun byteArrayToBitmap(byteArray: ByteArray, width: Int, height: Int): Bitmap? {
        val yuvImage = YuvImage(byteArray, ImageFormat.NV21, width, height, null)
        val outputStream = ByteArrayOutputStream()
        val rect = Rect(0, 0, width, height)

        if (!yuvImage.compressToJpeg(rect, 100, outputStream)) {
            return null
        }

        val jpegByteArray = outputStream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)

        outputStream.close()

        return bitmap
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
        }
    }

    private fun sendPlate(
        placa: String,
        confiabilidade: Float,
        bitmapPlaca: Bitmap,
        bitmapVeiculo: Bitmap
    ) {
        Log.d("Placa:", placa)

        val cameraId = sharedPreference.getLong("camera", 0)
        val veiculoInput = Veiculo()
        veiculoInput.placa = placa
        veiculoInput.fiscalizacaoId = CameraUSBActivity.fiscalizacaoId
        veiculoInput.dispositivo = Utilities.getDeviceName()
        veiculoInput.cameraId = cameraId
        veiculoInput.latitude = activity.latitude
        veiculoInput.longitude = activity.longitude
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
                        val confPerc = confiabilidade * 100f
                        veiculo?.confianca = confPerc.toDouble()
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
                                    }>$placa</font>"
                                )
                            }

                            Thread {
                                val veiculoImage =
                                    Utilities.getScaledImage(bitmapVeiculo, 640, 480)
                                val veiculoImageBase64 =
                                    Base64.encodeToString(veiculoImage, Base64.NO_WRAP)
                                veiculo.foto2 = veiculoImageBase64

                                val byteArrayOutputStream = ByteArrayOutputStream()
                                bitmapPlaca.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    100,
                                    byteArrayOutputStream
                                )
                                val byteArray: ByteArray =
                                    byteArrayOutputStream.toByteArray()
                                val plateImageBase64 =
                                    Base64.encodeToString(byteArray, Base64.NO_WRAP)
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

    override fun onResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int,
        bitmap: Bitmap
    ) {
        if (results?.any() == true) {
            val result = results.first()
            val bndbox = result.boundingBox
            val confidence = result.categories.first().score * 100
            Log.d("Confiabilidade", "$confidence %")
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
                        val placaNormalizada = Utilities.normalizePlate(visionText.text)
                        Log.d("PLACA NORMALIZADA", placaNormalizada)

                        val isBrasil = Utilities.validateBrazilianLicensePlate(placaNormalizada)
                        if (isBrasil) {
                            // colocar exeções I -> 1; B -> 8.... sufixo e prefixo
                            Log.d("PLACA FINAL", placaNormalizada)
                            limparPlacas()
                            if (!placas.any { it.placa == placaNormalizada }) {
                                val placaDTO = plateDTO()
                                placaDTO.placa = placaNormalizada
                                placaDTO.data = LocalDateTime.now()

                                placas.add(placaDTO)
                                val veiculoBitmap: Bitmap = bitmap.copy(bitmap.config, true)
                                sendPlate(placaNormalizada, confidence, placa, veiculoBitmap)
                            }
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

    fun limparPlacas() {
        placas.removeAll { Utilities.getSecondsBetweenDates(it.data!!, LocalDateTime.now()) > Constants.SamePlateDelay }
    }
}