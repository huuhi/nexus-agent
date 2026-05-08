package com.huzhijian.nexusagentweb.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2025/9/26
 * 说明:
 */
@Component
@Slf4j
public class RedisUtils {
    
//    private static final log log = logFactory.getlog(RedisUtils.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisUtils(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     *
     * @param key 键
     * @param value 值
     * @param ttl 过期时间，默认单位:分钟
     */
    public void set(String key, String value, Long ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl, TimeUnit.MINUTES);
            log.debug("Redis set key: {}, value: {}, ttl: {} minutes", key, value, ttl);
        } catch (Exception e) {
            log.error("Redis set operation failed for key: {}", key, e);
        }
    }
    
    public Boolean exists(String key) {
        try {
            Boolean result = stringRedisTemplate.hasKey(key);
            log.debug("Redis exists check for key: {}, result: {}", key, result);
            return result;
        } catch (Exception e) {
            log.error("Redis exists operation failed for key: {}", key, e);
            return false;
        }
    }
    
    public String get(String key) {
        try {
            String result = stringRedisTemplate.opsForValue().get(key);
            log.debug("Redis get operation for key: {}, result: {}", key, result);
            return result;
        } catch (Exception e) {
            log.error("Redis get operation failed for key: {}", key, e);
            return null;
        }
    }
    public List<MapRecord<String, Object, Object>> getMsg(String key, String group, String c) {
        try {
            if (stringRedisTemplate.getConnectionFactory().getConnection().isClosed()) {
                return Collections.emptyList(); // 连接已关闭，直接返回空
            }
            if(!isConsumerGroupExists(key,group)){
                stringRedisTemplate.opsForStream().createGroup(key, ReadOffset.from("0"), group);
            }

            return stringRedisTemplate.opsForStream().read(
                    Consumer.from(group, c),
                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
                    StreamOffset.create(key, ReadOffset.lastConsumed())
            );
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Long ackAndDelMsg(String key, String group, RecordId recordId) {
        StreamOperations<String, Object, Object> operations = stringRedisTemplate.opsForStream();
        operations.acknowledge(key,group,recordId);
        return  operations.delete(key,recordId);
    }

    /**
     * 检查消费者组是否存在
     */
    private boolean isConsumerGroupExists(String key, String group) {
        try {
            // 使用 XINFO GROUPS 获取 Stream 的所有消费者组
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(key);
            // 检查目标 Group 是否存在
            return groups.stream()
                    .anyMatch(g -> group.equals(g.groupName()));
        } catch (Exception e) {
            // 如果 key 不存在或发生其他错误，返回 false
            return false;
        }
    }
    public void delete(String s) {
        stringRedisTemplate.delete(s);
    }
}
