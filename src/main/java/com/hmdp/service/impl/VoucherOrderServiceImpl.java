package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.rabbitmq.VoucherOrderPublisher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
@Slf4j
@Transactional
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private VoucherOrderPublisher voucherOrderPublisher;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IVoucherOrderService iVoucherOrderService;


    /*private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }*/

    public String queueName = "stream.orders";
    //线程任务
    private class VoucherOrderHandle implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列中的信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //指定消费者和组
                            Consumer.from("g1", "c1"),
                            //读取1个消息，阻塞2秒
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //队列名称
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        //没有消息，循环
                        continue;
                    }
                    //解析消息中的订单信息
                    //MapRecord< 消息Id, Map<k, v> >
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);

                    //ACK 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常！");
                    handlePendingList();
                }
            }
        }
    }

    //处理pending-list中未确认的消息
    private void handlePendingList() {
        while(true){
            try {
                //获取pending-list中的信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //判断消息获取是否成功
                if(list == null || list.isEmpty()){
                    break;
                }
                //解析消息中的订单信息
                //MapRecord< 消息Id, Map<k, v> >
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);

                //ACK 确认消息
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

            } catch (Exception e) {
                log.error("处理订单异常！");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    /*
    * 使用阻塞队列
    * */
    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程任务
    private class VoucherOrderHandle implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常！");
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //在新线程中获取用户id
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean success = lock.tryLock();
        if(!success){
            log.error("不可重复下单！");
            return;
        }
        try{
            proxy.createOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    /*
    * 加载Lua脚本
    * */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private IVoucherOrderService proxy;
    /*
    * 执行Lua脚本，使用RabbitMQ消息队列
    * */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long orderId = redisIDWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        Long res = stringRedisTemplate.execute(
                //脚本
                SECKILL_SCRIPT,
                //传入key
                Collections.emptyList(),
                //传入ARGV参数
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int r = res.intValue();
        if(r != 0 ) {
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrderPublisher.publishOrder(JSON.toJSONString(voucherOrder));
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = res.intValue();
        if(r != 0 ) {
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //将订单放入阻塞队列
        orderTasks.add(voucherOrder);

        return Result.ok(orderId);
    }*/


        //乐观锁
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("未到抢购时间！");
//        }
//        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("抢购已经结束！");
//        }
//        if(seckillVoucher.getStock() < 1){
//            return Result.fail("库存不足！");
//        }
//        boolean suc = iSeckillVoucherService.update()
//                        .setSql("stock = stock -1")
//                        .gt("voucher_id", 0)
//                        .update();
//        if(!suc){
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //非分布式锁
//        /*synchronized(userId.toString().intern()) {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        }*/
//
//        //分布式锁
//        /*SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);*/
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean success = lock.tryLock();
//        if(!success){
//            return Result.fail("一人只能下一单！");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    @Override
    public void createOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            log.error("不可重复购买！");
            return;
        }
        boolean success = iSeckillVoucherService.update()
                        .setSql("stock = stock - 1")
                                .eq("voucher_id",voucherOrder.getVoucherId())
                                        .gt("stock",0)
                                                .update();
        if(!success){
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}
