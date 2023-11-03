package br.net.ari.lprfiscalcam

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@androidx.camera.core.ExperimentalGetImage
class BarCodeAnalyzer(val onSuccess: (List<Barcode>) -> Unit): ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {

        val mediaImage = imageProxy.image ?: return

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        val barcodeScannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_ALL_FORMATS
            )
            .build()

        val barcodeScanner = BarcodeScanning.getClient(barcodeScannerOptions)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                onSuccess(barcodes)
            }

        imageProxy.close()
    }
}