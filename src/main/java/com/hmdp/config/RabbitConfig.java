package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class RabbitConfig {
    @Bean
    public DirectExchange orderExchange(){
        return new DirectExchange("order.direct");
    }

    @Bean
    public DirectExchange deadExchange(){
        return new DirectExchange("dead.direct");
    }

    @Bean
    public Queue orderQueue(){
        HashMap<String,Object> arguments = new HashMap<>();

        arguments.put("x-dead-letter-exchange","dead.direct");
        arguments.put("x-dead-letter-routing-key","deadMessage");
        arguments.put("x-message-ttl",10000);

        return new Queue("order.queue",true,false,false,arguments);
    }

    @Bean
    public Queue deadQueue(){
        return new Queue("dead.queue",true);
    }

    @Bean
    public Binding orderBinding(Queue orderQueue,DirectExchange orderExchange){
        return BindingBuilder.bind(orderQueue).to(orderExchange).with("seckill.order");
    }

    @Bean
    public Binding deadOrderBinding(Queue deadQueue,DirectExchange deadExchange){
        return BindingBuilder.bind(deadQueue).to(deadExchange).with("dead.order");
    }
}
