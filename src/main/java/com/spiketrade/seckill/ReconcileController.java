package com.spiketrade.seckill;

import com.spiketrade.dto.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-order/reconcile")
@ConditionalOnProperty(name = "trade.seckill.reconcile.http-enabled", havingValue = "true")
public class ReconcileController {

    private final SeckillReconciler reconciler;

    public ReconcileController(SeckillReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @GetMapping("/{voucherId}")
    public Result reconcile(@PathVariable("voucherId") Long voucherId) {
        return Result.ok(reconciler.reconcile(voucherId));
    }
}