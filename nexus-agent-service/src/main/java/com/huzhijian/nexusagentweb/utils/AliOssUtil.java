package com.huzhijian.nexusagentweb.utils;


import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.OSSObject;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AliOssProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Component
public class AliOssUtil {

    private final AliOssProperties aliOssProperties;

    public AliOssUtil(AliOssProperties aliOssProperties) {
        this.aliOssProperties = aliOssProperties;
    }

    /**
     * 取 OSS 凭证：**配置优先，环境变量兜底**。
     * <p>
     * ⚠️ <b>2026-10-04 修正：原先这里（以及下面的上传方法）catch / throws 的是
     * {@code com.aliyuncs.exceptions.ClientException}（aliyun-java-sdk-core 里的那个），
     * 而 OSS SDK 真正抛的是 {@code com.aliyun.oss.ClientException} 与
     * {@code com.aliyun.oss.OSSException} —— 两个完全不同的类、包名只差一点。</b>
     * 结果：凭证错、endpoint 不通、上传超时全部**没被捕获**，一路冒泡到兜底 handler 变成 500，
     * 日志里只有一句"系统内部错误"，很难定位。现在统一捕获正确的类型并转成业务异常。
     * <p>
     * 优先用 {@code spring.aliyun.access-key-id/secret}；没配才回退到 SDK 的
     * {@code EnvironmentVariableCredentialsProvider}（读 {@code OSS_ACCESS_KEY_ID} /
     * {@code OSS_ACCESS_KEY_SECRET} 两个环境变量）。
     * <p>
     * <b>为什么必须支持配置方式</b>：SDK 那个 provider 直接调 {@code System.getenv()}，
     * <b>完全绕开 Spring</b> —— 于是写在 yml / .env.properties 里的凭证它一律看不到，
     * 表现为「配置明明写了且正确，却报
     * {@code InvalidCredentialsException: Access key id should not be null or empty}」。
     * 这一半配置（endpoint / bucket / region）走 Spring、另一半（凭证）走环境变量，
     * 本身就自相矛盾，这里统一成"两者都认"。
     */
    private CredentialsProvider credentialsProvider() {
        String id = aliOssProperties.getAccessKeyId();
        String secret = aliOssProperties.getAccessKeySecret();
        if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
            return new DefaultCredentialProvider(id, secret);
        }
        log.warn("""
                spring.aliyun.access-key-id / access-key-secret 没配，回退到环境变量 \
                OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET。注意：环境变量不经过 Spring，\
                写在 yml 或 .env.properties 里是读不到的 —— 上传文件会报 \
                InvalidCredentialsException。建议显式配置这两项。""");
        try {
            return CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
        } catch (com.aliyuncs.exceptions.ClientException e) {
            // 这是唯一真的会抛 com.aliyuncs.exceptions.ClientException 的调用点：
            // 环境变量里压根没有 OSS_ACCESS_KEY_ID / SECRET。
            throw new ValidationException("OSS 凭证缺失：既没配 spring.aliyun.access-key-id/secret，"
                    + "环境变量 OSS_ACCESS_KEY_ID/SECRET 也没读到");
        }
    }

    /**
     * 统一的 OSS 客户端参数。
     * <p>
     * 🔴 <b>超时是必须显式设的</b>：不设时 SDK 用默认超时 + 默认重试，
     * 服务器出网受限或 endpoint 不可达时，一次 putObject 可以挂好几分钟 ——
     * 期间前端/网关先超时，表现就是 502 / 504，而后端日志里连一条错误都没有。
     * 这里收口成：连接 10s、读写 60s、最多重试 2 次，最坏约 3 分钟必然有结果。
     */
    private ClientBuilderConfiguration clientConfig() {
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(10_000);
        config.setSocketTimeout(60_000);
        config.setMaxErrorRetry(2);
        return config;
    }

    /** 把 OSS 的两种异常翻译成可读的业务异常（区分"网络/凭证"与"服务端拒绝"）。 */
    private ValidationException ossFailure(String action, com.aliyun.oss.ClientException e) {
        log.error("OSS {}失败（客户端/网络）：{}", action, e.getMessage());
        return new ValidationException(action + "失败（网络不通或凭证错误）：" + e.getMessage());
    }

    private ValidationException ossFailure(String action, com.aliyun.oss.OSSException e) {
        log.error("OSS {}失败（服务端返回 {}）：{}", action, e.getErrorCode(), e.getErrorMessage());
        return new ValidationException(action + "失败（OSS 返回 " + e.getErrorCode() + "）：" + e.getErrorMessage());
    }

    /**
     * 图片上传
     *
     * @param content 文件字节数组
     * @param originalFilename 原始文件名
     * @return 文件访问路径
     */
    public String uploadImage(byte[] content, String originalFilename) {
        String endpoint = aliOssProperties.getEndpoint();
        String bucketName = aliOssProperties.getBucketName();
        String region = aliOssProperties.getRegion();
        String dir=aliOssProperties.getImageDir();

        log.info("这里！！！  endpoint:{}, bucketName:{}, region:{}", endpoint, bucketName, region);


        // 凭证：配置优先、环境变量兜底（见 credentialsProvider() 的说明）
        CredentialsProvider credentialsProvider = credentialsProvider();

        // 填写Object完整路径，例如202406/1.png。Object完整路径中不能包含Bucket名称。
        //获取当前系统日期的字符串,格式为 yyyy/MM
        //生成一个新的不重复的文件名
        String newFileName = UUID.randomUUID() + "."+originalFilename;
        String objectName = dir + "/" + newFileName;
        log.info("objectName: {}", objectName);

        // 创建OSSClient实例。
        return getUrl(content, endpoint, bucketName, region, credentialsProvider, objectName);
    }
    
    /**
     * 文档上传
     *
     * @param content 文件字节数组
     * @param fileExtension 文件扩展名
     * @param userId 用户ID，用于创建用户专属文件夹
     * @return 文件访问路径
     */
    public String uploadDocument(byte[] content, String fileExtension, Long userId) {
        String endpoint = aliOssProperties.getEndpoint();
        String bucketName = aliOssProperties.getBucketName();
        String region = aliOssProperties.getRegion();
        String dir = aliOssProperties.getFileDir();

        log.info("文档上传: endpoint:{}, bucketName:{}, region:{}", endpoint, bucketName, region);

        CredentialsProvider credentialsProvider = credentialsProvider();

        // 生成一个新的不重复的文件名
        String newFileName = UUID.randomUUID() +"."+ fileExtension;
        // 构造包含用户ID的文件路径
        LocalDate today = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
        String formatted = today.format(formatter);
        String objectName =dir + "/user_" + userId +"/"+formatted+"/"+newFileName;
        log.info("文档上传路径: {}", objectName);

        // 创建OSSClient实例。
        return getUrl(content, endpoint, bucketName, region, credentialsProvider, objectName);
    }
    
    /**
     * 上传文件并返回访问URL
     *
     * @param content 文件字节数组
     * @param endpoint OSS端点
     * @param bucketName 存储桶名称
     * @param region 区域
     * @param credentialsProvider 凭证提供者
     * @param objectName 对象名称
     * @return 文件访问路径
     */
    private String getUrl(byte[] content, String endpoint, String bucketName, String region, CredentialsProvider credentialsProvider, String objectName) {
        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientConfig())
                .region(region)
                .build();

        long start = System.currentTimeMillis();
        try {
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(content));
        } catch (com.aliyun.oss.ClientException e) {
            throw ossFailure("文件上传", e);
        } catch (com.aliyun.oss.OSSException e) {
            throw ossFailure("文件上传", e);
        } finally {
            ossClient.shutdown();
            log.info("OSS 上传完成：objectName={}, {} bytes, 耗时 {}ms",
                    objectName, content.length, System.currentTimeMillis() - start);
        }

        return endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + objectName;
    }
    
    /**
     * 删除 OSS 对象（P2-10 产物删除）。
     * <p>
     * 只负责删对象，**不管数据库记录** —— 顺序由调用方决定：
     * 先删记录、再尽力删对象。理由：用户点"删除"的语义以记录为准，
     * 对象残留只是存储成本，不该让删除操作失败。
     * <p>
     * 对象不存在时 OSS 的 deleteObject 也是幂等成功的，不会抛异常。
     */
    public void deleteObject(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return;
        }
        String endpoint = aliOssProperties.getEndpoint();
        String bucketName = aliOssProperties.getBucketName();
        String region = aliOssProperties.getRegion();
        CredentialsProvider credentialsProvider = credentialsProvider();

        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientConfig())
                .region(region)
                .build();
        try {
            ossClient.deleteObject(bucketName, objectName);
            log.debug("已删除 OSS 对象：{}", objectName);
        } catch (com.aliyun.oss.ClientException e) {
            throw ossFailure("删除对象", e);
        } catch (com.aliyun.oss.OSSException e) {
            throw ossFailure("删除对象", e);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 按「文件访问 URL」尽力删除 OSS 对象（2026-10-05 抽出，供产物与用户文件共用）。
     * <p>
     * 与 {@link #deleteObject} 的区别：这里吃的是 {@code sys_file.file_url}，
     * 内部先反推 objectName；并且<b>吞掉所有异常只记 WARN</b> —— 调用方都是
     * 「先删数据库记录，再尽力清理对象」的顺序，此时数据库记录已经没了，
     * 对象残留只是存储成本，不该反过来让删除操作失败（用户只会以为没删掉）。
     *
     * @param fileUrl {@code sys_file.file_url}；为 null / 空 / 解析不出对象名时直接跳过
     * @return 是否真的删掉了对象（false = 跳过或失败）
     */
    public boolean deleteByUrl(String fileUrl) {
        String objectName = objectNameOf(fileUrl);
        if (objectName == null) {
            log.warn("无法从 URL 解析出 OSS 对象名，跳过对象删除：url={}", fileUrl);
            return false;
        }
        try {
            deleteObject(objectName);
            return true;
        } catch (Exception e) {
            log.warn("删除 OSS 对象失败（已忽略，数据库记录已删除）：object={} 原因={}",
                    objectName, e.getMessage());
            return false;
        }
    }

    /**
     * 从 OSS 访问 URL 反推 objectName（删除产物时需要）。
     * <p>
     * URL 形如 {@code https://{bucket}.{host}/{objectName}}。
     * ⚠️ objectName 里的中文/空格在**上传时被 percent-encoding 过**（见 Python 侧
     * {@code oss_utils.str_upload_file}），所以这里必须解码，否则拿到的对象名对不上、
     * 删不掉对象。
     *
     * @return objectName；URL 不含路径时返回 null
     */
    public static String objectNameOf(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return null;
        }
        int schemeEnd = fileUrl.indexOf("://");
        int hostStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int pathStart = fileUrl.indexOf('/', hostStart);
        if (pathStart < 0 || pathStart == fileUrl.length() - 1) {
            return null;
        }
        String encoded = fileUrl.substring(pathStart + 1);
        // 去掉可能存在的 query / fragment，只留对象路径
        int cut = encoded.indexOf('?');
        if (cut >= 0) {
            encoded = encoded.substring(0, cut);
        }
        cut = encoded.indexOf('#');
        if (cut >= 0) {
            encoded = encoded.substring(0, cut);
        }
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解码失败就用原串：多半也删不掉，但至少不让调用方抛异常
            return encoded;
        }
    }

    /**
     * 从OSS下载文件内容
     *
     * @param objectName OSS中的对象名称
     * @return 文件字节数组
     */
    public byte[] downloadDocument(String objectName) {
        String endpoint = aliOssProperties.getEndpoint();
        String bucketName = aliOssProperties.getBucketName();
        String region = aliOssProperties.getRegion();

        CredentialsProvider credentialsProvider = credentialsProvider();

        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientConfig())
                .region(region)
                .build();

