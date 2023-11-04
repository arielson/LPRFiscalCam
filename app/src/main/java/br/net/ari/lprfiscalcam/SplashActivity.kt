package br.net.ari.lprfiscalcam

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import br.net.ari.lprfiscalcam.core.Utilities

//import br.net.ari.lprfiscalcam.core.Selvagem
//import br.net.ari.lprfiscalcam.core.Utilities

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        //Gerar arquivo simples
//        val lprFile = Utilities.getFileFromAssets(this, "lpr-ef0.tflite")
//        val lprFileCrypt = "${lprFile.parent}/lpr.data"
//        val selvagem = Selvagem()
//        selvagem.simples(lprFile.absolutePath, lprFileCrypt, "senha1237")

        Utilities.appVersion = getAppVersionName()
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 2000)
    }

    fun getAppVersionName(): String {
        try {
            val packageManager = packageManager
            val packageInfo: PackageInfo = packageManager.getPackageInfo(packageName, 0)
            return packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return ""
    }
}