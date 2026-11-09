package com.vidlab.app.data.remote

import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavClient(
    private val baseUrl: String = "http://127.0.0.1:8080",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    suspend fun listDirectory(path: String): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val fullUrl = "$baseUrl${encodePath(normalizedPath)}"

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

    suspend fun moveFile(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val srcNormalized = if (sourcePath.startsWith("/")) sourcePath else "/$sourcePath"
            val dstNormalized = if (destPath.startsWith("/")) destPath else "/$destPath"

            val srcUrl = "$baseUrl${encodePath(srcNormalized)}"
            val dstUrl = "$baseUrl${encodePath(dstNormalized)}"

            val request = Request.Builder()
                .url(srcUrl)
                .method("MOVE", null)
                .header("Destination", dstUrl)
                .header("Overwrite", "T")
                .build()

            val resp = client.newCall(request).execute()
            val code = resp.code
            val msg = resp.message
            val errBody = resp.body?.string() ?: ""
            resp.close()

            if (code !in 200..299) {
                throw Exception("WebDAV MOVE failed (HTTP $code $msg): $errBody")
            }
            Unit
        }
    }

    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
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
