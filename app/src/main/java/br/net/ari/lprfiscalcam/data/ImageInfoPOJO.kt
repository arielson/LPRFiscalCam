package br.net.ari.lprfiscalcam.data

import java.io.Serializable

data class ImageInfoPOJO(
    val _format: Long?,
    val _height: Long?,
    val _image: ByteArray?,
    val _size: Long?,
    val _width: Long?
) : Serializable