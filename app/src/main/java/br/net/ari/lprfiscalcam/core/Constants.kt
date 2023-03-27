package br.net.ari.lprfiscalcam.core

class Constants {
    companion object {
        const val OCRComplexity = 2
        const val GrammarStrict = 1
        const val MinGlobalConfidence = 80
        const val MinCharacterConfidence = 70
        const val SamePlateDelay = 360
        const val SamePlateMaxCharsDistance = 1
        const val MaxSlopAngle = 30
        const val BackgroundMode = 1
        const val MinNumPlateCharacters = 7
        const val MaxNumPlateCharacters = 7
        const val MinCharHeight = 18
        const val MaxCharHeight = 42
        const val DetectMultilinePlate = 1
    }
}