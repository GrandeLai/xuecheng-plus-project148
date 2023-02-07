package com.xuecheng.search.service.impl;

import com.alibaba.fastjson.JSON;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.search.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * @description 课程索引管理接口实现
 * @author Mr.M
 * @date 2022/9/25 7:23
 * @version 1.0
 */
@Slf4j
@Service
public class IndexServiceImpl implements IndexService {



    @Autowired
    RestHighLevelClient client;

    @Override
    public Boolean addCourseIndex(String indexName,String id,Object object) {
        String jsonString = JSON.toJSONString(object);
        IndexRequest indexRequest = new IndexRequest(indexName).id(id);
        //指定索引文档内容
        indexRequest.source(jsonString, XContentType.JSON);
        //索引响应对象
        IndexResponse indexResponse = null;
        try {
            indexResponse = client.index(indexRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.error("添加索引出错:{}",e.getMessage());
            e.printStackTrace();
            XueChengPlusException.cast("添加索引出错");
        }
        String name = indexResponse.getResult().name();
        System.out.println(name);
        return name.equalsIgnoreCase("created") || name.equalsIgnoreCase("updated");

    }
}
