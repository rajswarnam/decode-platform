package com.decode.code.parser.repository;

import com.decode.code.parser.domain.AclfMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AclfMappingRepository extends JpaRepository<AclfMapping, UUID> {
}
