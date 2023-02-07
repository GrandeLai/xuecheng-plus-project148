package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.UploadObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.slf4j.LoggerFactory.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sun.rmi.runtime.Log;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * @description TODO
 * @author Mr.M
 * @date 2022/9/10 8:58
 * @version 1.0
 */
@Service
public class MediaFileServiceImpl implements MediaFileService {

    @Autowired
    MediaFilesMapper mediaFilesMapper;

    @Autowired
    MediaProcessMapper mediaProcessMapper;

    @Autowired
    MinioClient minioClient;

    //普通文件存储的桶
    @Value("${minio.bucket.files}")
    private String bucket_Files;

    //视频文件存储的桶
    @Value("${minio.bucket.videofiles}")
    private String bucket_videoFiles;

    @Autowired
    MediaFileService currentProxy;

    @Override
    public PageResult<MediaFiles> queryMediaFiels(Long companyId,PageParams pageParams, QueryMediaParamsDto queryMediaParamsDto) {

        //构建查询条件对象
        LambdaQueryWrapper<MediaFiles> queryWrapper = new LambdaQueryWrapper<>();

        //分页对象
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());
        // 查询数据内容获得结果
        Page<MediaFiles> pageResult = mediaFilesMapper.selectPage(page, queryWrapper);
        // 获取数据列表
        List<MediaFiles> list = pageResult.getRecords();
        // 获取数据总数
        long total = pageResult.getTotal();
        // 构建结果集
        PageResult<MediaFiles> mediaListResult = new PageResult<>(list, total, pageParams.getPageNo(), pageParams.getPageSize());
        return mediaListResult;

    }

    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, byte[] bytes, String folder, String objectName) {

        //生成文件id，文件的md5值
        String fileId = DigestUtils.md5Hex(bytes);
        //文件名称
        String filename = uploadFileParamsDto.getFilename();
        //构造objectname
        if (StringUtils.isEmpty(objectName)) {
            objectName = fileId + filename.substring(filename.lastIndexOf("."));
        }
        if (StringUtils.isEmpty(folder)) {
            //通过日期构造文件存储路径
            folder = getFileFolder(new Date(), true, true, true);
        } else if (folder.indexOf("/") < 0) {
            folder = folder + "/";
        }
        //对象名称
        objectName = folder + objectName;
        MediaFiles mediaFiles = null;
        try {
            //上传至文件系统
            addMediaFilesToMinIO(bytes, bucket_Files, objectName);
            //写入文件表
            mediaFiles = currentProxy.addMediaFilesToDb(companyId,fileId,uploadFileParamsDto,bucket_Files,objectName);
            UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
            BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);
            return uploadFileResultDto;
        } catch (Exception e) {
            //log.debug("上传文件失败：{}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
//        return null;

    }

    /**
     * @description 将文件写入minIO
     * @param bytes  文件字节数组
     * @param bucket  桶
     * @param objectName 对象名称
     * @param contentType  内容类型
     * @return void
     * @author Mr.M
     * @date 2022/10/12 21:22
     */
    //将文件上传到分布式文件系统
    private void addMediaFilesToMinIO(byte[] bytes, String bucket, String objectName) {

        //资源的媒体类型
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//默认未知二进制流

        if (objectName.indexOf(".") >= 0) {
            //取objectName中的扩展名
            String extension = objectName.substring(objectName.lastIndexOf("."));
            ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
            if (extensionMatch != null) {
                contentType = extensionMatch.getMimeType();
            }

        }


        try {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    //InputStream stream, long objectSize 对象大小, long partSize 分片大小(-1表示5M,最大不要超过5T，最多10000)
                    .stream(byteArrayInputStream, byteArrayInputStream.available(), -1)
                    .contentType(contentType)
                    .build();
            //上传到minio
            minioClient.putObject(putObjectArgs);
        } catch (Exception e) {
            e.printStackTrace();
            //log.debug("上传文件到文件系统出错:{}", e.getMessage());
            XueChengPlusException.cast("上传文件到文件系统出错");
        }
    }

    //根据扩展名拿匹配的媒体类型
    private String getMimeTypeByExtension(String extension){
        //资源的媒体类型
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//默认未知二进制流
        if(StringUtils.isNotEmpty(extension)){
            ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
            if (extensionMatch != null) {
                contentType = extensionMatch.getMimeType();
            }
        }
        return  contentType;
    }

    /**
     * @description 将文件信息添加到文件表
     * @param companyId  机构id
     * @param fileMd5  文件md5值
     * @param uploadFileParamsDto  上传文件的信息
     * @param bucket  桶
     * @param objectName 对象名称
     * @return com.xuecheng.media.model.po.MediaFiles
     * @author Mr.M
     * @date 2022/10/12 21:22
     */
    @Transactional
    @Override
    public MediaFiles addMediaFilesToDb(Long companyId,String fileMd5,UploadFileParamsDto uploadFileParamsDto,String bucket,String objectName){

        //根据文件名称取出媒体类型
        //扩展名
        String extension = null;
        if(objectName.indexOf(".")>=0){
            extension = objectName.substring(objectName.lastIndexOf("."));
        }
        //获取扩展名对应的媒体类型
        String contentType = getMimeTypeByExtension(extension);

        //从数据库查询文件
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            mediaFiles = new MediaFiles();
            //拷贝基本信息
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            mediaFiles.setId(fileMd5);
            mediaFiles.setFileId(fileMd5);
            mediaFiles.setCompanyId(companyId);
            //图片及mp4文件设置url
            if(contentType.indexOf("image")>=0 || contentType.indexOf("mp4")>=0){
                mediaFiles.setUrl("/" + bucket + "/" + objectName);
            }
            mediaFiles.setBucket(bucket);
            mediaFiles.setFilePath(objectName);
            mediaFiles.setCreateDate(LocalDateTime.now());
            mediaFiles.setAuditStatus("002003");
            mediaFiles.setStatus("1");
            //保存文件信息到文件表
            int insert = mediaFilesMapper.insert(mediaFiles);
            if (insert < 0) {
                XueChengPlusException.cast("保存文件信息失败");
            }
            //如果是avi视频添加到视频待处理表
            if(contentType.equals("video/x-msvideo")){
                MediaProcess mediaProcess = new MediaProcess();
                BeanUtils.copyProperties(mediaFiles,mediaProcess);
                mediaProcess.setStatus("1");//未处理
                mediaProcessMapper.insert(mediaProcess);
            }

        }
        return mediaFiles;

    }



    //根据日期拼接目录
    private String getFileFolder(Date date, boolean year, boolean month, boolean day){
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        //获取当前日期字符串
        String dateString = sdf.format(new Date());
        //取出年、月、日
        String[] dateStringArray = dateString.split("-");
        StringBuffer folderString = new StringBuffer();
        if(year){
            folderString.append(dateStringArray[0]);
            folderString.append("/");
        }
        if(month){
            folderString.append(dateStringArray[1]);
            folderString.append("/");
        }
        if(day){
            folderString.append(dateStringArray[2]);
            folderString.append("/");
        }
        return folderString.toString();
    }

    @Override
    public RestResponse<Boolean> checkFile(String fileMd5) {
        //查询文件信息
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles != null) {
            //桶
            String bucket = mediaFiles.getBucket();
            //存储目录
            String filePath = mediaFiles.getFilePath();
            //文件流
            InputStream stream = null;
            try {
                stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(filePath)
                                .build());

                if (stream != null) {
                    //文件已存在
                    return RestResponse.success(true);
                }
            } catch (Exception e) {
                //文件不存在
                return RestResponse.success(false);
            }
        }
        //文件不存在
        return RestResponse.success(false);
    }



    @Override
    public RestResponse<Boolean> checkChunk(String fileMd5, int chunkIndex) {

        //得到分块文件所在目录
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        //分块文件的路径
        String chunkFilePath = chunkFileFolderPath + chunkIndex;

        //查询文件系统分块文件是否存在
        //查看是否在文件系统存在
        GetObjectArgs getObjectArgs = GetObjectArgs.builder().bucket(bucket_videoFiles).object(chunkFilePath).build();
        try {
            InputStream inputStream = minioClient.getObject(getObjectArgs);
            if(inputStream==null){
                //文件不存在
                return RestResponse.success(false);
            }
        }catch (Exception e){
            //文件不存在
            return RestResponse.success(false);
        }


        return RestResponse.success(true);
    }

    //得到分块文件的目录
    private String getChunkFileFolderPath(String fileMd5) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + "chunk" + "/";
    }

    @Override
    public RestResponse uploadChunk(String fileMd5, int chunk, byte[] bytes) {


        //得到分块文件的目录路径
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        //得到分块文件的路径
        String chunkFilePath = chunkFileFolderPath + chunk;

        try {
            //将分块上传到文件系统
            addMediaFilesToMinIO(bytes, bucket_videoFiles, chunkFilePath);
            //上传成功
            return RestResponse.success(true);
        } catch (Exception ex) {
            //log.debug("上传分块文件失败：{}", e.getMessage());
            return RestResponse.validfail(false,"上传分块失败");
        }
    }

    //检查所有分块是否上传完毕
    private File[] checkChunkStatus(String fileMd5, int chunkTotal) {
        //得到分块文件的目录路径
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        File[] files = new File[chunkTotal];
        //检查分块文件是否上传完毕
        for (int i = 0; i < chunkTotal; i++) {
            String chunkFilePath = chunkFileFolderPath + i;
            //下载文件
            File chunkFile =null;
            try {
                chunkFile = File.createTempFile("chunk" + i, null);
            } catch (IOException e) {
                e.printStackTrace();
                XueChengPlusException.cast("下载分块时创建临时文件出错");
            }
            downloadFileFromMinIO(chunkFile,bucket_videoFiles,chunkFilePath);
            files[i]=chunkFile;
        }
        return files;
    }

    //根据桶和文件路径从minio下载文件
    public File downloadFileFromMinIO(File file,String bucket,String objectName){

        GetObjectArgs getObjectArgs = GetObjectArgs.builder().bucket(bucket).object(objectName).build();
        try(
                InputStream inputStream = minioClient.getObject(getObjectArgs);
                FileOutputStream outputStream =new FileOutputStream(file);
        ) {
            IOUtils.copy(inputStream,outputStream);
            return file;
        }catch (Exception e){
            e.printStackTrace();
            XueChengPlusException.cast("查询分块文件出错");
        }
        return null;
    }

    //合并分块
    @Override
    public RestResponse mergechunks(Long companyId, String fileMd5, int chunkTotal, UploadFileParamsDto uploadFileParamsDto) {
        //下载分块
        File[] chunkFiles = checkChunkStatus(fileMd5, chunkTotal);

        //得到合并后文件的扩展名
        String filename = uploadFileParamsDto.getFilename();
        //扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        File tempMergeFile = null;
        try {
            try {
                //创建一个临时文件作为合并文件
                tempMergeFile = File.createTempFile("'merge'", extension);
            } catch (IOException e) {
                XueChengPlusException.cast("创建临时合并文件出错");
            }

            //创建合并文件的流对象
            try( RandomAccessFile raf_write  =new RandomAccessFile(tempMergeFile, "rw")) {
                byte[] b = new byte[1024];
                for (File file : chunkFiles) {
                    //读取分块文件的流对象
                    try(RandomAccessFile raf_read = new RandomAccessFile(file, "r");) {
                        int len = -1;
                        while ((len = raf_read.read(b)) != -1) {
                            //向合并文件写数据
                            raf_write.write(b, 0, len);
                        }
                    }

                }
            } catch (IOException e) {
                XueChengPlusException.cast("合并文件过程出错");
            }


            //校验合并后的文件是否正确
            try {
                FileInputStream mergeFileStream = new FileInputStream(tempMergeFile);
                String mergeMd5Hex = DigestUtils.md5Hex(mergeFileStream);
                if (!fileMd5.equals(mergeMd5Hex)) {
                    //log.debug("合并文件校验不通过,文件路径:{},原始文件md5:{}", tempMergeFile.getAbsolutePath(), fileMd5);
                    XueChengPlusException.cast("合并文件校验不通过");
                }
            } catch (IOException e) {
                //log.debug("合并文件校验出错,文件路径:{},原始文件md5:{}", tempMergeFile.getAbsolutePath(), fileMd5);
                XueChengPlusException.cast("合并文件校验出错");
            }


            //拿到合并文件在minio的存储路径
            String mergeFilePath = getFilePathByMd5(fileMd5, extension);
            //将合并后的文件上传到文件系统
            addMediaFilesToMinIO(tempMergeFile.getAbsolutePath(), bucket_videoFiles, mergeFilePath);

            //将文件信息入库保存
            uploadFileParamsDto.setFileSize(tempMergeFile.length());//合并文件的大小
            addMediaFilesToDb(companyId, fileMd5, uploadFileParamsDto, bucket_videoFiles, mergeFilePath);

            return RestResponse.success(true);
        }finally {
            //删除临时分块文件
            if(chunkFiles!=null){
                for (File chunkFile : chunkFiles) {
                    if(chunkFile.exists()){
                        chunkFile.delete();
                    }
                }
            }
            //删除合并的临时文件
            if(tempMergeFile!=null){
                tempMergeFile.delete();
            }
        }
    }
    private String getFilePathByMd5(String fileMd5,String fileExt){
        return   fileMd5.substring(0,1) + "/" + fileMd5.substring(1,2) + "/" + fileMd5 + "/" +fileMd5 +fileExt;
    }

    //将文件上传到minIO，传入文件绝对路径
    public void addMediaFilesToMinIO(String filePath, String bucket, String objectName) {
        try {
            minioClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .filename(filePath)
                            .build());
        } catch (Exception e) {
            e.printStackTrace();
            XueChengPlusException.cast("上传文件到文件系统出错");
        }
    }

    @Override
    public MediaFiles getFileById(String id) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(id);
        if(mediaFiles==null){
            XueChengPlusException.cast("文件不存在");
        }
        String url = mediaFiles.getUrl();
        if(StringUtils.isEmpty(url)){
            XueChengPlusException.cast("文件还没有处理，请稍后预览");
        }

        return mediaFiles;
    }
}
