package br.net.ari.lprfiscalcam.data

import br.net.ari.lprfiscalcam.enums.ImageFormat

data class ImagePOJO(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val src: ByteArray,
    val imageFormat: ImageFormat = ImageFormat.YUV
)