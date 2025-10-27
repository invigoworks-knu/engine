package com.coinreaders.engine.domain.repository;

import com.coinreaders.engine.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

}