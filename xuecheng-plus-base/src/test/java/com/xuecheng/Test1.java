package com.xuecheng;

import com.xuecheng.base.utils.QRCodeUtil;

import java.io.IOException;

public class Test1 {
    public static void main(String[] args) throws IOException {
        QRCodeUtil qrCodeUtil = new QRCodeUtil();
        System.out.println(qrCodeUtil.createQRCode("http://192.168.2.106:63030/orders/alipaytest", 200, 200));
    }
}
