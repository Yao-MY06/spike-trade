package com.spiketrade.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spiketrade.dto.LoginFormDTO;
import com.spiketrade.dto.Result;
import com.spiketrade.dto.UserDTO;
import com.spiketrade.entity.User;
import com.spiketrade.mapper.UserMapper;
import com.spiketrade.properties.JwtProperties;
import com.spiketrade.service.IUserService;
import com.spiketrade.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.spiketrade.utils.RedisConstants.*;
import static com.spiketrade.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 3. 如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到session  //set key value ex 120
//        session.setAttribute("code", code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 2.校验验证码
        // 从redis中获取验证码并进行校验
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        // 3.不一致，报错
        if(code == null || cacheCode == null ||cacheCode.equals(code)==false) {
            return Result.fail("验证码错误！");
        }

        // 4.一致，根据手机号查询用户
        stringRedisTemplate.delete(LOGIN_CODE_KEY+phone);
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if(user==null) {
            // 6.不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        // 将User对象转为UserDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //登录成功后，生成jwt令牌
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, userDTO.getId());
        claims.put(JwtClaimsConstant.NICK_NAME, userDTO.getNickName());
        claims.put(JwtClaimsConstant.ICON_NAME, userDTO.getIcon());
        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);

//        EmployeeLoginVO employeeLoginVO = EmployeeLoginVO.builder()
//                .id(employee.getId())
//                .userName(employee.getUsername())
//                .name(employee.getName())
//                .token(token)
//                .build();

        return Result.ok(token);

//        // 7.保存用户信息到session中
//        // 7.保存用户信息到redis中
//        // 7.1随机生成一个token,作为登陆令牌
//        String token = UUID.randomUUID().toString(true);
//        // 7.2将User对象转为Hash存储
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
//                CopyOptions.create()
//                        .setIgnoreNullValue(true)
//                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
//                // 7.3存储
//        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token, userMap);
//        // 7.4设置token有效期
//        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
//        // 返回token
////        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 7.使用JWT令牌登录

//        return Result.ok(token);

    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null||result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if ((num & 1)==0) {
                break;
            }else{
                count++;
            }
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }


}
