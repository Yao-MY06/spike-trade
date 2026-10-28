package com.spiketrade.service.impl;

import com.spiketrade.entity.UserInfo;
import com.spiketrade.mapper.UserInfoMapper;
import com.spiketrade.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author spike
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
