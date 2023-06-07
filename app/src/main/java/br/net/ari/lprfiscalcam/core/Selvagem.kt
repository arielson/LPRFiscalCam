package br.net.ari.lprfiscalcam.core

import android.util.Base64
import android.util.Log
import java.io.*
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec


class Selvagem {
    fun simples(inputFilePath: String, outputFilePath: String, encryptionKey: String) {
        try {
            val inputFile = File(inputFilePath)
            val inputData = inputFile.readBytes()

            val kk = generateEncryptionKey(encryptionKey, generateSalt(8))
            val base64String = Base64.encodeToString(kk, Base64.DEFAULT)
            Log.d("SIMPLES", base64String)

            val keySpec = SecretKeySpec(kk, "AES")

            val iv = ByteArray(16)
            val random = SecureRandom()
            random.nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

            val encryptedData = cipher.doFinal(inputData)

            val outputFile = File(outputFilePath)
            outputFile.writeBytes(iv + encryptedData)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dsimples(inputFilePath: String, outputFilePath: String, pass: String) {
        try {
            val inputFile = File(inputFilePath)
            val inputData = inputFile.readBytes()

            val iv = inputData.sliceArray(0..15)
            val encryptedData = inputData.sliceArray(16 until inputData.size)

            val kk = Base64.decode(pass, Base64.DEFAULT)
            val keySpec = SecretKeySpec(kk, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decryptedData = cipher.doFinal(encryptedData)

            val outputFile = File(outputFilePath)
            outputFile.writeBytes(decryptedData)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun generateEncryptionKey(password: String, salt: ByteArray): ByteArray {
        val iterationCount = 10000
        val keyLength = 256

        val keySpec: KeySpec = PBEKeySpec(password.toCharArray(), salt, iterationCount, keyLength)
        val secretKeyFactory: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val keyBytes: ByteArray = secretKeyFactory.generateSecret(keySpec).encoded

        return keyBytes
    }

    private fun generateSalt(length: Int): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(length)
        random.nextBytes(salt)
        return salt
    }
}