package com.huzhijian.nexusagentweb.utils;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.domain.SysFile;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.huzhijian.nexusagentweb.utils.FileTypeUtils.MS_OFFICE;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明:
 */
@Component
@Slf4j
public class FileUtils {
    private final AliOssUtil aliOssUtil;

    public FileUtils(AliOssUtil aliOssUtil) {
        this.aliOssUtil = aliOssUtil;
    }


    public  Document getDocument(SysFile file) throws ClientException, IOException {
        Path path=null;
        try {
            path  = Files.createTempFile("upload_", "-" + file.getFileName());
            String key = extractOssKeyFromUrl(file.getFileUrl());
            byte[] fileContent = aliOssUtil.downloadDocument(key);
            log.debug("key:{}",key);
            Files.write(path,fileContent);
            DocumentParser parser;
            if (file.getExtension().equals("pdf")){
                parser=new ApachePdfBoxDocumentParser();
            }else if(MS_OFFICE.contains(file.getExtension())){
                parser=new ApachePoiDocumentParser();
            }else{
                parser=new ApacheTikaDocumentParser();
            }
            return FileSystemDocumentLoader.loadDocument(path, parser);
        } finally {
            if (path!=null){
                Files.deleteIfExists(path);
            }
        }
    }

    private String extractOssKeyFromUrl(String ossUrl) {
        try {
            URL url = new URL(ossUrl);
            String path = url.getPath();
            // 移除开头的斜杠
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        } catch (MalformedURLException e) {
            // 如果不是有效的URL，假设它已经是对象键
            log.warn("OSS URL格式不正确，使用原始值: {}", ossUrl);
            return ossUrl;
        }
    }
}
