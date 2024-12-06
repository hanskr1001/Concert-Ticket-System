package com.hmdp.rabbitmq;

import com.hmdp.config.RabbitConfig;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class VoucherOrderPublisher {
    @Autowired
    private IVoucherOrderService iVoucherOrderService;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void publishOrder(String msg){
        log.info("发送消息：" + msg);
        rabbitTemplate.convertAndSend("order.direct","seckill.order",msg);
    }
}
