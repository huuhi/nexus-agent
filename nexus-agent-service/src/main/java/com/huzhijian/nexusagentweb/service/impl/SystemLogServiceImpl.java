package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.SystemLog;
import com.huzhijian.nexusagentweb.service.SystemLogService;
import com.huzhijian.nexusagentweb.mapper.SystemLogMapper;
import org.springframework.stereotype.Service;

/**
* @author windows
* @description 针对表【system_log(系统日志表)】的数据库操作Service实现
* @createDate 2026-05-06 18:24:38
*/
@Service
public class SystemLogServiceImpl extends ServiceImpl<SystemLogMapper, SystemLog>
    implements SystemLogService{

}




