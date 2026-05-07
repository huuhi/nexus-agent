package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.dto.CreateFileDTO;
import com.huzhijian.nexusagentweb.dto.UploadFileDTO;
import com.huzhijian.nexusagentweb.tools.BoxTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

@SpringBootTest
@Slf4j
public class BoxToolTest {

    @Autowired
    private BoxTool boxTool;

    private static final String TEST_FILE_URL =
            "https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/test/test2.md";

    @Test
    void fullFlowTest() {

        // 1️⃣ 创建沙盒
        Map<String,Object> createResp = boxTool.createBox();
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

            Map<String,Object> uploadResp = boxTool.uploadFile(uploadDTO);
            log.info("uploadFile: {}", uploadResp);


            // 3️⃣ 检查文件是否存在
            Map<String,Object> existResp =
                    boxTool.existFile(boxId, "/tmp/test2.md");
            log.info("existFile: {}", existResp);


            // 4️⃣ 查看目录
            List<Map<String, Object>> listResp =
                    boxTool.listDir("/tmp", boxId);
            log.info("listDir: {}", listResp);


            // 5️⃣ 下载文件
            Map<String,Object> downloadResp =
                    boxTool.download("/tmp/test2.md", boxId);
            log.info("downloadFile: {}", downloadResp);


            // 6️⃣ 创建并写入文件
            CreateFileDTO createDTO = new CreateFileDTO(
                    "/tmp/hello.txt",
                    boxId,
                    "Hello World\nThis is a test file"
            );

            Map<String,Object> createFileResp =
                    boxTool.createWithWriteFile(createDTO);
            log.info("createWriteFile: {}", createFileResp);


            // 7️⃣ 执行代码（Python）
            String code = """
                    print("Hello from sandbox")
                    x = 1 + 2
                    print("result:", x)
                    """;

            Map<String,Object> codeResp =
                    boxTool.executeCode(boxId, code);
            log.info("executeCode: {}", codeResp);


            // 8️⃣ 执行命令
            Map<String,Object> cmdResp =
                    boxTool.executeCmd(boxId, "ls -l /tmp");
            log.info("executeCmd: {}", cmdResp);


        } finally {
            // 9️⃣ 删除沙盒（一定要放 finally，防止资源泄露扣钱💀）
            Map<String,Object> deleteResp =
                    boxTool.deleteBox(boxId);
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
                boxTool.executeCode("i6ps4yn8g812lo4jf3vt8", code);
        log.info("executeCode: {}", codeResp);
    }
}