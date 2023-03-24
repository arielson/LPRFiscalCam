package br.net.ari.lprfiscalcam

import android.os.Bundle
import android.view.WindowManager
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import br.net.ari.lprfiscalcam.core.PermissionUtils
import kotlin.properties.Delegates

class CameraUSBActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_usbactivity)

        PermissionUtils.requestPermission(this, PermissionUtils.cameraPermissions)

        val relativeLayoutMainContainer = findViewById<RelativeLayout>(R.id.relativeLayoutMainContainer)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, relativeLayoutMainContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val fragment : CameraUSBFragment = CameraUSBFragment.newInstance(fiscalizacaoId)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .add(R.id.fragment_container, fragment, "fragment_camera_u_s_b")
                .commit()
        }
    }

    companion object {
        var fiscalizacaoId by Delegates.notNull<Long>()
    }
}