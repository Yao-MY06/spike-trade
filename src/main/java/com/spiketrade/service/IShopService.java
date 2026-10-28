package com.spiketrade.service;

import com.spiketrade.dto.Result;
import com.spiketrade.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryBiId(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
