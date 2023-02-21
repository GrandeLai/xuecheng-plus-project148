package com.xuecheng.learning.feignclient;

import com.xuecheng.content.model.po.CoursePublish;
import feign.hystrix.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @description 内容管理服务远程接口降级类
 * @author Mr.M
 * @date 2022/10/24 17:13
 * @version 1.0
 */
@Slf4j
@Component
public class ContentServiceClientFactory implements FallbackFactory<ContentServiceClient> {
    @Override
    public ContentServiceClient create(Throwable throwable) {

        return new ContentServiceClient(){

            @Override
            public CoursePublish getCoursepublish(Long courseId) {
                log.error("调用内容管理服务接口熔断:{}",throwable.getMessage());
                return null;
            }
        };
    }
}

