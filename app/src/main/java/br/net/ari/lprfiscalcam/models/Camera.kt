package br.net.ari.lprfiscalcam.models

class Camera {
    var id: Long = 0
    var chaveLprFiscal: String? = null
    var uuid: String? = null
    var threshold: Float? = null
    var ocrConfidence: Float? = null
    var samePlateDelay: Int? = null
}