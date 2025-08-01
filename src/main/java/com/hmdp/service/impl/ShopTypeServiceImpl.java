package com.spiketrade.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.spiketrade.dto.Result;
import com.spiketrade.entity.ShopType;
import com.spiketrade.mapper.ShopTypeMapper;
import com.spiketrade.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.spiketrade.utils.RedisConstants.CACHE_SHOP_ALL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        // 1.从redis中查询店铺类型
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_ALL);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            // 3.若存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(JSONUtil.parseArray(shopTypeJson), ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 4.不存在，从数据库中查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 5.数据库也不存在，返回错误
        if(CollUtil.isEmpty(shopTypeList)){
            return Result.fail("店铺类型不存在！");
        }

        // 6.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_ALL, JSONUtil.toJsonStr(shopTypeList));
        // 7.返回
        return Result.ok(shopTypeList);
    }
}
