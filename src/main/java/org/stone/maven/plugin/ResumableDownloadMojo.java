package org.stone.maven.plugin;


import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * 断点续传下载插件的核心mojo
 * note：在Maven生命周期中下载大文件，支持断点传续
 */
@Mojo(name = "download", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class ResumableDownloadMojo extends AbstractMojo {
    /**
     * note:下载的文件URL
     */
    @Parameter(property = "resumble.url", required = true)
    private String url;

    /**
     * note：文件保存路径，以项目的build目录为父目录
     */
    @Parameter(property ="resumble.outputDirectory", defaultValue = "${project.build.directory}/download")
    private File outputDirectory;

    /**
     * note：保存文件的文件名，如不指定则默认为url中的名称
     */
    @Parameter(property = "resumble.fileName")
    private String fileName;

    /**
     * note：期望的SHA-256校验和，指定后会在下载完成时自动校验
     */
    @Parameter(property = "resumble.sha256")
    private String expectedSha256;

    /**
     * note：若文件已存在且校验通过，可以选择跳过下载
     */
    @Parameter(property = "resumble.skipIfExists",defaultValue = "true")
    private boolean skipIfExists;

    /**
     * note：连接超时时间设置
     */
    @Parameter(property = "resumble.connectTimeout", defaultValue = "30000")
    private int connectTimeout;

    /**
     * note：读取超时时间设置
     */
    @Parameter(property = "resumble.readTimeout", defaultValue = "60000")
    private int readTimeout;

    /**
     * note：配置“是否强制使用断点传续”
     */
    @Parameter(property = "resumble.forceResume",defaultValue = "false")
    private boolean forceResume;

    private static final int BUFFERR_SIZE = 8192;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException{
        // 参数校验
        if(url == null || url.trim().isEmpty()){
            throw new MojoExecutionException("参数不能为空");
        }

        String resolvedFileName = resolveFileName();
        //用户实际想要保存的文件路径及断点传续时的临时文件路径
        Path targetFile = outputDirectory.toPath().resolve(resolvedFileName);
        Path partFile = outputDirectory.toPath().resolve(resolvedFileName + ".part");

        if(skipIfExists && Files.exists(targetFile)){
            if(expectedSha256 == null || verifyCheckSum(targetFile, expectedSha256)){
                getLog().info("文件已存在且校验通过，跳过下载" + targetFile);
                return;
            }else{
                getLog().warn("文件已存在但校验失败，将重新下载：" + targetFile);
                try{
                    Files.delete(targetFile);
                }catch(IOException e){
                    throw new MojoExecutionException("无法删除校验失败的文件",e);
                }
            }
        }

        //创建输出目录
        try{
            Files.createDirectories(outputDirectory.toPath());
        }catch (IOException e){
            throw new MojoExecutionException("无法创建输出目录" + outputDirectory, e);
        }

        //开始执行断点续传下载
        getLog().info("开始下载：" + url);
        getLog().info("目标文件：" + targetFile);

        try{
            ResumableDownloader resumableDownloader = new ResumableDownloader(
                    getLog(),connectTimeout, readTimeout, forceResume);
            resumableDownloader.download(new URI(url), partFile, targetFile);

            //校验
            if(expectedSha256 != null){
                if(!verifyCheckSum(targetFile, expectedSha256)){
                    throw new MojoExecutionException(
                            "SHA-256 校验失败！文件可能已损坏。期望：" + expectedSha256
                    );
                }
                getLog().info("SHA-256 校验通过");
            }

            getLog().info("下载完成：" + targetFile);

        }catch (IOException  | java.net.URISyntaxException e){
            throw  new MojoExecutionException("下载失败：" + url, e);
        }
    }

    /**
     * note：从url中解析文件名
     */
    private String resolveFileName(){
        if(fileName != null && !fileName.trim().isEmpty()){
            return fileName;
        }
        String path = url.substring(url.lastIndexOf("/" + 1));
        int queryIndex = path.indexOf("?");
        if(queryIndex > 0){
            path = path.substring(0,queryIndex);
        }
        if(path.isEmpty()){
            path = "downloaded-file";
        }
        return path;
    }
    /**
     * note：检验大文件的SHA-256哈希值，为避免大文件OOM采用流式处理
     */
    private boolean verifyCheckSum(Path file, String expectedSha256){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try(InputStream is = Files.newInputStream(file);
                DigestInputStream dis = new DigestInputStream(is, digest))
            {
                byte[] buffer = new byte[BUFFERR_SIZE];
                while(dis.read(buffer) != -1) {
                    //读取即更新摘要
                }
            }
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for(byte b : hashBytes){
                String hex = Integer.toHexString(0xff & b);
                if(hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String actualSha256 = hexString.toString();
            getLog().debug("实际 SHA-256" + actualSha256);
            return actualSha256.equalsIgnoreCase(expectedSha256);
        }catch (Exception e){
            getLog().warn("校验和计算失败" + e.getMessage());
            return false;
        }
    }

}
