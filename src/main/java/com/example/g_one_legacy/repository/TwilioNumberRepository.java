package com.example.g_one_legacy.repository;

import com.example.g_one_legacy.entity.TwilioNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TwilioNumberRepository extends JpaRepository<TwilioNumber, Long> {

    Optional<TwilioNumber> findByPhoneNumber(String phoneNumber);
}

