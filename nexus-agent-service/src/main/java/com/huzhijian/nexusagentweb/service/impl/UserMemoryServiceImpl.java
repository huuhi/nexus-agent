package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.dto.SearchMemoryRequest;
import com.huzhijian.nexusagentweb.mapper.UserMemoryMapper;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Service实现
* @createDate 2026-05-10 21:29:23
*/
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryServiceImpl extends ServiceImpl<UserMemoryMapper, UserMemory>
    implements UserMemoryService {
    private final UserMemoryMapper mapper;



    @Async
    public void saveMemory(UserMemory userMemory){
//        String content = userMemory.getContent();
//        float[] embedding = embeddingText(content);
//        userMemory.setEmbedding(embedding);
        save(userMemory);
    }

    @Override
    public String searchMemory(SearchMemoryRequest request) {
//        float[] embedding = request.embedding();
//        String query = request.query();
//        if (embedding == null&&query==null) {
//            return "参数错误！";
//        }
//        if (embedding == null) {
//            embedding = embeddingModel.embed(query).content().vector();
//        }
        List<String> list = mapper.search(request).stream().map(m -> m.getCategory() + "_" + m.getContent()).toList();
        return list.isEmpty() ?"啥也没有":list.toString();
    }

    @Override
    public List<UserMemoryVO> getMemory(String key) {
        Long userId = UserContextHolder.getUserId();
        List<UserMemory> list = query().eq("user_id", userId)
                .like(key!=null,"content", key).list();
        if (list==null||list.isEmpty()){
            return List.of();
        }
        return BeanUtil.copyToList(list, UserMemoryVO.class);

    }

    @Override
    public void deleteById(Long id) {
//        ???
        try {
            removeById(id);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
}




