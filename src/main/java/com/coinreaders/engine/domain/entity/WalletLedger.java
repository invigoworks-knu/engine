package com.coinreaders.engine.domain.entity;

import com.coinreaders.engine.domain.common.BaseTimeEntity;
import com.coinreaders.engine.domain.constant.LedgerReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "wallet_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletLedger extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 16)
    private String currency;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal amountChanged;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LedgerReason reason;

    @Column(name = "ref_id")
    private Long referenceId;

}