package br.net.ari.lprfiscalcam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.net.ari.lprfiscalcam.adapters.FiscalizacaoAdapter
import br.net.ari.lprfiscalcam.core.PermissionUtils
import br.net.ari.lprfiscalcam.core.Utilities
import br.net.ari.lprfiscalcam.models.Cliente
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseApp
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)
        PermissionUtils.requestPermission(this, PermissionUtils.locationPermissions)

        val activity: AppCompatActivity = this
        val textFieldLogin = findViewById<TextInputLayout>(R.id.textFieldLogin)
        val textFieldSenha = findViewById<TextInputLayout>(R.id.textFieldSenha)
        val relativeLayoutLoading = findViewById<RelativeLayout>(R.id.relativeLayoutLoading)
        val spinnerCamera = findViewById<Spinner>(R.id.spinnerCamera)
        val linearLayoutLogin = findViewById<LinearLayout>(R.id.linearLayoutLogin)
        val linearLayoutCamera = findViewById<LinearLayout>(R.id.linearLayoutCamera)
        val buttonLogin = findViewById<Button>(R.id.buttonLogin)
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
            Utilities.service().GetClienteByLoginAndSenha(login, senha)
                .enqueue(object : Callback<Cliente?> {
                    override fun onResponse(call: Call<Cliente?>, response: Response<Cliente?>) {
                        if (response.isSuccessful) {
                            Utilities.cliente = response.body()
                            Utilities.service().GetFiscalizacoes()
                                .enqueue(object : Callback<List<Fiscalizacao>?> {
                                    override fun onResponse(
                                        call: Call<List<Fiscalizacao>?>,
                                        response: Response<List<Fiscalizacao>?>
                                    ) {
                                        relativeLayoutLoading.visibility = View.GONE
                                        if (response.isSuccessful) {
                                            textFieldLogin.editText!!.setText("")
                                            textFieldSenha.editText!!.setText("")
                                            Toast.makeText(
                                                applicationContext,
                                                "Bem vindo(a) " + Utilities.cliente?.nome,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val fiscalizacoes = response.body() as List<Fiscalizacao>
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
        val buttonAcessar = findViewById<Button>(R.id.buttonAcessar)
        buttonAcessar.setOnClickListener {
            val sharedPreference =  getSharedPreferences("lprfiscalcam",Context.MODE_PRIVATE)
            if (!sharedPreference.contains("chave")) {
                val inputEditTextField = EditText(this)
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Chave")
                    .setMessage("Por favor digite a chave")
                    .setView(inputEditTextField)
                    .setPositiveButton("OK") { _, _ ->
                        val editTextInput = inputEditTextField .text.toString()
                        val editor = sharedPreference.edit()
                        editor.putString("chave", editTextInput)
                        editor.apply()
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                dialog.show()

                return@setOnClickListener
            }

            buttonAcessar.isEnabled = false
            val fiscalizacao = spinnerCamera.selectedItem as Fiscalizacao

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
        }
        val buttonSair = findViewById<Button>(R.id.buttonSair)
        buttonSair.setOnClickListener {
            Utilities.cliente = null
            linearLayoutLogin.visibility = View.VISIBLE
            linearLayoutCamera.visibility = View.GONE
        }
    }

    fun Context.hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)

    }
}