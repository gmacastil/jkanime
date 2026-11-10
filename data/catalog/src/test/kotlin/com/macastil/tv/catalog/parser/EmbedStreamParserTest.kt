package com.macastil.tv.catalog.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedStreamParserTest {

    private val activeStream =
        "https://nika.playmudos.com/SVB3bDdkcVRHaFpEVVArOE9oUlI1Q256ZmNlckFyMEVKTnM0ZWNXcE4xQT0" +
            ".m3u8?st=WH1b3zc9cBb1Qt0q6aqpOA&e=1794280518"

    private val activeStreamBase64 =
        "aHR0cHM6Ly9uaWthLnBsYXltdWRvcy5jb20vU1ZCM2JEZGtjVlJIYUZwRVZWQXJPRTlvVWxJMVEyNTZabU5s" +
            "Y2tGeU1FVktUbk0wWldOWGNFNHhRVDAubTN1OD9zdD1XSDFiM3pjOWNCYjFRdDBxNmFxcE9BJmU9MTc5N" +
            "DI4MDUxOA=="

    private val staleStream =
        "https://otro-cdn.ejemplo.com/clip.m3u8?st=antiguo&e=1000000000"

    /** Réplica mínima del HTML real de `jkplayer/um` (Desu). */
    private val html = """
        <script>
          /** Dragurimu */
          /* if (p2pml.hlsjs.Engine.isSupported()) {
               window.dp = new DPlayer({
                   video: { url: '$staleStream', type: 'customHls' }
               });
          */

          window.dp = new DPlayer({
              container: document.getElementById('mse'),
              video: {
                  url: atob('$activeStreamBase64'),
                  type: 'customHls',
                  customType: {
                      customHls: function (video, url) {
                          var hls = new Hls();
                          hls.loadSource('$activeStream');
                          hls.attachMedia(video);
                      }
                  }
              }
          });
        </script>
    """.trimIndent()

    @Test
    fun `decodifica el m3u8 del bloque activo en base64`() {
        val candidates = EmbedStreamParser.candidates(html)
        assertTrue("no se encontró el stream: $candidates", candidates.contains(activeStream))
    }

    @Test
    fun `ordena primero el bloque activo sobre el comentado`() {
        val candidates = EmbedStreamParser.candidates(html)
        assertEquals(
            "el candidato activo debe ir primero",
            activeStream,
            candidates.first(),
        )
        assertTrue("faltan candidatos: $candidates", candidates.size >= 2)
        assertEquals(
            "el literal del bloque comentado va al final",
            staleStream,
            candidates.last(),
        )
    }

    @Test
    fun `devuelve vacio si el embed no expone stream`() {
        val noStream = """<script>var s = {"remote": "aHR0cHM6Ly9leGFtcGxlLmNvbQ=="};</script>"""
        assertTrue(EmbedStreamParser.candidates(noStream).isEmpty())
    }
}
