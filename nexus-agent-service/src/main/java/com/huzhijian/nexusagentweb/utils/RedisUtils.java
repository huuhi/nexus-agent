package com.huzhijian.nexusagentweb.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.huzhijian.nexusagentweb.content.RedisContent.*;

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
     *
     * @param key 键
     * @return 返回是否成功获取互斥锁
     * @show 获取互斥锁，过期时间默认为5秒
     */
    public boolean tryLock(String key){
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     *
     * @param key 键
     * @param ttl 过期时间
     * @return 获取互斥锁，时间单位默认是秒
     */
    public boolean tryLock(String key,Long ttl){
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", ttl, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * @param key 解锁
     */
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    /**
     *
     * @param key 键
     * @param value 值
     * @param time 过期时间
     * @param unit 时间单位
     * @shwo 在指定的时间添加1-10的随机值，防止缓存雪崩
     */
    public  void set(String key,Object value,Long time , TimeUnit unit){
        String json = JSONUtil.toJsonStr(value);
        int i = RandomUtil.randomInt(1, 10);
        stringRedisTemplate.opsForValue().set(key,json,time+i,unit);
    }
    /**
     *
     * @param keyPrefix 前缀键
     * @param id 查询的id
     * @param clazz 返回的类型
     * @param dbFallback 查询数据库的函数
     * @param time 过期时间
     * @param unit 时间单位
     * @return  返回指定的类型数据
     * @param <R> 返回的类型
     * @param <ID> 查询的id的类型
     * @author 胡志坚
     * @show 此方法解决了缓存穿透问题，在指定的时间过期上加1-10的随机值，防止缓存雪崩
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> clazz,
                                         Function<ID,R> dbFallback, Long time,
                                         TimeUnit unit){
//        获取缓存
        String key=keyPrefix+id;
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s)){
//        有缓存，直接返回
            return JSONUtil.toBean(s,clazz);
        }
//        如果不为空，返回null，防止缓存穿透
        if(s!=null){
            return null;
        }
//        查询
        R r= dbFallback.apply(id);
//        没有结果，缓存空
        if(r==null){
//            防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        防止雪崩
        int random = RandomUtil.randomInt(1, 10);
        this.set(key,r,time+random,unit);
        return r;
    }
    public <R> R getCache(String key,Class<R> clazz){
        String s = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(s)){
            return JSONUtil.toBean(s,clazz);
        }
        return null;
    }
    /**
     *
     * @param keyPrefix 前缀键
     * @param id 查询的id
     * @param clazz 返回的类型
     * @param dbFallback 查询数据库的函数
     * @param time 过期时间
     * @param unit 时间单位
     * @return  返回指定的类型数据
     * @param <R> 返回的类型
     * @param <ID> 查询的id的类型
     * @author 胡志坚
     * @show 此方法使用互斥锁解决了缓存击穿问题，在指定的时间过期上加1-10的随机值，防止缓存雪崩
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> clazz,
                                    Function<ID, R> dbFallback, Long time,
                                    TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. 查缓存
        R r = getCache(key, clazz);
        if (r != null) {
            return r;
        }

        String lockKey = LOCK_KEY + id;
        boolean locked = false;

        // 2. 尝试获取锁（重试3次，每次等待200ms，共600ms）
        for (int i = 0; i < 3; i++) {
            locked = tryLock(lockKey);
            if (locked) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("获取锁被中断", e);
            }
        }

        // 3. 没拿到锁：自旋等待并重试（递归或循环调用自己）
        if (!locked) {
            // 延迟100ms后再次尝试获取缓存（此时查库的线程可能已经写好了）
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 再次调用自身，利用缓存返回
            return queryWithMutex(keyPrefix, id, clazz, dbFallback, time, unit);
        }

        // 4. 拿到锁，执行业务
        try {
            // Double-Check：防止在等待锁期间，别的线程已经重建了缓存
            R cached = getCache(key, clazz);
            if (cached != null) {
                return cached;
            }

            // 查数据库
            R result = dbFallback.apply(id);
            if (result == null) {
                // 防穿透：缓存空对象
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 防雪崩：设置随机过期时间（1~10秒随机偏移）
            int randomOffset = RandomUtil.randomInt(1, 10);
            this.set(key, result, time + randomOffset, unit);
            return result;

        } finally {
            // 5. 务必释放锁（如果当前线程还持有锁）
            unlock(lockKey);
        }
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
