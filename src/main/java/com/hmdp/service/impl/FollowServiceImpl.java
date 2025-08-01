package com.spiketrade.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.spiketrade.dto.Result;
import com.spiketrade.dto.UserDTO;
import com.spiketrade.entity.Follow;
import com.spiketrade.entity.User;
import com.spiketrade.mapper.FollowMapper;
import com.spiketrade.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spiketrade.service.IUserService;
import com.spiketrade.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取登录的用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;

        // 1.判断是关注还是取关
        if(isFollow){
            // 2.关注，添加数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){

                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            // 3.取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 获取登录的用户id
        Long userId = UserHolder.getUser().getId();

        // 查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(a -> Long.valueOf(a)).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        if(users==null||users.isEmpty()){
            return Result.ok();
        }
        return Result.ok(users);
    }
}
