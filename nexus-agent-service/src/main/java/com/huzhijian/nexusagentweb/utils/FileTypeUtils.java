package com.huzhijian.nexusagentweb.utils;



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
         "pdf","one","jpg","jpeg",
            "png",
            "gif",
            "webp"
    ));
    public static final Set<String> MS_OFFICE = new HashSet<>(Arrays.asList(
             "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx","one"
    ));
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg",
            "png",
            "gif",
            "webp","jpeg"
    ));
    /**
     * 判断文件是否为支持的文档类型
     *
     * @param extension 文件扩展名
     * @return 如果是支持的文档类型返回true，否则返回false
     */
    public static boolean isSupportedDocument(String extension) {
        return SUPPORTED_FILE_EXTENSIONS.contains(extension);
    }

    /**
     * 白名单的**可读文本**，用于给用户的失败提示（2026-10-07）。
     * <p>
     * 为什么要有它：拒绝一个文件时只说「不支持」没用，用户不知道该转成什么格式。
     * 而白名单散在两个 Set 里，各写一份字符串迟早会和判断逻辑漂移 ——
     * 这里从 {@link #SUPPORTED_FILE_EXTENSIONS} 直接渲染，保证「提示里说的」
     * 与「实际放行的」永远一致。
     * <p>
     * ⚠️ 刻意按固定顺序输出（{@code LinkedHashSet} 之外的稳定化处理见实现），
     * 避免 HashSet 的随机顺序让同一句话每次都不太一样。
     */
    public static String supportedExtensionsText() {
        return SUPPORTED_EXTENSIONS_TEXT;
    }

    /** 一次性渲染好的白名单文本，避免每次拼接 */
    private static final String SUPPORTED_EXTENSIONS_TEXT = renderExtensions();

    private static String renderExtensions() {
        java.util.List<String> ordered = new java.util.ArrayList<>(SUPPORTED_FILE_EXTENSIONS);
        java.util.Collections.sort(ordered);
        return String.join("、", ordered);
    }
    /**
     * 判断文件是否为支持的图片类型
     *
     * @param extension 文件扩展名
     * @return 如果是支持的文档类型返回true，否则返回false
     */
    public static boolean isSupportedImage(String extension) {
        // 方法1: 通过文件扩展名判断
        return SUPPORTED_IMAGE_EXTENSIONS.contains(extension);
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