//        ⚠️ OSSObject 是 Closeable：不关会漏底层 HTTP 连接（大文件下载尤其明显）
        try (OSSObject ossObject = ossClient.getObject(bucketName, objectName)) {
            return ossObject.getObjectContent().readAllBytes();
        } catch (IOException e) {
            log.error("下载文件失败: {}", e.getMessage());
            throw new ValidationException("读取 OSS 对象内容失败：" + e.getMessage());
        } catch (com.aliyun.oss.ClientException e) {
            throw ossFailure("文件下载", e);
        } catch (com.aliyun.oss.OSSException e) {
            throw ossFailure("文件下载", e);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 读取 OSS 对象的**头部若干字节**（2026-10-07 新增，用于产物魔数校验）。
     * <p>
     * 用 Range 请求只取前 N 字节，不下载整个对象 —— 产物可能几 MB，
     * 而校验只需要文件头（各格式的魔数都在前 16 字节内）。
     *
     * @param fileUrl {@code sys_file.file_url} 形式的访问 URL
     * @param n       要读的字节数
     * @return 实际读到的字节（对象比 n 小时就是对象全长）；读取失败返回 null（由调用方决定放行与否）
     */
    public byte[] readObjectHead(String fileUrl, int n) {
        String objectName = objectNameOf(fileUrl);
        if (objectName == null) {
            log.warn("无法从 URL 解析出 OSS 对象名，跳过头部读取：url={}", fileUrl);
            return null;
        }
        String endpoint = aliOssProperties.getEndpoint();
        String bucketName = aliOssProperties.getBucketName();
        String region = aliOssProperties.getRegion();

        OSS ossClient = OSSClientBuilder.create()
                .endpoint(endpoint)
                .credentialsProvider(credentialsProvider())
                .clientConfiguration(clientConfig())
                .region(region)
                .build();
        try {
            com.aliyun.oss.model.GetObjectRequest request =
                    new com.aliyun.oss.model.GetObjectRequest(bucketName, objectName);
//            Range 闭区间 [0, n-1]：只拉文件头，几 MB 的产物也只传几 KB
            request.setRange(0, n - 1L);
            try (OSSObject ossObject = ossClient.getObject(request)) {
                return ossObject.getObjectContent().readAllBytes();
            }
        } catch (Exception e) {
            log.warn("读取 OSS 对象头部失败（校验将跳过）：object={} 原因={}", objectName, e.getMessage());
            return null;
        } finally {
            ossClient.shutdown();
        }
    }
}