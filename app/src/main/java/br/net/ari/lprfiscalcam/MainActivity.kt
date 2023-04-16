package br.net.ari.lprfiscalcam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences.Editor
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import br.net.ari.lprfiscalcam.adapters.FiscalizacaoAdapter
import br.net.ari.lprfiscalcam.core.Constants
import br.net.ari.lprfiscalcam.core.PermissionUtils
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.models.Camera
import br.net.ari.lprfiscalcam.models.CameraLog
import br.net.ari.lprfiscalcam.models.Cliente
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.vaxtor.alprlib.AlprOcr
import com.vaxtor.alprlib.VaxtorAlprManager
import com.vaxtor.alprlib.VaxtorLicensingManager
import com.vaxtor.alprlib.arguments.OcrInitialiseArgs
import com.vaxtor.alprlib.enums.OperMode
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {
    private lateinit var manager: VaxtorAlprManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)
        PermissionUtils.requestPermission(this, PermissionUtils.locationPermissions)

        val sharedPreference = getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
        val editor = sharedPreference.edit()

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

        val activity: AppCompatActivity = this
        val textFieldLogin = findViewById<TextInputLayout>(R.id.textFieldLogin)
        val textFieldSenha = findViewById<TextInputLayout>(R.id.textFieldSenha)
        val relativeLayoutLoading = findViewById<RelativeLayout>(R.id.relativeLayoutLoading)
        val spinnerCamera = findViewById<Spinner>(R.id.spinnerCamera)
        val linearLayoutLogin = findViewById<LinearLayout>(R.id.linearLayoutLogin)
        val linearLayoutCamera = findViewById<LinearLayout>(R.id.linearLayoutCamera)
        val buttonLogin = findViewById<Button>(R.id.buttonLogin)
        val buttonAcessar = findViewById<Button>(R.id.buttonAcessar)

        buttonLogin.setOnClickListener {
            val login = textFieldLogin.editText?.text.toString()
            val senhaLimpa = textFieldSenha.editText?.text.toString()
            if (login.isEmpty()) {
                Toast.makeText(applicationContext, "Digite o usuário", Toast.LENGTH_LONG).show()
                textFieldLogin.findFocus()
                return@setOnClickListener
            }
            if (senhaLimpa.isEmpty()) {
                Toast.makeText(applicationContext, "Digite a senha", Toast.LENGTH_LONG).show()
                textFieldSenha.findFocus()
                return@setOnClickListener
            }
            val senha = Utilities.sha256(senhaLimpa)
            relativeLayoutLoading.visibility = View.VISIBLE
            Utilities.service().getClienteByLoginAndSenha(login, senha)
                .enqueue(object : Callback<Cliente?> {
                    override fun onResponse(call: Call<Cliente?>, response: Response<Cliente?>) {
                        if (response.isSuccessful) {
                            Utilities.cliente = response.body()
                            Utilities.service().getFiscalizacoes()
                                .enqueue(object : Callback<List<Fiscalizacao>?> {
                                    override fun onResponse(
                                        call: Call<List<Fiscalizacao>?>,
                                        response: Response<List<Fiscalizacao>?>
                                    ) {
                                        relativeLayoutLoading.visibility = View.GONE
                                        if (response.isSuccessful) {

                                            editor.putString("login", login)
                                            editor.putString("senha", senhaLimpa)
                                            editor.apply()

                                            textFieldLogin.editText!!.setText("")
                                            textFieldSenha.editText!!.setText("")
                                            Toast.makeText(
                                                applicationContext,
                                                "Bem vindo(a) " + Utilities.cliente?.nome,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val fiscalizacoes =
                                                response.body() as List<Fiscalizacao>
                                            val adapter: ArrayAdapter<Fiscalizacao> =
                                                FiscalizacaoAdapter(
                                                    activity,
                                                    android.R.layout.simple_spinner_item,
                                                    fiscalizacoes
                                                )
                                            spinnerCamera.adapter = adapter
                                            linearLayoutLogin.visibility = View.GONE
                                            linearLayoutCamera.visibility = View.VISIBLE
                                            hideKeyboard(it)

                                            if (sharedPreference.contains("fiscalizacao")) {
                                                val fiscalizacaoId =
                                                    sharedPreference.getLong("fiscalizacao", 0)
                                                val items = retrieveAllItems(spinnerCamera)

                                                var index: Int? = null
                                                for (i in 0 until items.size) {
                                                    if (items[i].id == fiscalizacaoId) {
                                                        index = i
                                                        Log.d("Fiscalização", items[i].codigo!!)
                                                        break
                                                    }
                                                }
                                                if (index != null) {
                                                    spinnerCamera.setSelection(index)
                                                    buttonAcessar.performClick()
                                                }
                                            }
                                        } else {
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
                                        call: Call<List<Fiscalizacao>?>,
                                        t: Throwable
                                    ) {
                                        relativeLayoutLoading.visibility = View.GONE
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
                                })
                        } else {
                            relativeLayoutLoading.visibility = View.GONE
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

                    override fun onFailure(call: Call<Cliente?>, t: Throwable) {
                        relativeLayoutLoading.visibility = View.GONE
                        Toast.makeText(
                            applicationContext,
                            getString(R.string.service_failure),
                            Toast.LENGTH_LONG
                        ).show()
                        t.printStackTrace()
                    }
                })
        }

        buttonAcessar.setOnClickListener {
            if (!sharedPreference.contains("chave")) {
                val inputEditTextField = EditText(this)
                inputEditTextField.filters =
                    arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Chave")
                    .setMessage("Por favor digite a chave")
                    .setView(inputEditTextField)
                    .setPositiveButton("OK") { _, _ ->
                        relativeLayoutLoading.visibility = View.VISIBLE
                        val chave = inputEditTextField.text.toString()
                        Utilities.service().getCameraByChave(chave.uppercase(Locale.ROOT))
                            .enqueue(object : Callback<Camera?> {
                                override fun onResponse(
                                    call: Call<Camera?>,
                                    response: Response<Camera?>
                                ) {
                                    if (response.isSuccessful && response.body() != null) {
                                        val camera = response.body()!!
                                        camera.chaveLprFiscal = chave
                                        VaxtorLicensingManager.registerLicense(camera.chaveVaxtor!!) { bool, error ->
                                            if (bool) {
                                                sendData(
                                                    camera,
                                                    editor,
                                                    buttonAcessar,
                                                    relativeLayoutLoading
                                                )
                                            } else {
                                                val packageInfo = getPackageInfo()
                                                val verCode =
                                                    PackageInfoCompat.getLongVersionCode(packageInfo)
                                                        .toInt()

                                                if (camera.c2V?.isNotEmpty() == true) {
                                                    VaxtorLicensingManager.setC2V(camera.c2V!!)
                                                }

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
                                                                relativeLayoutLoading.visibility =
                                                                    View.GONE
                                                            }

                                                            override fun onFailure(
                                                                call: Call<Void?>,
                                                                t: Throwable
                                                            ) {
                                                                relativeLayoutLoading.visibility =
                                                                    View.GONE
                                                                t.printStackTrace()
                                                                Toast.makeText(
                                                                    applicationContext,
                                                                    R.string.service_failure,
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }
                                                        })
                                                    relativeLayoutLoading.visibility = View.GONE
                                                } else {
                                                    sendData(
                                                        camera,
                                                        editor,
                                                        buttonAcessar,
                                                        relativeLayoutLoading
                                                    )
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
                                        relativeLayoutLoading.visibility = View.GONE
                                    }
                                }

                                override fun onFailure(call: Call<Camera?>, t: Throwable) {
                                    t.printStackTrace()
                                    relativeLayoutLoading.visibility = View.GONE
                                    Toast.makeText(
                                        applicationContext,
                                        R.string.service_failure,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            })
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                dialog.show()

                return@setOnClickListener
            } else if (!sharedPreference.contains("camera")) {
                relativeLayoutLoading.visibility = View.VISIBLE
                Utilities.service().getCameraByChaveVaxtor(sharedPreference.getString("chave", ""))
                    .enqueue(object : Callback<Camera?> {
                        override fun onResponse(
                            call: Call<Camera?>,
                            response: Response<Camera?>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                val camera = response.body()!!
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
                                            } else {
                                                editor.putLong("camera", camera.id)
                                                editor.apply()

                                                buttonAcessar.performClick()
                                            }
                                            relativeLayoutLoading.visibility = View.GONE
                                        }

                                        override fun onFailure(
                                            call: Call<Camera?>,
                                            t: Throwable
                                        ) {
                                            relativeLayoutLoading.visibility = View.GONE
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
                                    applicationContext, Utilities.analiseException(
                                        response.code(), response.raw().toString(),
                                        if (response.errorBody() != null) response.errorBody()!!
                                            .string() else null,
                                        applicationContext
                                    ), Toast.LENGTH_LONG
                                ).show()
                            }
                            relativeLayoutLoading.visibility = View.GONE
                        }

                        override fun onFailure(call: Call<Camera?>, t: Throwable) {
                            t.printStackTrace()
                            relativeLayoutLoading.visibility = View.GONE
                            Toast.makeText(
                                applicationContext,
                                R.string.service_failure,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
            } else {
                buttonAcessar.isEnabled = false
                val fiscalizacao = spinnerCamera.selectedItem as Fiscalizacao
                editor.putLong("fiscalizacao", fiscalizacao.id)
                editor.apply()

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.deviceList.isEmpty()) {
                    CameraActivity.fiscalizacao = fiscalizacao
                    val intent = Intent(this, CameraActivity::class.java)
                    startActivity(intent)
                } else {
                    CameraUSBActivity.fiscalizacaoId = fiscalizacao.id
                    val intent = Intent(this, CameraUSBActivity::class.java)
                    startActivity(intent)
                }

                buttonAcessar.isEnabled = true
                finish()
            }
        }

        val buttonSair = findViewById<Button>(R.id.buttonSair)
        buttonSair.setOnClickListener {
            Utilities.cliente = null
            linearLayoutLogin.visibility = View.VISIBLE
            linearLayoutCamera.visibility = View.GONE
        }

        if (sharedPreference.contains("login") && sharedPreference.contains("senha")) {
            textFieldLogin.editText?.setText(sharedPreference.getString("login", ""))
            textFieldSenha.editText?.setText(sharedPreference.getString("senha", ""))
            buttonLogin.performClick()
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
        camera: Camera,
        editor: Editor,
        buttonAcessar: Button,
        relativeLayoutLoading: RelativeLayout
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
                    } else {
                        editor.putString("chave", camera.chaveVaxtor)
                        editor.putString("chave_lprfiscal", camera.chaveLprFiscal)
                        editor.putLong("camera", camera.id)
                        editor.apply()

                        buttonAcessar.performClick()
                    }
                    relativeLayoutLoading.visibility = View.GONE
                }

                override fun onFailure(
                    call: Call<Camera?>,
                    t: Throwable
                ) {
                    relativeLayoutLoading.visibility = View.GONE
                    t.printStackTrace()
                    Toast.makeText(applicationContext, R.string.service_failure, Toast.LENGTH_LONG)
                        .show()
                }
            })
    }

    fun retrieveAllItems(theSpinner: Spinner): MutableList<Fiscalizacao> {
        val adapter: Adapter = theSpinner.adapter
        val n = adapter.count
        val items: MutableList<Fiscalizacao> = ArrayList(n)
        for (i in 0 until n) {
            val item = adapter.getItem(i) as Fiscalizacao
            items.add(item)
        }
        return items
    }

    fun Context.hideKeyboard(view: View) {
        val inputMethodManager =
            getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
}