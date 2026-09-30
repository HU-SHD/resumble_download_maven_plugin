package org.stone.maven.plugin;

import com.sun.net.httpserver.HttpServer;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ResumableDownloaderTest {

    private static HttpServer server;
    private static byte[] testData;
    private Path tempDir;

    @BeforeAll
    static void startServer() throws IOException {
        testData = new byte[1024 * 1024];
        new Random(42).nextBytes(testData);

        server = HttpServer.create(new InetSocketAddress(0), 0);

        server.createContext("/file.bin", exchange -> {
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                long start = Long.parseLong(
                        rangeHeader.substring(6, rangeHeader.length() - 1));
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (testData.length - 1)
                                + "/" + testData.length);
                exchange.sendResponseHeaders(206, testData.length - start);
                exchange.getResponseBody().write(testData, (int) start,
                        testData.length - (int) start);
            } else {
                exchange.sendResponseHeaders(200, testData.length);
                exchange.getResponseBody().write(testData);
            }
            exchange.close();
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("resumble-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "全量下载,0",
            "断点续传（模拟已下载512KB）,524288"
    })
    void testDownload(String casename, int existingBytes) throws Exception{
        Path partFile = tempDir.resolve("test.bin.part");
        Path targetFile = tempDir.resolve("test.bin");

        if(existingBytes > 0){
            try(FileOutputStream fos = new FileOutputStream(partFile.toFile())){
                fos.write(testData, 0, existingBytes);
            }
        }

        ResumableDownloader downloader = new ResumableDownloader(
                new SystemStreamLog(), 5000, 5000, false);
        downloader.download(
                new java.net.URI("http://localhost:" + server.getAddress().getPort() + "/file.bin"),
                partFile, targetFile
        );

        assertTrue(Files.exists(targetFile));
        assertArrayEquals(testData, Files.readAllBytes(targetFile));
    }
}