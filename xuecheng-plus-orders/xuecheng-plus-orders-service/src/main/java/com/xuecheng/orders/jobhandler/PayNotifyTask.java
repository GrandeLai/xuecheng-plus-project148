package com.xuecheng.orders.jobhandler;

import com.alibaba.fastjson.JSON;
import com.xuecheng.messagesdk.model.po.MqMessage;
import com.xuecheng.messagesdk.service.MessageProcessAbstract;
import com.xuecheng.messagesdk.service.MqMessageService;
import com.xuecheng.orders.config.PayNotifyConfig;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Mr.M
 * @version 1.0
 * @description 支付通知任务
 * @date 2022/9/22 10:16
 */
@Slf4j
@Component
public class PayNotifyTask extends MessageProcessAbstract {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    MqMessageService mqMessageService;

    //任务调度入口
    @XxlJob("NotifyPayResultJobHandler")
    public void notifyPayResultJobHandler() throws Exception {
        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        log.debug("shardIndex="+shardIndex+",shardTotal="+shardTotal);
        //只查询支付通知的消息
        process(shardIndex,shardTotal, PayNotifyConfig.MESSAGE_TYPE,5,60);
    }

    //课程发布任务处理
    @Override
    public boolean execute(MqMessage mqMessage) {

        log.debug("开始进行支付结果通知:{}",mqMessage.toString());
        //发送到消息队列
        send(mqMessage);

        //由于消息表的记录需要等到订单服务收到回复后才能删除，这里返回false不让消息sdk自动删除
        return false;
    }

    /**
     * @description 发送支付结果通知
     * @param message  消息内容
     * @return void
     * @author Mr.M
     * @date 2022/9/20 9:43
     */
    private void send(MqMessage message){
        //转json
        String msg = JSON.toJSONString(message);
        // 发送消息
        rabbitTemplate.convertAndSend(PayNotifyConfig.PAYNOTIFY_EXCHANGE_FANOUT, "", msg);

    }

    //接收回复
    @RabbitListener(queues = PayNotifyConfig.PAYNOTIFY_REPLY_QUEUE)
    public void receive(String message) {
        //获取消息
        MqMessage mqMessage = JSON.parseObject(message, MqMessage.class);
        log.debug("接收支付结果回复:{}", mqMessage);

        //完成支付通知
        mqMessageService.completed(mqMessage.getId());

    }

}
