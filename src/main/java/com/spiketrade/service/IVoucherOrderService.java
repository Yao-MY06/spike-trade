package com.spiketrade.service;

import com.spiketrade.dto.Result;
import com.spiketrade.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author spike
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);


//    Result createVoucherOrder(Long voucherId);

}
