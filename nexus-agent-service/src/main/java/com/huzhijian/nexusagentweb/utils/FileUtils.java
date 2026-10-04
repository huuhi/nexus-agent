package com.huzhijian.nexusagentweb.utils;

import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    private final OssUrlGuard ossUrlGuard;

    public FileUtils(AliOssUtil aliOssUtil, OssUrlGuard ossUrlGuard) {
        this.aliOssUtil = aliOssUtil;
        this.ossUrlGuard = ossUrlGuard;
    }

    /**
     * 下载并解析一个文档附件。
     * <p>
     * ⚠️ <b>2026-10-04 安全修复：必须传 {@code userId} 做归属校验。</b>
     * {@code fileUrl} 来自前端，本方法会用<b>服务端 OSS 凭证</b>按它下载，
     * 所以"URL 是用户填的"这件事必须配合"这个文件确实属于该用户"一起验证，
     * 否则任何登录用户都能读走别人的文件（详见 {@link OssUrlGuard} 的注释）。
     *
     * @param file   附件信息（fileUrl 必填、extension 用于选解析器）
     * @param userId 附件所有者；null 直接拒绝
     */
    public Document getDocument(SysFile file, Long userId) throws IOException {
        if (file == null || file.getFileUrl() == null || file.getFileUrl().isBlank()) {
            throw new ValidationException("附件地址为空");
        }
        if (userId == null) {
            throw new ValidationException("未登录，无法解析附件");
        }
        // ⚠️ 必须在下载之前校验：校验放在下载之后就等于没做
        ossUrlGuard.validateOwnedBy(file.getFileUrl(), userId, "解析附件");

        Path path = null;
        try {
            path = Files.createTempFile("upload_", "-" + file.getFileName());
            String key = extractOssKeyFromUrl(file.getFileUrl());
            byte[] fileContent = aliOssUtil.downloadDocument(key);
            log.debug("key:{}", key);
            Files.write(path, fileContent);
            DocumentParser parser;
            if (file.getExtension().equals("pdf")) {
                parser = new ApachePdfBoxDocumentParser();
            } else if (MS_OFFICE.contains(file.getExtension())) {
                parser = new ApachePoiDocumentParser();
            } else {
                parser = new ApacheTikaDocumentParser();
            }
            return FileSystemDocumentLoader.loadDocument(path, parser);
        } finally {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 从 OSS 访问 URL 反推 object key。
     * <p>
     * ⚠️ <b>2026-10-04：这里不再吞异常回退成"当它是 key"。</b>
     * 原实现在 URL 非法时 {@code catch} 掉 {@code MalformedURLException}
     * 然后 {@code return ossUrl} —— 意味着传一个根本不是 URL 的字符串，
     * 它会把这个字符串<b>原样当 object key</b> 去 OSS 查。配合
     * {@code ..} 之类的路径片段，攻击者可以尝试越出 {@code fileDir} 读到别的对象。
     * 现在：URL 不合法直接拒绝（归属校验里已先过了一道，这里是第二道）。
     */
    private String extractOssKeyFromUrl(String ossUrl) {
        String key = AliOssUtil.objectNameOf(ossUrl);
        if (key == null || key.isBlank()) {
            throw new ValidationException("附件地址里解析不出对象路径");
        }
        // 拒绝路径穿越：OSS 的 object key 里出现 ".." 没有合法业务场景
        if (key.contains("..")) {
            throw new ValidationException("附件地址包含非法路径片段");
        }
        return key;
    }
}
