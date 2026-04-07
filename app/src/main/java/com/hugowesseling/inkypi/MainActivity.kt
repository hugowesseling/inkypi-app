package com.hugowesseling.inkypi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.hugowesseling.inkypi.ui.theme.InkyPiTheme
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
// ... existing imports ...
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.jcraft.jsch.ChannelSftp
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private val SSH_HOST = "192.168.1.130"
    private val SSH_USER = "inky"
    private val SSH_PASS = "impression"
    private val REMOTE_PATH = "/home/inky/images/"
    private val THUMBS_PATH = "/home/inky/images/thumbs/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var statusMessage by mutableStateOf("InkyPi Gallery")
        var isUploading by mutableStateOf(false)
        var uploadProgress by mutableStateOf(0f)

        // State for our gallery images (stored as ByteArrays for simplicity)
        val thumbList = mutableStateListOf<ByteArray>()

        val action = intent?.action
        val type = intent?.type

        // 1. Handle "Share" Action (Existing logic)
        if (type?.startsWith("image/") == true && (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE)) {
            handleShareIntent(intent, { isUploading = it }, { statusMessage = it }, { uploadProgress = it })
        } else {
            // 2. Handle "Normal" Start: Download Thumbs
            lifecycleScope.launch {
                statusMessage = "Loading Gallery..."
                val thumbs = fetchThumbsFromSsh()
                thumbList.addAll(thumbs)
                statusMessage = if (thumbs.isEmpty()) "No images found." else "Gallery (${thumbs.size})"
            }
        }

        setContent {
            InkyPiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        // Header Status
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = statusMessage)
                                if (isUploading) {
                                    LinearProgressIndicator(
                                        progress = { uploadProgress },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    )
                                }
                            }
                        }

                        // 3. The Grid View (5 images wide)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(thumbList) { imageData ->
                                AsyncImage(
                                    model = imageData,
                                    contentDescription = null,
                                    modifier = Modifier.height(100.dp).fillMaxWidth(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    private fun getFileSize(uri: Uri): Long {
        val cursor = contentResolver.query(uri, null, null, null, null)
        val sizeIndex = cursor?.getColumnIndex(OpenableColumns.SIZE)
        cursor?.moveToFirst()
        val size = sizeIndex?.let { cursor.getLong(it) } ?: 0L
        cursor?.close()
        return size
    }
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        // Fallback if everything else fails
        return result ?: "upload_${System.currentTimeMillis()}.jpg"
    }

    private suspend fun uploadImageViaSsh(
        uri: Uri,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val fileName = getFileName(uri)
            val fileSize = getFileSize(uri)

            if (inputStream == null) return@withContext false

            val jsch = JSch()
            session = jsch.getSession(SSH_USER, SSH_HOST, 22)
            session.setPassword(SSH_PASS)

            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            session.connect(10000)

            val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            channel.connect()

            // Create the progress monitor
            val monitor = object : com.jcraft.jsch.SftpProgressMonitor {
                private var transferred: Long = 0
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}

                override fun count(count: Long): Boolean {
                    transferred += count
                    if (fileSize > 0) {
                        onProgress(transferred.toFloat() / fileSize.toFloat())
                    }
                    return true
                }

                override fun end() { onProgress(1f) }
            }

            // Pass the monitor to the put method
            channel.put(inputStream, REMOTE_PATH + fileName, monitor)

            channel.disconnect()
            true
        } catch (e: Exception) {
            Log.e("SSH_UPLOAD", "Error: ${e.message}")
            false
        } finally {
            session?.disconnect()
        }
    }

    private suspend fun fetchThumbsFromSsh(): List<ByteArray> = withContext(Dispatchers.IO) {
        val images = mutableListOf<ByteArray>()
        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(SSH_USER, SSH_HOST, 22)
            session.setPassword(SSH_PASS)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            // List files in the thumbs directory
            val files = channel.ls(THUMBS_PATH)
            files.forEach {
                val entry = it as ChannelSftp.LsEntry
                if (!entry.attrs.isDir) {
                    val out = ByteArrayOutputStream()
                    channel.get(THUMBS_PATH + entry.filename, out)
                    images.add(out.toByteArray())
                }
            }
            channel.disconnect()
        } catch (e: Exception) {
            Log.e("SSH_GALLERY", "Error: ${e.message}")
        } finally {
            session?.disconnect()
        }
        return@withContext images
    }

    // Extracted share logic for cleanliness
    private fun handleShareIntent(intent: Intent, setUploading: (Boolean) -> Unit, setStatus: (String) -> Unit, setProgress: (Float) -> Unit) {
        val urisToUpload = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { urisToUpload.add(it) }
        } else {
            val uris = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            uris?.let { urisToUpload.addAll(it) }
        }

        lifecycleScope.launch {
            setUploading(true)
            urisToUpload.forEachIndexed { i, uri ->
                setStatus("Uploading ${i+1}/${urisToUpload.size}")
                uploadImageViaSsh(uri) { setProgress(it) }
            }
            setUploading(false)
            setStatus("Upload Complete")
        }
    }
}