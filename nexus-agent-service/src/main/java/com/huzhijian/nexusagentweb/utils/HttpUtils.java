package com.huzhijian.nexusagentweb.utils;


import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/30
 * 说明:
 */
@Component
public class HttpUtils {
    private final WebClient webClient;

    public HttpUtils(WebClient webClient) {
        this.webClient = webClient;
    }


//    无参数get请求
    public Mono<Map<String,Object>> get(String url){
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>(){});
    }

//    有请求参数get请求
    public Mono<Map<String, Object>> get(String url,Map<String, String> params){
        LinkedMultiValueMap<String,String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return webClient.get()
                .uri(urlBuilder->urlBuilder.path(url).queryParams(form).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>(){});
    }

    public Mono<List<Map<String, Object>>> getWithList(String url, Map<String, String> params){
        LinkedMultiValueMap<String,String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return webClient.get()
                .uri(urlBuilder->urlBuilder.path(url).queryParams(form).build())
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>(){})
                .collectList();
    }
//   带请求体的Post请求
    public Mono<Map<String, Object>> post(String url,Map<String, String> params){
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(params)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>(){});
    }
    public <T> Mono<Map<String, Object>> post(String url, T body){
        return webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>(){});
    }

//    delete
    public Mono<Map<String, Object>> delete(String url,String boxId){
        return webClient.delete()
                .uri(url,boxId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>(){});
    }



}
