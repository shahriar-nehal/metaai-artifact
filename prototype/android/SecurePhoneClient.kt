package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class SecurePhoneClient(private val context: Context, private val edgeNodeIp: String) {

    private val client = OkHttpClient()

    // 1. Cache to Secure Android Sandbox (Vulnerability Simulation)
    fun cacheImageLocally(imageData: ByteArray, sessionId: String): File {
        val cacheDir = context.filesDir
        val imageFile = File(cacheDir, "$sessionId.jpg")
        FileOutputStream(imageFile).use { fos -> fos.write(imageData) }
        return imageFile
    }

    // 2. Transmit to Edge Node
// Replace the existing processImageAtEdge function in SecurePhoneClient.kt
    fun processImageAtEdge(imageFile: File, sessionId: String, callback: (safeText: String, hasPii: Boolean, rawText: String) -> Unit) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .addFormDataPart("session_id", sessionId)
            .build()

        val request = Request.Builder()
            .url("http://$edgeNodeIp:8000/capture")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Error: Could not reach Edge Node at $edgeNodeIp", false, "")
            }
            override fun onResponse(call: Call, response: Response) {
                val data = response.body?.string() ?: ""
                try {
                    val json = org.json.JSONObject(data)
                    val safeText = json.optString("safe_text", "Analysis failed.")
                    val hasPii = json.optBoolean("has_pii", false)
                    val rawText = json.optString("raw_text", safeText)
                    callback(safeText, hasPii, rawText)
                } catch (e: Exception) {
                    callback("Error parsing edge response: $data", false, "")
                }
            }
        })
    }

    // NEW: 2b. Transmit Follow-up Chat to Edge Node
    fun sendChatMessage(prompt: String, sessionId: String, callback: (String) -> Unit) {
        val formBody = FormBody.Builder()
            .add("prompt", prompt)
            .add("session_id", sessionId)
            .build()

        val request = Request.Builder()
            .url("http://$edgeNodeIp:8000/chat")
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("Error: Could not reach text brain.")
            }
            override fun onResponse(call: Call, response: Response) {
                val data = response.body?.string() ?: ""
                try {
                    // Extract just the AI's text from the JSON response
                    val json = org.json.JSONObject(data)
                    callback(json.optString("response", "No response parsed."))
                } catch (e: Exception) {
                    callback(data)
                }
            }
        })
    }

    // 3. The Synchronized Kill Switch (Hardware File Wiping)
    fun overwriteAndDeleteFile(sessionId: String): Boolean {
        val imageFile = File(context.filesDir, "$sessionId.jpg")
        if (!imageFile.exists()) return false
        try {
            // "rws" forces synchronous overwrite to the physical flash storage blocks
            val raf = RandomAccessFile(imageFile, "rws")
            val length = raf.length()
            val zeroBytes = ByteArray(length.toInt()) { 0 }

            raf.seek(0)
            raf.write(zeroBytes)
            raf.close()

            // Delete the OS pointer after physical destruction
            return imageFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // 4. Drop Vector from Cloud DB
    fun triggerServerVectorDrop(sessionId: String) {
        val json = """{"session_id": "$sessionId"}"""
        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), json)
        val request = Request.Builder().url("http://$edgeNodeIp:8000/delete").post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }
}