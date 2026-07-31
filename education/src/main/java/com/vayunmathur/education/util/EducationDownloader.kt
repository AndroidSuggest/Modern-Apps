package com.vayunmathur.education.util

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** NewPipe downloader backed by the shared [NetworkClient]. Mirrors :youpipe. */
class EducationDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val response = NetworkClient.performRequest(
            url = request.url,
            method = request.httpMethod,
            headers = request.headers,
            body = request.dataToSend,
        )
        Response(
            response.status,
            response.statusMessage,
            response.headers,
            response.body,
            response.url,
        )
    }
}
