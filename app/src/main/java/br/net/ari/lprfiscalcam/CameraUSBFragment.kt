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
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.data.ImageInfoPOJO
import br.net.ari.lprfiscalcam.databinding.FragmentCameraUSBBinding
import br.net.ari.lprfiscalcam.enums.ImageFormat
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.ToastUtils
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
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

private const val ARG_PARAM1 = "param1"

/**
 * A simple [Fragment] subclass.
 * Use the [CameraUSBFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CameraUSBFragment : CameraFragment(), IPreviewDataCallBack  {
    private lateinit var mViewBinding: FragmentCameraUSBBinding

    private var manager : VaxtorAlprManager? = null
    private var initOcr : Long = -1

    private var camera : MultiCameraClient.ICamera? = null

    private lateinit var textViewTemperature : TextView
    private var intentfilter: IntentFilter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textViewTemperature = view.findViewById(R.id.textViewTemperature)

        val buttonExit = view.findViewById<Button>(R.id.buttonExit)
        buttonExit?.setOnClickListener {
            buttonExit.isEnabled = false
            camera?.removePreviewDataCallBack(this)
            Thread.sleep(200)
            manager?.finalize()
            Thread.sleep(200)
            manager?.shutdown()
            Thread.sleep(200)
            closeCamera()
            Thread.sleep(100)
            activity?.finish()
        }

        intentfilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        requireContext().registerReceiver(broadcastreceiver, intentfilter)

        initOcr = -1
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

        val textViewPlateLog = view?.findViewById<TextView>(R.id.textViewPlateLog)
        textViewPlateLog?.movementMethod = ScrollingMovementMethod()

        try {
            val ocrFile = File(activity?.cacheDir, "ocr_data.bin")
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

            initOcr = manager!!.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
            val teste = VaxtorLicensingManager.getC2V()
            if (initOcr < 0) {
                if (teste.isNotEmpty()) {
                    val ret = VaxtorLicensingManager.setC2V(teste)
                    if (ret < -1) {
                        VaxtorLicensingManager.registerLicense("981287e4-d75e-495e-bf71-dba9f0e31369") { bool, error ->
                            if (bool) {
                                initOcr = manager!!.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
                                Toast.makeText(requireContext(), "Success registering license", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Error registering license $error", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        initOcr = manager!!.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
                    }
                }
            }

            manager!!.eventPlateInfoCallback = { it ->
                val plate = it._plate_info?._plate_number_asciivar
                Log.d("Placa:", "$plate")

                Utilities.service().GetVeiculo(plate, CameraActivity.fiscalizacao.id)
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
                                    val fullText = "* $plate - ${veiculo.pendencia}\n\n${textViewPlateLog?.text}\n"
                                    textViewPlateLog?.text = fullText

                                    if (it._source_image != null ) {
                                        val imagePOJO = Utilities.mapImagePOJO(
                                            ImageInfoPOJO(
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
                                                            requireContext(), Utilities.analiseException(
                                                                response.code(),
                                                                response.raw().toString(),
                                                                if (response.errorBody() != null) response.errorBody()!!
                                                                    .string() else null,
                                                                requireContext()
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
                                                Toast.makeText(requireContext(), "Erro ao acessar servidor. Verifique internet.", Toast.LENGTH_LONG).show()
                                            }
                                        })
                                } else {
                                    val fullText = "* $plate - OK\n\n${textViewPlateLog?.text}\n"
                                    textViewPlateLog?.text = fullText
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

    private fun handleCameraClosed() {
        ToastUtils.show("camera closed success")
    }

    private fun handleCameraError(msg: String?) {
        ToastUtils.show("camera opened error: $msg")
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1024) // camera preview width
            .setPreviewHeight(768) // camera preview height
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
        Log.d("onPreviewData", "Size: ${data?.size} - Width: $width - Height: $height - Format: ${format.name}")
        val imagePOJO = Utilities.mapImagePOJO(ImageInfoPOJO(
            _format = ImageFormat.NV12.code.toLong(),
            _height = height.toLong(),
            _width = width.toLong(),
            _image = data,
            _size = data?.size?.toLong()
        ))

        val ocrId = manager?.ocrId ?: -111
        if (ocrId < 0) throw Exception("Can't recognize Image. Ocr init code  = $ocrId")

        if (imagePOJO != null) {
            manager?.ocrFindPlatesYUV(
                OcrFindPlatesImageArgs(
                    id = ocrId,
                    src_image = imagePOJO.src,
                    image_width = imagePOJO.width.toLong(),
                    image_height = imagePOJO.height.toLong()
                )
            )
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
}