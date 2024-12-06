package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result  queryById(Long id) {
        //缓存穿透
        //return Result.ok(queryByIdWithPassThrough(id));

        //缓存击穿
        //return Result.ok(queryByIdWithMutex(id));

        //逻辑过期
        Shop shop = queryByIdWithLogicalExpire(id);

        /*Shop shop = cacheClient.queryByIdWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);*/

        return Result.ok(shop);
    }


    /*
    * 逻辑过期解决缓存击穿
    * */
    public Shop queryByIdWithLogicalExpire(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        //未命中缓存直接返回空
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        //反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if(flag){
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2RedisData(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    delLock(lockKey);
                }
            });
        }
        return shop;
    }


    /*
    * 互斥锁解决缓存击穿
    * */
    public Shop queryByIdWithMutex(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (!StrUtil.isBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson == null){
            return null;
        }
        String lock = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean success = tryLock(lock);
            if(!success){
                Thread.sleep(20);
                return queryByIdWithMutex(id);
            }
            if (!StrUtil.isBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            if(shopJson == null){
                return null;
            }
            shop = getById(id);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            }
            stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
            delLock(lock);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            delLock(lock);
        }
        return shop;
    }


    /*
    * 缓存穿透
    * */
    public Shop queryByIdWithPassThrough(Long id){
        String shopKey = CACHE_SHOP_KEY + id;

        /*
        * 布隆过滤器
        * */
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        config.useSingleServer().setPassword("123456");

        RedissonClient redissonClient = Redisson.create(config);
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("Shop");
        bloomFilter.tryInit(100,0.01);
        boolean contains = bloomFilter.contains(shopKey);
        if(!contains){
            log.error("该店铺不存在！");
            return null;
        }


        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        /*
        * 返回空对象
        * */
        if (!StrUtil.isBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson == null){
            return null;
        }
        Shop shop = getById(id);
        if(shop == null){
            stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        }
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void delLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2RedisData(Long id,Long seconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        //先对数据库操作，再删除缓存
        if(shop.getId() == null){
            return Result.fail("商店不存在！");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        String shopKey = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }
}
