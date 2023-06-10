package br.net.ari.lprfiscalcam.core

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.gms.vision.TfLiteVision
import org.tensorflow.lite.task.gms.vision.detector.Detection
import org.tensorflow.lite.task.gms.vision.detector.ObjectDetector
import java.io.File


class ObjectDetectorHelper (
    private var numThreads: Int = -1,
    private var maxResults: Int = 1,
    val context: Context,
    val objectDetectorListener: DetectorListener
) {

    private val tag = "ObjectDetectionHelper"

    private val binData: Int = 0

    private var objectDetector: ObjectDetector? = null
    private var gpuSupported = false

    init {
        TfLiteGpu.isGpuDelegateAvailable(context).onSuccessTask { gpuAvailable: Boolean ->
            val optionsBuilder =
                TfLiteInitializationOptions.builder()
            if (gpuAvailable) {
                optionsBuilder.setEnableGpuDelegateSupport(true)
                gpuSupported = true
            }
            TfLiteVision.initialize(context, optionsBuilder.build())
        }.addOnSuccessListener {
            objectDetectorListener.onInitialized()
        }.addOnFailureListener{
            objectDetectorListener.onError("TfLiteVision failed to initialize: "
                    + it.message)
        }
    }

    fun setupObjectDetector() {
        if (!TfLiteVision.isInitialized()) {
            Log.e(tag, "setupObjectDetector: TfLiteVision is not initialized yet")
            return
        }

        val sharedPreference = context.getSharedPreferences("lprfiscalcam", Context.MODE_PRIVATE)
        val threshold = sharedPreference.getFloat("threshold", 0.90f)

        val optionsBuilder =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)

        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)
        if (gpuSupported) {
            baseOptionsBuilder.useGpu()
        }

        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        try {
            var pass = ""
            var model = ""

            if (binData == 0) {
                pass = Constants.k0
                model = Constants.b0
            } else if (binData == 1) {
                pass = Constants.k1
                model = Constants.b1
            }

            val modelFile =  Utilities.getFileFromAssets(context, Utilities.getSimples(model))
            val modelTemp = Utilities.getSimples(Constants.t)
            val selvagem = Selvagem()
            val modelTempPath = "${modelFile.parent}/$modelTemp"

            selvagem.dsimples(modelFile.absolutePath, modelTempPath, pass)
            val modelTempFile = File(modelTempPath)

            objectDetector =
                ObjectDetector.createFromFileAndOptions(modelTempFile, optionsBuilder.build())
            modelTempFile.delete()
//            objectDetector =
//                ObjectDetector.createFromFileAndOptions(context, "lpr-ef0.tflite", optionsBuilder.build())
        } catch (e: Exception) {
            objectDetectorListener.onError(
                "Object detector failed to initialize. See error logs for details"
            )
            Log.e(tag, "TFLite failed to load model with error: " + e.message)
        }
    }

    fun detect(image: Bitmap, imageRotation: Int) {
        if (!TfLiteVision.isInitialized()) {
            Log.e(tag, "detect: TfLiteVision is not initialized yet")
            return
        }

        if (objectDetector == null) {
            setupObjectDetector()
        }

        var inferenceTime = SystemClock.uptimeMillis()

        val imageProcessor = ImageProcessor.Builder().add(Rot90Op(-imageRotation / 90)).build()

        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        objectDetectorListener.onResults(
            results,
            inferenceTime,
            tensorImage.height,
            tensorImage.width,
            tensorImage.bitmap)
    }

    interface DetectorListener {
        fun onInitialized()
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int,
            bitmap: Bitmap
        )
    }
}

