package com.spiketrade.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.spiketrade.dto.UserDTO;
import com.spiketrade.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.spiketrade.utils.RedisConstants.LOGIN_USER_KEY;
import static com.spiketrade.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        // 1.获取session
//        // 1.获取请求头中的token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            response.setStatus(404);
//            return false;
//        }
////        HttpSession session = request.getSession();
//        // 2.获取session中的用户
//        // 2.基于token来获取redis中的用户
//        Map<Object,Object> userMap = stringRedisTemplate.opsForHash()
//                .entries(LOGIN_USER_KEY + token);
////        Object user = session.getAttribute("user");
//        // 3.判断用户是否存在
//        // 4.不存在，拦截
//        if(userMap.isEmpty()){
//            response.setStatus(401);
//            return false;
//        }
//        // 5.将查询到的Hash数据转为UserDTO对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        // 6.保存用户信息到ThreadLocal
//        UserHolder.saveUser(userDTO);
//        // 7.刷新token有效期
//        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
//        // 8.放行

        // 1.判断是否需要拦截（TreadLocal中是否有数据）
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
