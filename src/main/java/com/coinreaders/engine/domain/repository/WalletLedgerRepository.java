package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.WalletLedger;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

}