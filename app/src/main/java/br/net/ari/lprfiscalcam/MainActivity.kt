package br.net.ari.lprfiscalcam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.net.ari.lprfiscalcam.core.PermissionUtils
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.models.Camera
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseApp
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*


class MainActivity : AppCompatActivity() {
    private lateinit var buttonAcessarCodigo: Button
    private lateinit var textFieldCodigo: TextInputLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)
        PermissionUtils.requestPermission(this, PermissionUtils.locationPermissions)

        val sharedPreference = getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
        val editor = sharedPreference.edit()

        val activity: AppCompatActivity = this
        textFieldCodigo = findViewById(R.id.textFieldCodigo)
        val relativeLayoutLoading = findViewById<RelativeLayout>(R.id.relativeLayoutLoading)
        buttonAcessarCodigo = findViewById(R.id.buttonAcessarCodigo)
        val buttonAcessarQrCode = findViewById<Button>(R.id.buttonAcessarQrCode)

        buttonAcessarQrCode.setOnClickListener {
            readBarcode()
        }

        buttonAcessarCodigo.setOnClickListener {
            if (textFieldCodigo.editText.toString().isEmpty()) {
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Código")
                    .setMessage("Por favor digite o código da operação")
                    .setNegativeButton("Cancelar", null)
                    .create()
                dialog.show()

                return@setOnClickListener
            }
            if (!sharedPreference.contains("chave") || !sharedPreference.contains("uuid")) {
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
                        val uuid = Utilities.generateUUID()
                        Utilities.service().getCameraByChaveAnon(chave.uppercase(Locale.ROOT), uuid)
                            .enqueue(object : Callback<Camera?> {
                                override fun onResponse(
                                    call: Call<Camera?>,
                                    response: Response<Camera?>
                                ) {
                                    if (response.isSuccessful && response.body() != null) {
                                        val camera = response.body()!!
                                        camera.chaveLprFiscal = chave
                                        editor.putString("chave", camera.chaveLprFiscal)
                                        editor.putLong("camera", camera.id)
                                        editor.putString("uuid", uuid)
                                        editor.apply()

                                        buttonAcessarCodigo.performClick()
                                    } else {
                                        Utilities.showDialog(
                                            activity,
                                            Utilities.analiseException(
                                                response.code(), response.raw().toString(),
                                                if (response.errorBody() != null) response.errorBody()!!
                                                    .string() else null,
                                                applicationContext
                                            ),
                                            "Aviso"
                                        )
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
            } else {
                relativeLayoutLoading.visibility = View.VISIBLE
                val uuid = sharedPreference.getString("uuid", "")
                Utilities.service()
                    .getCameraByChaveAnon(sharedPreference.getString("chave", ""), uuid)
                    .enqueue(object : Callback<Camera?> {
                        override fun onResponse(
                            call: Call<Camera?>,
                            response: Response<Camera?>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                val camera = response.body()!!
                                editor.putLong("camera", camera.id)
                                camera.threshold?.let { it1 -> editor.putFloat("threshold", it1) }
                                camera.ocrConfidence?.let { it1 ->
                                    editor.putFloat(
                                        "ocrconfidence",
                                        it1
                                    )
                                }
                                camera.samePlateDelay?.let { it1 ->
                                    editor.putInt(
                                        "sameplatedelay",
                                        it1
                                    )
                                }
                                editor.apply()

                                Utilities.service().getFiscalizacao(
                                    textFieldCodigo.editText?.text.toString().uppercase(Locale.ROOT)
                                ).enqueue(object : Callback<Fiscalizacao?> {
                                    override fun onResponse(
                                        call: Call<Fiscalizacao?>,
                                        response: Response<Fiscalizacao?>
                                    ) {
                                        if (response.isSuccessful && response.body() != null) {
                                            val fiscalizacao = response.body()!!
                                            editor.putLong("fiscalizacao", fiscalizacao.id)
                                            editor.putString("codigo", fiscalizacao.codigo)
                                            editor.apply()
                                            Utilities.token = fiscalizacao.token

                                            val usbManager =
                                                getSystemService(Context.USB_SERVICE) as UsbManager
                                            if (usbManager.deviceList.isEmpty()) {
                                                CameraActivity.fiscalizacao = fiscalizacao
                                                CameraActivity.binData = 0
                                                val intent =
                                                    Intent(activity, CameraActivity::class.java)
                                                startActivity(intent)
                                            } else {
                                                CameraUSBActivity.fiscalizacaoId = fiscalizacao.id
                                                val intent =
                                                    Intent(activity, CameraUSBActivity::class.java)
                                                startActivity(intent)
                                            }

                                            buttonAcessarCodigo.isEnabled = true
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
                                                "Aviso"
                                            )
                                            relativeLayoutLoading.visibility = View.GONE
                                        }
                                    }

                                    override fun onFailure(
                                        call: Call<Fiscalizacao?>,
                                        t: Throwable
                                    ) {
                                        t.printStackTrace()
                                        Toast.makeText(
                                            applicationContext,
                                            R.string.service_failure,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        relativeLayoutLoading.visibility = View.GONE
                                    }
                                })
                            } else {
                                relativeLayoutLoading.visibility = View.GONE
                                Utilities.showDialog(
                                    activity,
                                    Utilities.analiseException(
                                        response.code(), response.raw().toString(),
                                        if (response.errorBody() != null) response.errorBody()!!
                                            .string() else null,
                                        applicationContext
                                    ),
                                    "Aviso"
                                )
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
                            buttonAcessarCodigo.isEnabled = true
                        }
                    })
            }
        }

        if (sharedPreference.contains("codigo")) {
            textFieldCodigo.editText?.setText(sharedPreference.getString("codigo", ""))
        }
    }

    private val barcodeScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val barcodeRawValue = result.data?.getStringExtra(
                BarCodeScannerActivity.RESULT_BARCODE_RAW_VALUE
            ) ?: ""
            textFieldCodigo.editText?.setText(barcodeRawValue)
            buttonAcessarCodigo.performClick()
        }
    }

    private fun readBarcode() {
        val intent = Intent(this, BarCodeScannerActivity::class.java)
        barcodeScannerLauncher.launch(intent)
    }
}