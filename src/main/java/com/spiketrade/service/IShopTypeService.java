package com.spiketrade.service;

import com.spiketrade.dto.Result;
import com.spiketrade.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryList();
}
