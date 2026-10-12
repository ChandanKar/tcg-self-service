package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentTemplateRepository extends JpaRepository<EnvironmentTemplate, String> {

    List<EnvironmentTemplate> findByActiveTrueOrderByName();

    List<EnvironmentTemplate> findAllByOrderByName();

    Optional<EnvironmentTemplate> findByNameIgnoreCase(String name);
}
