package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.tools.BoxTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

/**
 * ⚠️ 人工集成测试，**默认禁用**。
 * <p>
 * 本类会真实访问外部服务（E2B 云沙盒 / OSS / 大模型 API），因此：
 * 1. 会产生真实费用（如创建 E2B 沙盒）与网络依赖，不适合放进 CI；
 * 2. 结果随外部服务状态波动，失败不代表代码有问题。
 * <p>
 * 需要人工验证时，去掉下面的 @Disabled 再单独运行本类。
 */
@Disabled("人工集成测试：会访问真实外部服务（含计费服务），需要时手动运行")
@Tag("manual")
@SpringBootTest
@Slf4j
@EnableAutoConfiguration(excludeName = "com.huzhijian.nexusagentweb.config.WebSocketConfiguration")
public class BoxToolTest {

    @Autowired
    private BoxTool boxTool;

    private static final String TEST_FILE_URL =
            "https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/test/test2.md";

    @Test
    void fullFlowTest() {

        // 1️⃣ 创建沙盒
        Map<String,Object> createResp = boxTool.createBox(null);
        log.info("createBox: {}", createResp);

        String boxId = createResp.get("box_id").toString();
        if (boxId == null) {
            throw new RuntimeException("创建沙盒失败");
        }

        try {

            // 2️⃣ 上传文件
            UploadFileDTO uploadDTO = new UploadFileDTO(
                    TEST_FILE_URL,
                    "/tmp/test2.md",
                    boxId
            );

            Map<String,Object> uploadResp = boxTool.uploadFile(null, uploadDTO);
            log.info("uploadFile: {}", uploadResp);


            // 3️⃣ 检查文件是否存在
            Map<String,Object> existResp =
                    boxTool.existFile(null, "/tmp/test2.md", boxId);
            log.info("existFile: {}", existResp);


            // 4️⃣ 查看目录
            List<Map<String, Object>> listResp =
                    boxTool.listDir(null, "/tmp", boxId);
            log.info("listDir: {}", listResp);


            // 5️⃣ 下载文件
            Map<String,Object> downloadResp =
                    boxTool.download(null, "/tmp/test2.md", boxId);
            log.info("downloadFile: {}", downloadResp);


//            // 6️⃣ 创建并写入文件
//            CreateFileDTO createDTO = new CreateFileDTO(
//                    "/tmp/hello.txt",
//                    boxId,
//                    "Hello World\nThis is a test file"
//            );
//
//            Map<String,Object> createFileResp =
//                    boxTool.createWithWriteFile(createDTO);
//            log.info("createWriteFile: {}", createFileResp);


            // 7️⃣ 执行代码（Python）
            String code = """
                    print("Hello from sandbox")
                    x = 1 + 2
                    print("result:", x)
                    """;

            Map<String,Object> codeResp =
                    boxTool.executeCode(null, code, boxId);
            log.info("executeCode: {}", codeResp);


            // 8️⃣ 执行命令
            Map<String,Object> cmdResp =
                    boxTool.executeCmd(null, "ls -l /tmp", boxId);
            log.info("executeCmd: {}", cmdResp);


        } finally {
            // 9️⃣ 删除沙盒（一定要放 finally，防止资源泄露扣钱💀）
            Map<String,Object> deleteResp =
                    boxTool.deleteBox(null, boxId);
            log.info("deleteBox: {}", deleteResp);
        }
    }
    @Test
    void testCode(){
        String code = """
                    print("Hello from sandbox")
                    x = 1 + 2
                    print("result:", x)
                    """;
        Map<String,Object> codeResp =
                boxTool.executeCode(null, code, "i6ps4yn8g812lo4jf3vt8");
        log.info("executeCode: {}", codeResp);
    }
}