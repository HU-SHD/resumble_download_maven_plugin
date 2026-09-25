package org.stone.maven.plugin;

import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ResumableDownloader {
    private final Log log;
    private final int connectTimeout;
    private final int readTimeout;
    private final boolean forceResume;

    private static final int BUFFER_SIZE = 8192;
    private static final long LOCK_TIMEOUT_SECONDS = 30;

    public ResumableDownloader(Log log,int connectTimeout, int readTimeout, boolean forceResume){
        this.log = log;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.forceResume = forceResume;
    }

    /**
     * 开始下载
     *
     * @param uri
     * @param partFile
     * @param targetFile
     */
    public void download(URI uri, Path partFile, Path targetFile) throws IOException{
        try(RandomAccessFile lockRaf = new RandomAccessFile(partFile.toFile(), "rw");
            FileChannel lockChannel = lockRaf.getChannel()){
            FileLock lock = lockChannel.tryLock();
            if(lock == null){
                log.warn("无法获取文件锁，另一个进程可能正在下载此文件。等待中...");
                lock = acquireLockWithRetry(lockChannel);
            }
            if(lock == null){
                throw new IOException("获取文件锁超时，放弃下载");
            }

            try{
                doDownload(uri, partFile, targetFile, lockRaf);
            }finally {
                lock.release();;
            }
        }
        Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("文件已保存： " + targetFile);
    }
    private FileLock acquireLockWithRetry(FileChannel channel) throws IOException{
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(LOCK_TIMEOUT_SECONDS);
        while(System.currentTimeMillis() < deadline){
            try {
                Thread.sleep(2000);
                FileLock lock = channel.tryLock();
                if(lock != null){
                    log.info("成功获取文件锁");
                    return lock;
                }
            }catch (InterruptedException e){
                Thread.currentThread().interrupt();
                throw new IOException("等待文件锁被中断", e);
            }
        }
        return null;
    }

    /**
     * note：实际下载逻辑
     */
    private void doDownload(URI uri, Path partFile, Path targetFile, RandomAccessFile raf) throws IOException{
        long existingBytes = Files.exists(partFile) ? Files.size(partFile) : 0;
        if(existingBytes > 0){
            log.info("检测到已下载 " + existingBytes + " 字节，尝试从断点继续下载...");
        }

        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept-Encoding", "identity");

        if(existingBytes > 0){
            connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
        }
        int responseCode = connection.getResponseCode();
        log.debug("HTTP响应码: " + responseCode);

        try {
            if(responseCode == HttpURLConnection.HTTP_PARTIAL){
                log.info("服务器支持断点传续（206 Partial Content）");
                long contentLength = connection.getHeaderFieldLong("Content-Length", -1);
                long totalSize = existingBytes + contentLength;
                log.info("文件总大小：" + formatSize(totalSize) + "，已下载： " + formatSize(existingBytes));
                raf.seek(existingBytes);
                try(InputStream in = connection.getInputStream()){
                    raf.seek(existingBytes);
                    copyStream(in, raf, existingBytes, totalSize);
                }
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                if(existingBytes > 0 && forceResume){
                    throw new IOException(
                            "服务器不支持断点续传（返回200），但forceResume=true。"
                            + "请设置forceResume=false以允许全量下载。"
                    );
                }
                if(existingBytes > 0){
                    log.warn("服务器不支持断点续传，将从零开始全量下载");
                }
                long totalSize = connection.getContentLengthLong();
                log.info("文件大小：" + formatSize(totalSize));

                raf.setLength(0);
                raf.seek(0);
                try(InputStream in = connection.getInputStream()){
                    raf.setLength(0);
                    copyStream(in, raf, 0, totalSize);
                }
            }else{
                throw new IOException("HTTP 请求失败，响应码：" + responseCode);
            }
        }finally {
            connection.disconnect();
        }
    }

    /**
     * note：复制输入流到RandomAccessFile并记录进度
     */
    private void copyStream(
            InputStream in, RandomAccessFile raf, long starOffset, long totalSize) throws IOException{
        byte[] buffer = new byte[BUFFER_SIZE];
        long downloaded = starOffset;
        long lastLogTime = System.currentTimeMillis();
        int bytesRead;

        while((bytesRead = in.read(buffer)) != -1){
            raf.write(buffer, 0, bytesRead);
            downloaded += bytesRead;

            long now = System.currentTimeMillis();
            if(now - lastLogTime > 5000){
                if(totalSize > 0){
                    double percent = (double) downloaded / totalSize * 100 ;
                    log.info(String.format("下载进度：%.1f%%(%s / %s)"
                            , percent, formatSize(downloaded), formatSize(totalSize)));
                }else{
                    log.info("已下载： " + formatSize(downloaded));
                }
                lastLogTime = now;
            }
        }
        log.info("本次下载完成，已下载 " + formatSize(downloaded = starOffset) + " 字节");
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes){
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return String.format("%.1f KB",bytes / 1024.0);
        if(bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
