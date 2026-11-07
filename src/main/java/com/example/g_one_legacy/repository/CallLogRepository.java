package com.example.g_one_legacy.repository;

import com.example.g_one_legacy.entity.CallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CallLogRepository extends JpaRepository<CallLog, Long> {
}

