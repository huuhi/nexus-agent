package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.File;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import org.springframework.stereotype.Service;

/**
* @author windows
* @description 针对表【file】的数据库操作Service实现
* @createDate 2026-04-16 20:02:47
*/
@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, File>
    implements FileService{

}




