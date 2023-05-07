package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import br.net.ari.lprfiscalcam.core.Constants
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.data.ImageInfoPOJO
import br.net.ari.lprfiscalcam.databinding.FragmentCameraUSBBinding
import br.net.ari.lprfiscalcam.enums.ImageFormat
import br.net.ari.lprfiscalcam.models.Camera
import br.net.ari.lprfiscalcam.models.CameraLog
import br.net.ari.lprfiscalcam.models.Veiculo
import com.google.android.gms.location.*
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

    private lateinit var activity : CameraUSBActivity

    private lateinit var sharedPreference: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

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
            manager?.finalize()
            Thread.sleep(200)
            manager?.shutdown()
            Thread.sleep(200)
            closeCamera()
            Thread.sleep(100)
            activity.finish()
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

    @SuppressLint("DiscouragedApi")
    private fun handleCameraOpened() {
        ToastUtils.show("camera opened success")
        camera = getCurrentCamera()
        camera?.addPreviewDataCallBack(this)

        val textViewPlateLog = view?.findViewById<TextView>(R.id.textViewPlateLog)
        textViewPlateLog?.movementMethod = ScrollingMovementMethod()

        try {
            val ocrFile = File(activity.cacheDir, "ocr_data.bin")
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

            initOcr = manager!!.initOcr(ocrInitialiseArgs, FirebaseCrashlytics.getInstance())
            if (initOcr == -1L) {
                Toast.makeText(
                    activity,
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

                                            val initOcr = manager!!.initOcr(
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
                                                                    activity,
                                                                    "Ocorreu um erro! Log enviado com sucesso. Suporte irá verificar o problema.",
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            } else {
                                                                Toast.makeText(
                                                                    activity,
                                                                    Utilities.analiseException(
                                                                        response.code(),
                                                                        response.raw()
                                                                            .toString(),
                                                                        if (response.errorBody() != null) response.errorBody()!!
                                                                            .string() else null,
                                                                        activity
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
                                                                activity,
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
                                        activity, Utilities.analiseException(
                                            response.code(), response.raw().toString(),
                                            if (response.errorBody() != null) response.errorBody()!!
                                                .string() else null,
                                            activity
                                        ), Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            override fun onFailure(call: Call<Camera?>, t: Throwable) {
                                t.printStackTrace()
                                Toast.makeText(
                                    activity,
                                    R.string.service_failure,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                } else {
                    Toast.makeText(
                        activity,
                        "Chave local não encontrada",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val vezesTotal = 5
            var vezesTocada = 0
            val resID = resources.getIdentifier(
                "sirene", "raw",
                activity.packageName
            )

            val mediaPlayer = MediaPlayer.create(activity, resID)
            mediaPlayer.setOnCompletionListener {
                vezesTocada++
                if (vezesTocada < vezesTotal)
                    mediaPlayer.start()
                else
                    vezesTocada = 0
            }

            var logText = ""
            manager!!.eventPlateInfoCallback = { it ->
                val plate = it._plate_info?._plate_number_asciivar
                Log.d("Placa:", "$plate")
                val cameraId = sharedPreference.getLong("camera", 0)
                val veiculoInput = Veiculo()
                veiculoInput.placa = plate
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
                                veiculo?.placa = plate
                                val confPerc = it._plate_info?._plate_read_confidence?.div(100.0)
                                veiculo?.confianca = confPerc
                                veiculo?.dispositivo = Utilities.getDeviceName()
                                if (veiculo?.id!! > 0) {
                                    logText =
                                        "<font color=${Utilities.colorByStatus(veiculo.pendencia)}>* $plate - ${veiculo.pendencia}</font><br><br>${logText}\n"
                                    textViewPlateLog?.text =
                                        Html.fromHtml(logText, Html.FROM_HTML_MODE_LEGACY)

                                    if (veiculo.pendencia?.contains("Roubo_e_Furto") == true) {
                                        mediaPlayer.start()
                                        showDialog(activity, plate)
                                    }

                                    Thread {
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
                                                Rect(
                                                    plateBox[0],
                                                    plateBox[1],
                                                    plateBox[2],
                                                    plateBox[3]
                                                )
                                            veiculoBitmap =
                                                Utilities.bitmapFromImagePojo(imagePOJO)!!
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
                                                                activity,
                                                                Utilities.analiseException(
                                                                    response.code(),
                                                                    response.raw().toString(),
                                                                    if (response.errorBody() != null) response.errorBody()!!
                                                                        .string() else null,
                                                                    activity
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
                                                        activity,
                                                        "Erro ao acessar servidor. Verifique internet.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            })
                                    }.start()
                                } else {
                                    logText =
                                        "<font color=#5EBA7D>* $plate - OK</font><br><br>${logText}\n"
                                    textViewPlateLog?.text =
                                        Html.fromHtml(logText, Html.FROM_HTML_MODE_LEGACY)
                                }
                            } else {
                                Toast.makeText(
                                    activity,
                                    Utilities.analiseException(
                                        response.code(),
                                        response.raw().toString(),
                                        if (response.errorBody() != null) response.errorBody()!!
                                            .string() else null,
                                        activity
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onFailure(call: Call<Veiculo?>, t: Throwable) {
                            t.printStackTrace()
                            Toast.makeText(
                                activity,
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
        //Log.d("onPreviewData", "Size: ${data?.size} - Width: $width - Height: $height - Format: ${format.name}")
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

    private fun showDialog(activity: Activity, info: String?) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setContentView(R.layout.dialog_custom_layout)
        val textViewInfo = dialog.findViewById(R.id.textViewInfo) as TextView
        textViewInfo.text = info
        val buttonFechar = dialog.findViewById(R.id.buttonFechar) as Button
        buttonFechar.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
                            activity, Utilities.analiseException(
                                response.code(), response.raw().toString(),
                                if (response.errorBody() != null) response.errorBody()!!
                                    .string() else null,
                                activity
                            ), Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onFailure(
                    call: Call<Camera?>,
                    t: Throwable
                ) {
                    t.printStackTrace()
                    Toast.makeText(activity, R.string.service_failure, Toast.LENGTH_LONG)
                        .show()
                }
            })
    }

    @Suppress("DEPRECATION")
    fun getPackageInfo(): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
    }
}