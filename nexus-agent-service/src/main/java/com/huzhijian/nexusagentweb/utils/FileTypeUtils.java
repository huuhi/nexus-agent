package com.huzhijian.nexusagentweb.utils;



import org.apache.tika.metadata.Office;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件类型工具类
 * 用于判断上传的文件是否为文档类型，可被TikaDocumentReader处理
 */
public class FileTypeUtils {

    // 支持的文件扩展名列表
    private static final Set<String> SUPPORTED_FILE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "txt", "md", "markdown", "html", "htm", "csv",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx",
         "pdf","one"
    ));
    public static final Set<String> MS_OFFICE = new HashSet<>(Arrays.asList(
             "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx","one"
    ));
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg",
            "png",
            "gif",
            "webp"
    ));
    /**
     * 判断文件是否为支持的文档类型
     * 
     * @param originalFilename 文件扩展名
     * @return 如果是支持的文档类型返回true，否则返回false
     */
    public static boolean isSupportedDocument(String extension) {
        return SUPPORTED_FILE_EXTENSIONS.contains(extension);
    }
    /**
     * 判断文件是否为支持的图片类型
     *
     * @param originalFilename 文件扩展名
     * @return 如果是支持的文档类型返回true，否则返回false
     */
    public static boolean isSupportedImage(String originalFilename) {
        // 方法1: 通过文件扩展名判断
        if (originalFilename != null) {
            String extension = getFileExtension(originalFilename).toLowerCase();
            return SUPPORTED_IMAGE_EXTENSIONS.contains(extension);
        }
        return false;
    }
    
    /**
     * 获取文件扩展名
     * 
     * @param filename 文件名
     * @return 文件扩展名，格式为".ext"
     */
    public static String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.')).toLowerCase().replace(".","");
    }
}