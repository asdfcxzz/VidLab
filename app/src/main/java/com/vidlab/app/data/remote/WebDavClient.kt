package com.vidlab.app.data.remote

import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavClient(
    private val baseUrl: String = "http://127.0.0.1:8080",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun listDirectory(path: String): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val fullUrl = "$baseUrl$normalizedPath"

            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:resourcetype/>
                        <D:getcontentlength/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", RequestBody.create("application/xml".toMediaType(), propfindXml))
                .header("Depth", "1")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful && response.code != 207) {
                return@runCatching emptyList<MediaFile>()
            }

            val bodyString = response.body?.string() ?: return@runCatching emptyList<MediaFile>()
            parseMultiStatus(bodyString, normalizedPath)
        }
    }

    suspend fun makeDirectory(dirPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = if (dirPath.startsWith("/")) dirPath else "/$dirPath"
            val segments = normalized.trim('/').split('/')
            var current = ""

            for (seg in segments) {
                current += "/$seg"
                val url = "$baseUrl$current"
                val request = Request.Builder()
                    .url(url)
                    .method("MKCOL", null)
                    .build()
                client.newCall(request).execute()
            }
            Unit
        }
    }

    suspend fun moveFile(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val srcNormalized = if (sourcePath.startsWith("/")) sourcePath else "/$sourcePath"
            val dstNormalized = if (destPath.startsWith("/")) destPath else "/$destPath"

            val srcUrl = "$baseUrl$srcNormalized"
            val dstUrl = "$baseUrl$dstNormalized"

            val destFolder = dstNormalized.substringBeforeLast('/')
            if (destFolder.isNotBlank() && destFolder != "/") {
                makeDirectory(destFolder)
            }

            val request = Request.Builder()
                .url(srcUrl)
                .method("MOVE", null)
                .header("Destination", dstUrl)
                .header("Overwrite", "F")
                .build()

            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                throw Exception("Failed to move $sourcePath to $destPath (HTTP ${resp.code})")
            }
            Unit
        }
    }

    private fun parseMultiStatus(xml: String, requestedPath: String): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray()))
        val responses = doc.getElementsByTagNameNS("*", "response")

        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")

        for (i in 0 until responses.length) {
            val respElem = responses.item(i) as? Element ?: continue
            val hrefNode = respElem.getElementsByTagNameNS("*", "href").item(0) ?: continue
            val rawHref = hrefNode.textContent.trim()
            val decodedHref = URLDecoder.decode(rawHref, "UTF-8")

            val propstat = respElem.getElementsByTagNameNS("*", "propstat").item(0) as? Element
            val prop = propstat?.getElementsByTagNameNS("*", "prop")?.item(0) as? Element

            val isDirectory = prop?.getElementsByTagNameNS("*", "resourcetype")?.item(0)?.let {
                (it as Element).getElementsByTagNameNS("*", "collection").length > 0
            } ?: false

            val contentLength = prop?.getElementsByTagNameNS("*", "getcontentlength")?.item(0)?.textContent?.toLongOrNull() ?: 0L

            val pathTrimmed = decodedHref.trimEnd('/')
            val reqTrimmed = requestedPath.trimEnd('/')

            if (pathTrimmed.equals(reqTrimmed, ignoreCase = true) || pathTrimmed.isEmpty()) {
                continue
            }

            val name = pathTrimmed.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            val isVideo = ext in videoExtensions

            files.add(
                MediaFile(
                    name = name,
                    path = decodedHref,
                    isDirectory = isDirectory,
                    size = contentLength,
                    isVideo = isVideo
                )
            )
        }
        return files
    }
}
