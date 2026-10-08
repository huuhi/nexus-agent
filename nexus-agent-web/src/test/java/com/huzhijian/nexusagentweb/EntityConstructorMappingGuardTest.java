package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-08「文件列表接口稳定 500」事故的防复发护栏。
 * <p>
 * <b>线上现象</b>：{@code GET /api/file}（用户文件列表）每次必 500，日志里是
 * <pre>
 * Caused by: java.lang.IllegalArgumentException:
 *     No enum constant com.huzhijian.nexusagentweb.em.UploadFailCode.SUCCESS
 *   at com.huzhijian.nexusagentweb.service.impl.FileServiceImpl.getFileByUserId(FileServiceImpl.java:174)
 * </pre>
 * <b>根因</b>（坑的形状值得记住，它跟枚举毫无关系）：
 * <ol>
 *   <li>{@code SysFile} 只有 Lombok {@code @Builder} 生成的全参构造器，<b>没有无参构造器</b>；</li>
 *   <li>{@code failCode} 字段标了 {@code @TableField(exist = false)} —— 它<b>不参与 SQL 列列表</b>；</li>
 *   <li>MyBatis 在「结果类型无无参构造器」时会退化成
 *       {@code applyColumnOrderBasedConstructorAutomapping}：<b>按结果集的列顺序</b>
 *       逐个喂给构造器参数。</li>
 * </ol>
 * 于是 SELECT 出来 12 列、构造器有 13 个参数，第 7 列的 {@code upload_status='SUCCESS'}
 * 被喂给了第 7 个参数 {@code failCode}（{@code UploadFailCode} 的合法值是 UNSUPPORTED_TYPE
 * / QUOTA_EXCEEDED / …，<b>没有</b> SUCCESS）—— 当场抛异常，整个接口 500。
 * <p>
 * <b>为什么这条护栏是完备的（限定在 MyBatis-Plus 自动 SQL 场景）</b>：
 * MP 生成 SELECT 时，列顺序严格按实体的字段声明顺序；而 {@code exist = false} 的字段
 * <b>不生成列</b>。所以「字段顺序 == 列顺序」当且仅当「实体没有 exist=false 字段」。
 * 也就是说，这条不变式恰好覆盖了所有会错位的形态，不会多报也不会漏报。
 * <p>
 * ⚠️ <b>不覆盖的场景</b>：手写 XML 里自定义列顺序的 select（本仓库 {@code FileMapper.xml}
 * 的 {@code BaseResultMap} 目前没有被任何 select 引用，是死配置）。若将来启用，
 * 要么保证列顺序 == 字段顺序，要么给实体补无参构造器走按名映射。
 * <p>
 * ⚠️ 修法选择：本次用「加 {@code @NoArgsConstructor}」让 MyBatis 回到<b>按名字</b>映射，
 * 是零风险止血。真正的治本是把这类纯传输字段从<b>实体</b>挪到 VO —— 属待办技术债。
 */
@DisplayName("实体构造器映射（防「不入库字段」把按序映射打错位）")
class EntityConstructorMappingGuardTest {

    /** 实体所在的包 */
    private static final String DOMAIN_PKG = "com.huzhijian.nexusagentweb.domain";

    @Test
    @DisplayName("带 exist=false 字段的实体必须有 public 无参构造器")
    void entityWithNonPersistentFieldNeedsNoArgConstructor() throws Exception {
        List<Class<?>> entities = loadDomainClasses();
        assertTrue(entities.size() >= 5,
                "只扫到 " + entities.size() + " 个 domain 类，扫描逻辑多半失效了 ——"
                        + "护栏静默通过比没有护栏更危险（2026-10-06 的 MemoryWindowDriftTest 就是这么废掉的）。");

        List<String> offenders = new ArrayList<>();
        for (Class<?> type : entities) {
            Field[] nonPersistent = Arrays.stream(type.getDeclaredFields())
                    .filter(f -> {
                        TableField tf = f.getAnnotation(TableField.class);
                        return tf != null && !tf.exist();
                    })
                    .toArray(Field[]::new);
            if (nonPersistent.length == 0) {
                continue; // 列顺序与字段顺序天然一致，构造器映射也不会错位
            }
            if (hasNoArgConstructor(type)) {
                continue; // MyBatis 会走「无参构造 + 按名 setter」，安全
            }
            offenders.add(type.getSimpleName() + "（不入库字段："
                    + Arrays.stream(nonPersistent).map(Field::getName).toList() + "）");
        }

        if (!offenders.isEmpty()) {
            fail("以下实体有 @TableField(exist = false) 字段，却没有无参构造器，"
                    + "MyBatis 会退化成「按结果集列顺序喂构造器」的映射方式，"
                    + "不入库字段会顶掉后面所有字段的位置：\n  - " + String.join("\n  - ", offenders)
                    + "\n\n典型症状：`No enum constant <枚举类>.<别的枚举的值>` —— 2026-10-08 "
                    + "SysFile.failCode 顶掉 uploadStatus，导致文件列表接口稳定 500。\n"
                    + "修法：给该实体加 @NoArgsConstructor（并保留 @Builder + @AllArgsConstructor）。");
        }
    }

    /** 扫描 domain 包下的顶层类（跳过内部类） */
    private static List<Class<?>> loadDomainClasses() throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource(DOMAIN_PKG.replace('.', '/'));
        assertNotNull(url, "定位不到 " + DOMAIN_PKG + " 的类路径资源，无法扫描实体");
        assertTrue("file".equals(url.getProtocol()),
                "domain 包不是目录（" + url.getProtocol() + "），本护栏无法扫描。"
                        + "若测试改为 jar 内运行，需要改用 Spring 的 ClassPathScanning 重写。");

        File dir = new File(url.toURI());
        File[] classFiles = dir.listFiles((d, name) -> name.endsWith(".class") && !name.contains("$"));
        assertNotNull(classFiles, "列不出 " + dir + " 下的 class 文件");

        List<Class<?>> entities = new ArrayList<>();
        for (File f : classFiles) {
            String simpleName = f.getName().substring(0, f.getName().length() - ".class".length());
            Class<?> type = Class.forName(DOMAIN_PKG + "." + simpleName);
            if (type.getAnnotation(TableName.class) != null) {
                entities.add(type);
            }
        }
        return entities;
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }
}
