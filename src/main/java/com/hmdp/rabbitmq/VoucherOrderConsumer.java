package com.hmdp.rabbitmq;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;


@Component
@Slf4j
public class VoucherOrderConsumer {
    @Resource
    private IVoucherOrderService iVoucherOrderService;

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;



    @RabbitListener(queues = "order.queue")
    @Transactional
    public void handleOrder(String orderJson){
        System.out.println("消息为："+orderJson);
        VoucherOrder voucherOrder = JSON.parseObject(orderJson, VoucherOrder.class);
        System.out.println("order:"+voucherOrder);

        Long userId = voucherOrder.getUserId();

        int count = iVoucherOrderService
                .query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if(count > 0){
            log.error("不可重复购买！");
            return;
        }
        boolean success = iSeckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id",voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if(!success) {
            log.error("库存不足！");
            return;
        }
        iVoucherOrderService.save(voucherOrder);
    }

    @RabbitListener(queues = "dead.queue")
    @Transactional
    public void handleDeadOrder(String orderJson){
        log.info("死信队列");
        System.out.println("消息为："+orderJson);
        VoucherOrder voucherOrder = JSON.parseObject(orderJson, VoucherOrder.class);
        System.out.println("order:"+voucherOrder);

        Long userId = voucherOrder.getUserId();

        int count = iVoucherOrderService
                .query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();

        if(count > 0){
            log.error("不可重复购买！");
            return;
        }

        boolean success = iSeckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id",voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if(!success) {
            log.error("库存不足！");
            return;
        }
        iVoucherOrderService.save(voucherOrder);
    }
}
