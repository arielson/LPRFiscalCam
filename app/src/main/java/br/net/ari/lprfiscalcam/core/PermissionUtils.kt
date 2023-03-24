package br.net.ari.lprfiscalcam.core

import android.Manifest
import android.app.Activity
//import android.content.Context
//import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import androidx.fragment.app.Fragment

object PermissionUtils {
    private const val REQUEST_CODE = 123

    val cameraPermissions = listOf(Permission.CAMERA) //, Permission.WRITE, Permission.READ)

    val locationPermissions = listOf(Permission.COARSE_LOCATION, Permission.FINE_LOCATION)

//    fun requestPermission(
//        fragment: Fragment,
//        vararg permissions: Permission = Permission.values()
//    ) = requestPermission(fragment, permissions.toList())
//
//    fun requestPermission(
//        fragment: Fragment,
//        permissions: List<Permission> = Permission.values().toList()
//    ) = fragment.requestPermissions(
//        permissions.map { it.permissionName }.toTypedArray(),
//        REQUEST_CODE
//    )
//
//    fun requestPermission(
//        activity: Activity,
//        vararg permissions: Permission = Permission.values()
//    ) = requestPermission(activity, permissions.toList())

    fun requestPermission(
        activity: Activity,
        permissions: List<Permission> = Permission.values().toList()
    ) = ActivityCompat.requestPermissions(
        activity,
        permissions.map { it.permissionName }.toTypedArray(),
        REQUEST_CODE
    )

//    fun hasPermission(context: Context, vararg permissions: Permission = Permission.values()) =
//        hasPermission(context, permissions.toList())

//    fun hasPermission(
//        context: Context,
//        permissions: List<Permission> = Permission.values().toList()
//    ) = permissions.all {
//        val hasPermission = ContextCompat.checkSelfPermission(context, it.permissionName)
//        hasPermission == PackageManager.PERMISSION_GRANTED
//    }

    enum class Permission(val permissionName: String) {
        CAMERA(Manifest.permission.CAMERA),
        AUDIO(Manifest.permission.RECORD_AUDIO),
        WRITE(Manifest.permission.WRITE_EXTERNAL_STORAGE),
        READ(Manifest.permission.READ_EXTERNAL_STORAGE),
        FINE_LOCATION(Manifest.permission.ACCESS_FINE_LOCATION),
        COARSE_LOCATION(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}