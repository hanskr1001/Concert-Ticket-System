package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogical(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /*
    * 缓存穿透
    * */
    public <R,ID> R queryByIdWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbfallback , Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        if(json == null){
            return null;
        }
        R r = dbfallback.apply(id);
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
        }
        this.set(key,r,time,unit);
        return r;
    }

    public <R,ID> R queryByIdWithMutex(
            String keyPreFix, String lockPreFix,ID id, Class<R> type, Long time, Function<ID,R> dbFallBack, TimeUnit unit){
        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(json)) {
            R r = JSONUtil.toBean(json,type);
            return r;
        }
        if(json == null){
            return null;
        }
        String lock = lockPreFix + id;
        R r = null;
        try {
            boolean success = tryLock(lock);
            if(!success){
                Thread.sleep(20);
                return queryByIdWithMutex(keyPreFix,lockPreFix,id,type,time,dbFallBack,unit);
            }
            if (!StrUtil.isBlank(json)) {
                r = JSONUtil.toBean(json, type);
                return r;
            }
            if(json == null){
                return null;
            }
            r = dbFallBack.apply(id);
            if(r == null){
                stringRedisTemplate.opsForValue().set(key,"",time,unit);
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),time,unit);
            delLock(lock);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            delLock(lock);
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void delLock(String key){
        stringRedisTemplate.delete(key);
    }



}
